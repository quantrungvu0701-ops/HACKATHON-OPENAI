package vn.chiday.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
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

    /** Hash of the last screen sent to the model: an unchanged screen is never analysed twice. */
    private var analysedHash: Int? = null
    private var lastAnalysisAt = 0L
    private var firstPendingEventAt = 0L

    /** What was last spoken, so a re-analysis that gives the same instruction stays quiet. */
    private var lastSpoken: String? = null

    /** Automatic analyses in a row that produced no ring; each one doubles the wait before the next. */
    private var autoMisses = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        settings = SettingsStore(this)
        engine = GuideEngine(settings, PlaybookStore(this))
        highlight = HighlightWindow(this)
        speaker = Speaker(this)
        bubble = BubbleWindow(this, ::onBubbleTap).also { it.show() }
        _running.value = true
        Log.i(TAG, "service connected, bubble shown")
    }

    private fun onBubbleTap() {
        if (sessionActive) {
            Log.i(TAG, "bubble tap: session ended")
            endSession()
        } else {
            Log.i(TAG, "bubble tap: session started")
            sessionActive = true
            engine.resetSession()
            analyse(auto = false)
        }
    }

    /** [auto] is true when follow mode triggered this, false for the user's own tap. */
    private fun analyse(auto: Boolean, preRead: ScreenSnapshot? = null) {
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
        analysedHash = snap.hash
        lastAnalysisAt = SystemClock.elapsedRealtime()
        armIdleTimeout()
        bubble?.state = BubbleWindow.State.THINKING
        analysis = scope.launch {
            engine.goal = settings.currentGoal()
            val started = SystemClock.elapsedRealtime()
            val result = engine.decide(snap)
            Log.i(TAG, "decision (auto=$auto) in ${SystemClock.elapsedRealtime() - started} ms: $result")
            autoMisses = if (result is GuideResult.Show || !auto) 0 else autoMisses + 1
            when (result) {
                is GuideResult.Show -> show(result, snap)
                // In follow mode an unsure answer is shown quietly: the user did not ask just now.
                is GuideResult.NotSure -> if (auto && autoMisses == 1) {
                    // First automatic miss is usually a page that is still loading: stay silent
                    // and look again when it settles, instead of flashing a message.
                    Log.i(TAG, "auto analysis unsure once: waiting for the screen to settle")
                    bubble?.state = BubbleWindow.State.ACTIVE
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
        val reply = result.reply
        val say = reply.say?.trim().orEmpty()
        val title = reply.subtitle?.trim()?.takeIf { it.isNotEmpty() } ?: say
        val step = reply.step
        val total = reply.totalSteps
        val stepLabel = if (step != null && total != null && step in 1..total) getString(R.string.guide_step, step, total) else null
        val footer = listOfNotNull(stepLabel, getString(R.string.guide_unverified).takeIf { !result.verified })
            .joinToString(" · ").takeIf { it.isNotEmpty() }
        val body = listOfNotNull(
            say.takeIf { it.isNotEmpty() && it != title },
            getString(R.string.guide_caution).takeIf { reply.caution },
        ).joinToString("\n").takeIf { it.isNotEmpty() }

        shown = snap.nodes[result.targetIndex]
        highlight?.showTarget(result.rect, title, body, footer, reply.caution)
        bubble?.bringToFront()
        bubble?.state = BubbleWindow.State.ACTIVE

        // Spoken text: the full sentence, plus the warning before payment / submit / delete.
        val spoken = listOfNotNull(say.ifEmpty { title }, getString(R.string.guide_caution).takeIf { reply.caution }).joinToString(" ")
        if (spoken != lastSpoken) {
            speaker?.speak(spoken)
            lastSpoken = spoken
        }
        Log.i(TAG, "ring on node ${result.targetIndex} at ${result.rect.toShortString()} caution=${reply.caution} verified=${result.verified}")
    }

    private fun showMessage(title: String, body: String?, speak: Boolean) {
        shown = null
        highlight?.showMessage(title, body)
        bubble?.bringToFront()
        bubble?.state = if (sessionActive) BubbleWindow.State.ACTIVE else BubbleWindow.State.IDLE
        if (speak) {
            speaker?.speak(listOfNotNull(title, body).joinToString(". "))
            lastSpoken = null
        }
        Log.i(TAG, "message: $title")
        messageTimeout?.cancel()
        messageTimeout = scope.launch {
            delay(MESSAGE_MS)
            if (highlight?.isShowingTarget == false) highlight?.hide()
        }
    }

    private fun endSession() {
        sessionActive = false
        analysis?.cancel()
        settle?.cancel()
        messageTimeout?.cancel()
        idleTimeout?.cancel()
        shown = null
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

        // Debounce: wait for SETTLE_MS of quiet, but never longer than MAX_SETTLE_MS in total,
        // because pages with carousels or clocks never go quiet.
        val now = SystemClock.elapsedRealtime()
        if (firstPendingEventAt == 0L) firstPendingEventAt = now
        // While a ring is up the first pass is quick and only re-positions it (no model call),
        // so the ring keeps up with scrolling and with pages whose layout shifts.
        val tracking = shown != null
        val quiet = if (tracking) TRACK_MS else SETTLE_MS
        val cap = if (tracking) MAX_TRACK_MS else MAX_SETTLE_MS
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

        val was = shown
        if (was != null) {
            val oldTop = was.bounds[1]
            val match = snap.nodes.withIndex()
                // Same element = same role and labels. The hint counts too: two empty text
                // fields differ only by it, and a field the user typed into no longer matches,
                // which is what moves the guide on to the next step.
                .filter { (_, n) -> n.role == was.role && n.text == was.text && n.desc == was.desc && n.id == was.id && n.hint == was.hint }
                .minByOrNull { (_, n) -> abs(n.bounds[1] - oldTop) }
            if (match != null) {
                shown = match.value
                highlight?.moveTarget(snap.rects[match.index])
                return
            }
            if (trackingPass) {
                // The element is gone, but this was the quick pass: take the ring down (it
                // points at nothing now) and give the new screen the normal settle time
                // before spending a model call on it. With the ring down, later events use
                // the capped debounce, so a page that never goes quiet cannot postpone this.
                shown = null
                highlight?.hide()
                firstPendingEventAt = SystemClock.elapsedRealtime()
                settle?.cancel()
                settle = scope.launch {
                    delay(SETTLE_MS)
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
        val minInterval = MIN_AUTO_INTERVAL_MS shl autoMisses.coerceAtMost(4)
        if (sinceLast < minInterval) {
            settle?.cancel()
            settle = scope.launch {
                delay(minInterval - sinceLast)
                onScreenSettled()
            }
            return
        }
        Log.i(TAG, "screen moved on: analysing again (follow mode)")
        shown = null
        highlight?.hide()
        analyse(auto = true, preRead = snap)
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
        private const val SETTLE_MS = 800L
        private const val MAX_SETTLE_MS = 2500L
        private const val TRACK_MS = 250L
        private const val MAX_TRACK_MS = 600L
        private const val MIN_AUTO_INTERVAL_MS = 1500L
        private const val MESSAGE_MS = 7000L
        private const val IDLE_SESSION_MS = 5 * 60 * 1000L
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
