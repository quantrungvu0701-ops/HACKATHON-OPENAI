package vn.chiday.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import vn.chiday.app.guide.GuideEngine
import vn.chiday.app.guide.GuideResult
import vn.chiday.app.guide.Speaker
import vn.chiday.app.guide.TargetResolver
import vn.chiday.app.overlay.BubbleWindow
import vn.chiday.app.overlay.HighlightWindow
import vn.chiday.app.playbook.PlaybookStore
import vn.chiday.app.screen.ScreenReader
import vn.chiday.app.screen.ScreenSnapshot
import vn.chiday.app.screen.UiNode
import vn.chiday.app.settings.SettingsStore
import kotlin.math.abs

/**
 * The core of the app. One accessibility permission gives us the overlay windows and the
 * screen tree. Guide-only: this class must never call performAction or dispatchGesture.
 *
 * Session model (MECHANISM.md §2.5): one bubble tap starts a session; while it is active the
 * service re-analyses on its own each time the screen moves on; a second tap ends it.
 * Nothing is read outside a session.
 */
class GuideService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settings: SettingsStore
    private lateinit var engine: GuideEngine
    private var bubble: BubbleWindow? = null
    private var highlight: HighlightWindow? = null
    private var speaker: Speaker? = null

    private var sessionActive = false

    /** The model call in flight. One at a time; a newer analysis cancels the old one. */
    private var analysis: Job? = null
    private var settle: Job? = null
    private var messageTimeout: Job? = null
    private var idleTimeout: Job? = null

    /** The node currently ringed, so the ring can follow a scroll and we can tell when it is gone. */
    private var shown: UiNode? = null

    /** Where the ring is, and in which app: taps are compared against it (tap feedback). */
    private var shownRect: Rect? = null
    private var shownPkg: String? = null
    private var shownOnWeb = false

    /** The card that goes with the ring, so a feedback line can be added without losing it. */
    private var card: Card? = null

    private class Card(
        val title: String,
        val body: String?,
        val footer: String?,
        val caution: Boolean,
        val step: Int?,
        val total: Int?,
        val mark: String?,
    )

    /** Ring plus card: progress segments, the button name marked, the caution band when needed. */
    private fun showCard(ring: Rect, c: Card, body: String?) {
        highlight?.showTarget(
            target = ring,
            title = c.title,
            body = body,
            footer = c.footer,
            caution = c.caution,
            step = c.step,
            total = c.total,
            mark = c.mark,
            cautionNote = getString(R.string.guide_caution),
        )
    }

    /** A "move the screen first" instruction is up (no ring): re-place the target after each scroll. */
    private var moving = false

    /** Each feedback sentence is said at most once per step. */
    private var missSaid = false
    private var stuckSaid = false
    private var stuckCheck: Job? = null

    /** Hash of the last screen sent to the model: an unchanged screen is never analysed twice. */
    private var analysedHash: Int? = null
    private var lastAnalysisAt = 0L
    private var firstPendingEventAt = 0L

    /** What was last spoken, so a re-analysis that gives the same instruction stays quiet. */
    private var lastSpoken: String? = null

    /** Automatic analyses in a row that produced no ring; each one doubles the wait before the next. */
    private var autoMisses = 0

    /**
     * Bumped by every analysis start and by endSession. A model answer is applied only if its
     * generation is still the newest, so an answer for an old screen (or an old session) that
     * arrives late can never overwrite a newer instruction (the "rolls back to step 1" bug).
     */
    private var generation = 0L

    /** Bumped on every click the user makes in another app during a session. */
    private var actionSeq = 0L

    /**
     * The user has clearly acted (pressed the ring, or the ringed element went away): the next
     * analysis uses the short settle and skips the automatic rate limit. Cleared when it starts.
     */
    private var actedFast = false

    /** Bumped each time the card changes, so a stale message timeout never hides a newer card. */
    private var uiSeq = 0L

    /** Keyboard package for this session: its windows never trigger a re-analysis. */
    private var imePkg: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = SettingsStore(this)
        engine = GuideEngine(settings, PlaybookStore(this))
        // Order matters: the highlight window is added first so the bubble stays on top of it.
        highlight = HighlightWindow(this).also { it.attach() }
        speaker = Speaker(this)
        bubble = BubbleWindow(this, ::onBubbleTap, ::onBubbleHold)
        applyBubbleSwitch()
        _running.value = true
        warmUpModel()
        Log.i(TAG, "service connected, bubble ${if (_bubbleOn.value) "shown" else "off"}")
    }

    /** Connects to the model server ahead of the first tap (after a reopen, after "Bắt đầu"). */
    private fun warmUpModel() {
        scope.launch {
            val s = settings.current()
            if (s.isComplete) vn.chiday.app.llm.Llm.warmUp(s.baseUrl)
        }
    }

    /** Press and hold on the bubble: switch it off and say how to get it back. */
    private fun onBubbleHold() {
        Log.i(TAG, "bubble held: switched off")
        setBubbleOn(this, false)
        speaker?.speak(getString(R.string.bubble_off_spoken))
    }

    /** Shows or hides the bubble to match the saved switch. Hiding also ends a running session. */
    private fun applyBubbleSwitch() {
        val on = bubblePrefs(this).getBoolean(KEY_BUBBLE_ON, true)
        _bubbleOn.value = on
        if (on) {
            bubble?.show()
        } else {
            if (sessionActive) endSession()
            bubble?.hide()
        }
    }

    private fun onBubbleTap() {
        if (sessionActive) {
            Log.i(TAG, "bubble tap: session ended")
            endSession()
        } else {
            Log.i(TAG, "bubble tap: session started")
            sessionActive = true
            engine.resetSession()
            imePkg = runCatching {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
            }.getOrNull()
            analyse(auto = false, thinkingCard = true)
        }
    }

    /** Immediate sign that the guide is working while the model call runs (no speech, no ring). */
    private fun showThinking() {
        val mySeq = ++uiSeq
        highlight?.showMessage(getString(R.string.guide_thinking))
        // Never left up forever (e.g. the follow-up read found nothing new to analyse).
        scope.launch {
            delay(THINKING_MAX_MS)
            if (uiSeq == mySeq && highlight?.isShowingTarget == false) highlight?.hide()
        }
    }

    /** Takes a "thinking" card down when an analysis ends with nothing to show. */
    private fun clearThinking() {
        if (card == null && !moving && highlight?.isShowingTarget == false && messageTimeout?.isActive != true) {
            highlight?.hide()
        }
    }

    /** [auto] is true when follow mode triggered this, false for the user's own tap. */
    private fun analyse(auto: Boolean, preRead: ScreenSnapshot? = null, thinkingCard: Boolean = false) {
        analysis?.cancel()
        messageTimeout?.cancel()
        // Our own windows are skipped by the reader, so the ring can stay up while we look.
        val snap = preRead ?: ScreenReader.read(this)
        if (snap == null) {
            Log.w(TAG, "no readable app window")
            if (!auto) showMessage(getString(R.string.guide_no_screen), null, speak = true)
            return
        }
        logSnapshot(snap)
        val myGen = ++generation
        val actionsAtStart = actionSeq
        actedFast = false
        // A "move the screen" card may stay up while we look, but its remembered target must
        // not be re-placed by engine.recheck while this newer analysis runs (stale step).
        moving = false
        analysedHash = snap.hash
        lastAnalysisAt = SystemClock.elapsedRealtime()
        armIdleTimeout()
        bubble?.state = BubbleWindow.State.THINKING
        if (thinkingCard && card == null) showThinking()
        analysis = scope.launch {
            engine.goal = settings.currentGoal()
            val started = SystemClock.elapsedRealtime()
            val result = engine.decide(snap)
            Log.i(TAG, "decision (auto=$auto, gen=$myGen) in ${SystemClock.elapsedRealtime() - started} ms: $result")
            if (myGen != generation || !sessionActive) {
                Log.i(TAG, "decision gen=$myGen is stale (newest $generation): dropped")
                return@launch
            }
            autoMisses = if (result is GuideResult.Show || result is GuideResult.Move || !auto) 0 else autoMisses + 1
            if (result is GuideResult.Show || result is GuideResult.Move) {
                missSaid = false
                stuckSaid = false
                stuckCheck?.cancel()
            }
            when (result) {
                is GuideResult.Show -> {
                    // The model took a second or two: the page may have shifted meanwhile. Put
                    // the ring where the chosen element is NOW; if it is gone, look again.
                    val fresh = ScreenReader.read(this@GuideService)
                    if (fresh == null || fresh.hash == snap.hash) {
                        show(result, snap)
                    } else {
                        val chosen = snap.nodes[result.targetIndex]
                        // Re-place only when it is still the same page and the user has not
                        // pressed anything meanwhile: otherwise the answer belongs to an old
                        // screen, and a same-labelled element on the new one would show a
                        // previous step again.
                        val samePage = fresh.pkg == snap.pkg && fresh.host == snap.host && actionSeq == actionsAtStart
                        val at = if (samePage) fresh.nodes.indexOfFirst { GuideEngine.sameElement(it, chosen) } else -1
                        if (at >= 0) {
                            show(result.copy(targetIndex = at, rect = Rect(fresh.rects[at])), fresh)
                        } else {
                            Log.i(TAG, "screen changed during the model call (samePage=$samePage): looking again")
                            bubble?.state = BubbleWindow.State.ACTIVE
                            analysedHash = null
                            // The user pressed something: no rate limit before the next look.
                            if (actionSeq != actionsAtStart) actedFast = true
                            settle?.cancel()
                            settle = scope.launch {
                                delay(if (actedFast) ACT_SETTLE_MS else SETTLE_MS)
                                onScreenSettled()
                            }
                        }
                    }
                }
                is GuideResult.Move -> showMove(result)
                // In follow mode an unsure answer is shown quietly: the user did not ask just now.
                is GuideResult.NotSure -> if (auto && autoMisses == 1) {
                    // First automatic miss is usually a page that is still loading: stay silent
                    // and look again when it settles, instead of flashing a message.
                    Log.i(TAG, "auto analysis unsure once: waiting for the screen to settle")
                    bubble?.state = BubbleWindow.State.ACTIVE
                    clearThinking()
                } else {
                    showMessage(getString(R.string.guide_not_sure), null, speak = !auto)
                }
                is GuideResult.Failed -> if (result.missingKey) {
                    showMessage(getString(R.string.guide_no_key), getString(R.string.guide_no_key_body), speak = !auto)
                } else {
                    showMessage(getString(R.string.guide_failed), getString(R.string.guide_failed_body), speak = !auto)
                }
            }
        }
    }

    private fun show(result: GuideResult.Show, snap: ScreenSnapshot) {
        var reply = result.reply
        // A "look" target is ringed to be read, not pressed: never let "bấm" through.
        if (result.look && PRESS.containsMatchIn(listOfNotNull(reply.say, reply.subtitle).joinToString(" "))) {
            reply = reply.copy(say = getString(R.string.guide_look), subtitle = null)
        }
        val say = reply.say?.trim().orEmpty()
        val title = reply.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: say
        val step = reply.step
        val total = reply.totalSteps
        val stepLabel = if (step != null && total != null && step in 1..total) getString(R.string.guide_step, step, total) else null
        val footer = listOfNotNull(stepLabel, getString(R.string.guide_unverified).takeIf { !result.verified })
            .joinToString(" · ").takeIf { it.isNotEmpty() }
        val body = say.takeIf { it.isNotEmpty() && it != title }

        val node = snap.nodes[result.targetIndex]
        moving = false
        shown = node
        shownRect = Rect(result.rect)
        shownPkg = snap.pkg
        shownOnWeb = snap.host != null
        val c = Card(title, body, footer, reply.caution, step, total, node.text ?: node.desc)
        card = c
        uiSeq++
        showCard(result.rect, c, body)
        // The bubble must not sit on the element the user is told to press.
        bubble?.avoid(result.rect)
        bubble?.state = BubbleWindow.State.ACTIVE

        // Spoken text: the full sentence, plus the warning before payment / submit / delete.
        val spoken = listOfNotNull(say.ifEmpty { title }, getString(R.string.guide_caution).takeIf { reply.caution }).joinToString(" ")
        if (spoken != lastSpoken) {
            speaker?.speak(spoken)
            lastSpoken = spoken
        }
        Log.i(TAG, "ring on node ${result.targetIndex} at ${result.rect.toShortString()} caution=${reply.caution} verified=${result.verified} look=${result.look}")
    }

    /** No ring: tell the user to move the screen. The target is re-placed after each scroll. */
    private fun showMove(result: GuideResult.Move) {
        val reply = result.reply
        val below = result.towards == TargetResolver.Towards.BELOW
        val direction = getString(if (below) R.string.guide_move_below else R.string.guide_move_above)
        val title: String
        val say: String
        if (result.nudge) {
            // Our own words: the model's sentence says "bấm", and there is nothing to press yet.
            title = direction
            say = getString(if (below) R.string.guide_nudge_below else R.string.guide_nudge_above)
        } else {
            val modelSay = reply.say?.trim().orEmpty()
            say = modelSay.takeIf { it.isNotEmpty() && !PRESS.containsMatchIn(it) } ?: direction
            title = reply.subtitle?.trim()?.takeIf { it.isNotEmpty() && !PRESS.containsMatchIn(it) } ?: direction
        }
        val step = reply.step
        val total = reply.totalSteps
        val stepLabel = if (step != null && total != null && step in 1..total) getString(R.string.guide_step, step, total) else null
        val footer = listOfNotNull(stepLabel, getString(R.string.guide_unverified).takeIf { !result.verified })
            .joinToString(" · ").takeIf { it.isNotEmpty() }

        moving = true
        shown = null
        shownRect = null
        card = null
        uiSeq++
        highlight?.showMove(below, title, say.takeIf { it != title }, footer)
        bubble?.restore()
        bubble?.state = BubbleWindow.State.ACTIVE
        if (say != lastSpoken) {
            speaker?.speak(say)
            lastSpoken = say
        }
        Log.i(TAG, "move towards=${result.towards} nudge=${result.nudge} anchor=${result.anchor?.toShortString()}")
    }

    /**
     * Tap feedback. A click event from the guided app while a ring is up: outside the ring the
     * user missed; inside it, the screen should change soon. Each sentence is said once per
     * step. No event (Chrome does not send one for every web element) means silence: no guessing.
     */
    private fun onViewClicked(event: AccessibilityEvent) {
        val ring = shownRect ?: return
        val target = shown ?: return
        if (event.packageName?.toString() != shownPkg) return
        val source = event.source ?: return
        val r = Rect()
        source.getBoundsInScreen(r)
        if (r.isEmpty) return
        val hit = r.contains(ring.centerX(), ring.centerY()) || ring.contains(r.centerX(), r.centerY())
        Log.i(TAG, "click at ${r.toShortString()} ring ${ring.toShortString()} hit=$hit")
        if (!hit) {
            // A tap elsewhere can still be progress (picking another video than the ringed
            // one, ticking a box): if the screen changed, look again instead of scolding.
            val hashAtTap = analysedHash
            stuckCheck?.cancel()
            stuckCheck = scope.launch {
                delay(MISS_CHECK_MS)
                if (!sessionActive || shownRect == null) return@launch
                val now = ScreenReader.read(this@GuideService) ?: return@launch
                if (now.hash != hashAtTap) {
                    Log.i(TAG, "tap outside the ring changed the screen: analysing again")
                    engine.noteUserActed()
                    shown = null
                    shownRect = null
                    analyse(auto = true, preRead = now)
                } else if (!missSaid) {
                    missSaid = true
                    feedback(getString(R.string.guide_miss))
                }
            }
            return
        }
        // A step done several times on one screen (Split twice): count, and wait for the rest.
        val morePresses = engine.notePress()
        if (morePresses) {
            Log.i(TAG, "press on a repeat step: waiting for the next press")
            return
        }
        // The user pressed the ring: the next screen change is progress, analyse it quickly.
        actedFast = true
        // Fields and toggles change in place; only buttons and links are expected to move on.
        // A press on a field puts the cursor in it: the next answer must be "type", not "tap it" again.
        // Any press on the ring: the next answer must move on, not repeat this step.
        engine.noteUserActed()
        if (stuckSaid || target.role !in MOVES_ON) return
        val hashAtTap = analysedHash
        stuckCheck?.cancel()
        stuckCheck = scope.launch {
            fun stillThere() = sessionActive && shown?.let { GuideEngine.sameElement(it, target) } == true && analysedHash == hashAtTap
            delay(if (shownOnWeb) STUCK_WEB_MS else ADVANCE_MS)
            if (!stillThere()) return@launch
            var now = ScreenReader.read(this@GuideService) ?: return@launch
            if (now.hash != hashAtTap) {
                // The press changed the screen, but the ringed element is still found (an app
                // that draws the next page over the old one, like CapCut's video picker):
                // the user has moved on, so look again.
                Log.i(TAG, "target pressed and the screen changed under the ring: analysing again")
                engine.noteUserActed()
                shown = null
                shownRect = null
                analyse(auto = true, preRead = now)
                return@launch
            }
            if (engine.nextStepIsHere(now)) {
                // The playbook's next step is on this same screen: move on without a screen change.
                Log.i(TAG, "target pressed, next step is on this screen: analysing again")
                engine.noteUserActed()
                analyse(auto = true, preRead = now)
                return@launch
            }
            if (!shownOnWeb) {
                delay(STUCK_MS - ADVANCE_MS)
                if (!stillThere()) return@launch
                now = ScreenReader.read(this@GuideService) ?: return@launch
            }
            if (now.hash != hashAtTap) return@launch
            stuckSaid = true
            feedback(getString(R.string.guide_stuck))
        }
    }

    /** Says one feedback sentence and shows it on the card, keeping the ring where it is. */
    private fun feedback(text: String) {
        val ring = shownRect ?: return
        val c = card ?: return
        showCard(ring, c, text)
        speaker?.speak(text)
        Log.i(TAG, "feedback: $text")
    }

    private fun showMessage(title: String, body: String?, speak: Boolean) {
        shown = null
        shownRect = null
        card = null
        moving = false
        val mySeq = ++uiSeq
        highlight?.showMessage(title, body)
        bubble?.state = if (sessionActive) BubbleWindow.State.ACTIVE else BubbleWindow.State.IDLE
        if (speak) speaker?.speak(listOfNotNull(title, body).joinToString(". "))
        // The instruction card is gone: the next instruction must be spoken even if it repeats
        // the one before the message.
        lastSpoken = null
        Log.i(TAG, "message: $title")
        messageTimeout?.cancel()
        messageTimeout = scope.launch {
            delay(MESSAGE_MS)
            // Only hide this message, never a newer card (a move card or a thinking card).
            if (uiSeq == mySeq && highlight?.isShowingTarget == false) highlight?.hide()
        }
    }

    private fun endSession() {
        sessionActive = false
        generation++
        actedFast = false
        analysis?.cancel()
        settle?.cancel()
        messageTimeout?.cancel()
        idleTimeout?.cancel()
        stuckCheck?.cancel()
        shown = null
        shownRect = null
        card = null
        moving = false
        missSaid = false
        stuckSaid = false
        bubble?.restore()
        analysedHash = null
        lastSpoken = null
        autoMisses = 0
        firstPendingEventAt = 0L
        speaker?.stop()
        highlight?.hide()
        bubble?.state = BubbleWindow.State.IDLE
        if (::engine.isInitialized) engine.resetSession()
    }

    /** A forgotten session must not keep reading the screen: it ends itself after a quiet spell. */
    private fun armIdleTimeout() {
        idleTimeout?.cancel()
        idleTimeout = scope.launch {
            delay(IDLE_SESSION_MS)
            Log.i(TAG, "session idle for ${IDLE_SESSION_MS / 1000}s: ended")
            endSession()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are only acted on inside a session; nothing is read outside one.
        if (!sessionActive || event == null) return
        if (event.packageName == packageName) return
        val pkg = event.packageName?.toString()
        // The keyboard and the system bars are transient: ScreenReader only reads application
        // windows, so their events can never change what we read. They must not restart the
        // debounce (a blinking clock or typing would postpone or trigger re-analysis).
        if (pkg == SYSTEM_UI || (pkg != null && pkg == imePkg)) return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            actionSeq++
            onViewClicked(event)
            return
        }

        // Debounce: wait for SETTLE_MS of quiet, but never longer than MAX_SETTLE_MS in total,
        // because pages with carousels or clocks never go quiet.
        val now = SystemClock.elapsedRealtime()
        if (firstPendingEventAt == 0L) firstPendingEventAt = now
        // While a ring is up the first pass is quick and only re-positions it (no model call),
        // so the ring keeps up with scrolling and with pages whose layout shifts.
        val tracking = shown != null
        // After a clear user action the new screen gets the short settle (still capped).
        val quiet = if (tracking) TRACK_MS else if (actedFast) ACT_SETTLE_MS else SETTLE_MS
        val cap = if (tracking) MAX_TRACK_MS else if (actedFast) MAX_ACT_SETTLE_MS else MAX_SETTLE_MS
        val wait = minOf(quiet, (firstPendingEventAt + cap - now).coerceAtLeast(0L))
        settle?.cancel()
        settle = scope.launch {
            delay(wait)
            firstPendingEventAt = 0L
            onScreenSettled(trackingPass = tracking)
        }
    }

    /**
     * Follow mode. The screen changed and has settled:
     *  - same content as last analysed: nothing to do (at most the ring follows a scroll);
     *  - the ringed element is still there: the user has not acted yet, keep the ring on it;
     *  - otherwise the user moved on: analyse the new screen, without another bubble tap.
     */
    private fun onScreenSettled(trackingPass: Boolean = false) {
        if (!sessionActive) return
        val snap = ScreenReader.read(this) ?: return

        if (moving) {
            // The user was asked to move the screen: see where the target is now, without the model.
            when (val again = engine.recheck(snap)) {
                is GuideResult.Show -> {
                    Log.i(TAG, "target in reach after the scroll: ring without a model call")
                    analysedHash = snap.hash
                    show(again, snap)
                    return
                }
                is GuideResult.Move -> {
                    analysedHash = snap.hash
                    showMove(again)
                    return
                }
                else -> {} // no remembered target (plain scroll step) or it is gone: analyse below
            }
        }

        val was = shown
        if (was != null) {
            val oldTop = was.bounds[1]
            val match = snap.nodes.withIndex()
                // Same element = same role and labels. The hint counts too: two empty text
                // fields differ only by it, and a field the user typed into no longer matches,
                // which is what moves the guide on to the next step.
                .filter { (_, n) -> GuideEngine.sameElement(n, was) }
                .minByOrNull { (_, n) -> abs(n.bounds[1] - oldTop) }
            if (match != null) {
                shown = match.value
                shownRect = Rect(snap.rects[match.index])
                highlight?.moveTarget(snap.rects[match.index])
                bubble?.avoid(snap.rects[match.index])
                return
            }
            if (trackingPass) {
                // The element is gone, but this was the quick pass: take the ring down (it
                // points at nothing now) and give the new screen the normal settle time
                // before spending a model call on it. With the ring down, later events use
                // the capped debounce, so a page that never goes quiet cannot postpone this.
                // The ringed element going away means the user acted: use the short settle.
                shown = null
                shownRect = null
                card = null
                stuckCheck?.cancel()
                actedFast = true
                // Not a blank screen while we wait: a quiet "looking" card replaces the old step.
                showThinking()
                firstPendingEventAt = SystemClock.elapsedRealtime()
                settle?.cancel()
                settle = scope.launch {
                    delay(ACT_SETTLE_MS)
                    firstPendingEventAt = 0L
                    onScreenSettled()
                }
                return
            }
        }
        if (snap.hash == analysedHash) return

        // Rate limit: a screen that keeps changing must not turn into a stream of model calls.
        val sinceLast = SystemClock.elapsedRealtime() - lastAnalysisAt
        // Back off when automatic calls keep coming back unsure (a carousel page the model
        // cannot help with): 1.5 s, 3 s, 6 s ... 24 s. A ring or a bubble tap resets it.
        // The first analysis after a clear user action is not rate limited (it is not a storm:
        // it needs a press or a ring that went away); the back-off still applies to the rest.
        val minInterval = MIN_AUTO_INTERVAL_MS shl autoMisses.coerceAtMost(4)
        if (!actedFast && sinceLast < minInterval) {
            settle?.cancel()
            settle = scope.launch {
                delay(minInterval - sinceLast)
                onScreenSettled()
            }
            return
        }
        if (analysis?.isActive == true && !actedFast) {
            // A model call is in flight (it takes a second or two). Let it finish instead of
            // cancelling and restarting on every twitch of a page with a carousel; its answer
            // is placed on the fresh screen when it arrives.
            settle?.cancel()
            settle = scope.launch {
                delay(IN_FLIGHT_POLL_MS)
                onScreenSettled()
            }
            return
        }
        Log.i(TAG, "screen moved on: analysing again (follow mode)")
        shown = null
        shownRect = null
        stuckCheck?.cancel()
        // A "move the screen" card stays up while the next answer is fetched: no flicker.
        // Otherwise a quiet "looking" card shows the guide is working. If a call was in flight
        // for an older screen (the user acted meanwhile), analyse() cancels it and its
        // generation makes any late answer harmless.
        val keepMoveCard = moving
        card = null
        analyse(auto = true, preRead = snap, thinkingCard = !keepMoveCard)
    }

    private fun logSnapshot(snap: ScreenSnapshot) {
        Log.i(TAG, "screen package=${snap.pkg} host=${snap.host} nodes=${snap.nodes.size} hash=${snap.hash}")
        if (BuildConfig.DEBUG) {
            // One node per line: logcat truncates long lines.
            snap.nodes.forEach { Log.d(TAG, "node " + NODE_JSON.encodeToString(UiNode.serializer(), it)) }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        scope.cancel()
        super.onDestroy()
    }

    private fun teardown() {
        endSession()
        highlight?.detach()
        highlight = null
        speaker?.shutdown()
        speaker = null
        bubble?.hide()
        bubble = null
        if (instance === this) {
            instance = null
            _running.value = false
        }
    }

    companion object {
        const val TAG = "ChiDay"
        private const val SYSTEM_UI = "com.android.systemui"
        private const val SETTLE_MS = 800L
        private const val MAX_SETTLE_MS = 2500L
        private const val TRACK_MS = 200L
        /** Settle after the user clearly acted (pressed the ring / the ringed element went away). */
        private const val ACT_SETTLE_MS = 350L
        private const val MAX_ACT_SETTLE_MS = 1200L
        private const val THINKING_MAX_MS = 15000L
        private const val MAX_TRACK_MS = 600L
        private const val MIN_AUTO_INTERVAL_MS = 1500L
        private const val MESSAGE_MS = 7000L
        private const val IDLE_SESSION_MS = 5 * 60 * 1000L
        private const val STUCK_MS = 4000L
        private const val ADVANCE_MS = 900L
        private const val MISS_CHECK_MS = 600L
        private const val IN_FLIGHT_POLL_MS = 500L
        private const val STUCK_WEB_MS = 8000L
        private val MOVES_ON = setOf("button", "link", "item")
        private val PRESS = Regex("(?i)(?<![\\p{L}])(bấm|nhấn|chạm|ấn)(?![\\p{L}])")
        private val NODE_JSON = Json { explicitNulls = false }

        @Volatile
        var instance: GuideService? = null
            private set

        private val _running = MutableStateFlow(false)

        /**
         * True while the system has the service bound in this process. A flow, not a one-off
         * read: after a kill-and-reopen the activity can be on screen before the system rebinds.
         */
        val running: StateFlow<Boolean> = _running

        private const val KEY_BUBBLE_ON = "enabled"
        private val _bubbleOn = MutableStateFlow(true)

        /** Whether the floating bubble is switched on (saved; survives kill-and-reopen). */
        val bubbleOn: StateFlow<Boolean> = _bubbleOn

        private fun bubblePrefs(context: Context) = context.getSharedPreferences("bubble", Context.MODE_PRIVATE)

        /** Reads the saved switch into [bubbleOn]; call when the activity comes to the front. */
        fun refreshBubbleSwitch(context: Context) {
            _bubbleOn.value = bubblePrefs(context).getBoolean(KEY_BUBBLE_ON, true)
        }

        /**
         * The user's switch for the floating bubble. Off removes the bubble from every screen and
         * ends any session, without touching the accessibility permission; on brings it back.
         */
        fun setBubbleOn(context: Context, on: Boolean) {
            bubblePrefs(context).edit().putBoolean(KEY_BUBBLE_ON, on).apply()
            _bubbleOn.value = on
            instance?.applyBubbleSwitch()
            if (on) instance?.warmUpModel()
        }

        /** True when the user has switched the service on in Settings. */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, GuideService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
