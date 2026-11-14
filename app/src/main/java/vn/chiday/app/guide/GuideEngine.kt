package vn.chiday.app.guide

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import vn.chiday.app.llm.Llm
import vn.chiday.app.llm.LlmException
import vn.chiday.app.playbook.Playbook
import vn.chiday.app.playbook.PlaybookStore
import vn.chiday.app.screen.ScreenSnapshot
import vn.chiday.app.screen.UiNode
import vn.chiday.app.settings.SettingsStore

/** The model's answer. Schema from MECHANISM.md §2.3. Everything is optional: output is untrusted. */
@Serializable
data class GuideReply(
    val service: String? = null,
    val step: Int? = null,
    @SerialName("total_steps") val totalSteps: Int? = null,
    val target: Int? = null,
    val action: String? = null,
    val say: String? = null,
    val subtitle: String? = null,
    val caution: Boolean = false,
    val confidence: Double? = null,
)

sealed interface GuideResult {
    /**
     * A validated instruction: [rect] comes from the accessibility tree, never from the model.
     * [verified] is true when a verified playbook backed this step; false is generic mode.
     */
    data class Show(
        val reply: GuideReply,
        val targetIndex: Int,
        val rect: Rect,
        val verified: Boolean = false,
        /** Ring it, but the user only looks or reads here: the overlay must not say "bấm". */
        val look: Boolean = false,
    ) : GuideResult

    /**
     * Nothing is ringed: the user has to move the screen first. [nudge] false means what they
     * need is off screen (follow [reply]'s instruction); true means the target is on screen at
     * [anchor] but cut off or hidden under a floating button, so a small scroll is enough.
     */
    data class Move(
        val reply: GuideReply,
        val towards: TargetResolver.Towards,
        val nudge: Boolean,
        val anchor: Rect? = null,
        val verified: Boolean = false,
    ) : GuideResult

    /** The model could not tell, pointed at a node that does not exist, or was not confident. */
    data class NotSure(val why: String) : GuideResult

    /** No key, no network, HTTP error. [message] is for the log; the user sees a fixed sentence. */
    data class Failed(val message: String, val missingKey: Boolean = false) : GuideResult
}

/**
 * Turns one screen read into one validated instruction. Holds the session memory the prompt
 * needs (goal, last step, playbook in use). Never touches the screen: guide-only.
 */
class GuideEngine(private val settings: SettingsStore, private val playbooks: PlaybookStore) {

    /** What the user wants to do, in their words. Null means "work it out from the screen". */
    var goal: String? = null

    /** Subtitle of the previous instruction in this session, for continuity between screens. */
    private var lastStep: String? = null

    /** Once a playbook is picked it is kept for the session while it still fits the app. */
    private var sessionPlaybook: Playbook? = null

    /** Number of the playbook step shown last, to tell apart steps that share a screen. */
    private var lastStepN: Int? = null

    /** How many times in a row [lastStepN] has been shown (for steps with `repeat`). */
    private var lastStepShown = 0

    /** The last instruction with a target, kept so [recheck] can re-place it without the model. */
    private class Pending(val reply: GuideReply, val node: UiNode, val scrollStep: Boolean, val towards: TargetResolver.Towards, val verified: Boolean)

    private var pending: Pending? = null

    /** Set when the user pressed the ringed target and the screen stayed: told to the model once. */
    private var userActed = false

    /** Without a playbook: the step number shown last, so a finished step is never shown again. */
    private var freeStepN: Int? = null

    /**
     * True when the playbook's next step happens on this same screen (CapCut: split, split
     * again, delete all sit on the editor). After a press on the ring the guide then moves on
     * in place instead of waiting for a screen change that will not come.
     */
    fun nextStepIsHere(snapshot: ScreenSnapshot): Boolean {
        val pb = sessionPlaybook ?: return false
        val n = lastStepN ?: return false
        return pb.appliesTo(snapshot) && pb.likelyStep(snapshot, n, pressesOnStep)?.n == n + 1
    }

    /** Presses on the ring since the current step was shown (a `repeat` step needs several). */
    private var pressesOnStep = 0

    /** The user pressed the ringed target. Returns true when the step still wants more presses. */
    fun notePress(): Boolean {
        pressesOnStep++
        val step = sessionPlaybook?.steps?.firstOrNull { it.n == lastStepN } ?: return false
        return pressesOnStep < step.repeat
    }

    fun noteUserActed() {
        userActed = true
    }

    fun resetSession() {
        lastStep = null
        freeStepN = null
        lastStepN = null
        lastStepShown = 0
        pressesOnStep = 0
        sessionPlaybook = null
        pending = null
        userActed = false
    }

    /**
     * Runs the geometry check again for the last target on a new read of the screen, with no
     * model call. Used after the user scrolled in answer to a "move the screen" instruction:
     * the target may now be fully visible. Null when there is no such target or it is gone.
     */
    fun recheck(snapshot: ScreenSnapshot): GuideResult? {
        val p = pending ?: return null
        val index = snapshot.nodes.indexOfFirst { sameElement(it, p.node) }
        if (index < 0) return null
        return place(p.reply.copy(target = index), snapshot, p.scrollStep, p.towards, p.verified)
    }

    suspend fun decide(snapshot: ScreenSnapshot): GuideResult {
        val s = settings.current()
        if (!s.isComplete) return GuideResult.Failed("no API key configured", missingKey = true)
        if (snapshot.nodes.isEmpty()) return GuideResult.NotSure("empty node list")

        // The session playbook and its progress survive screens it does not apply to (keyboard,
        // permission dialog, system UI, a page whose host reads null for a moment): only a
        // different playbook that positively applies here replaces it. Until resetSession().
        val session = sessionPlaybook
        val playbook: Playbook? = if (session != null && session.appliesTo(snapshot)) {
            session
        } else {
            val picked = playbooks.select(snapshot, goal)
            if (picked != null && picked.id != session?.id) {
                sessionPlaybook = picked
                lastStepN = null
                lastStepShown = 0
                pressesOnStep = 0
            }
            picked
        }
        // A `repeat` step is left once its target has been pressed that many times.
        val likely = playbook?.likelyStep(snapshot, lastStepN, pressesOnStep)
        Log.i(TAG, "playbook=${playbook?.id ?: "none"} likelyStep=${likely?.n ?: "-"}")

        val raw = try {
            // A guide answer normally takes 1-4 s. A call that stalls must not leave the user
            // waiting in silence: give up after CALL_TIMEOUT_MS and try once more.
            val message = userMessage(snapshot, playbook, likely)
            val client = Llm.clientFor(s)
            kotlinx.coroutines.withTimeoutOrNull(CALL_TIMEOUT_MS) { client.complete(SYSTEM_PROMPT, message) }
                ?: run {
                    Log.w(TAG, "model call took over ${CALL_TIMEOUT_MS / 1000}s: trying once more on a fresh connection")
                    Llm.http.connectionPool.evictAll()
                    kotlinx.coroutines.withTimeoutOrNull(CALL_TIMEOUT_MS) { client.complete(SYSTEM_PROMPT, message) }
                }
                ?: return GuideResult.Failed("model call timed out twice")
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return GuideResult.Failed(e.message ?: "model call failed")
        } catch (e: Exception) {
            return GuideResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        // The model has seen USER_ACTION: only now is it consumed (a failed or cancelled call keeps it).
        val acted = userActed
        userActed = false
        var reply = parse(raw) ?: return GuideResult.NotSure("unparseable reply: ${raw.take(120)}")
        val floor = lastStepN
        val said = reply.step
        if (playbook != null && floor != null && said != null && said < floor &&
            !playbook.clearlyBackAt(snapshot, said, floor)
        ) {
            // Progress is monotonic: a finished step comes back only on strong evidence.
            val fixed = likely?.n?.takeIf { it >= floor } ?: floor
            Log.i(TAG, "model went back to step $said after $floor: kept step $fixed")
            reply = reply.copy(step = fixed)
        }
        if (playbook == null) {
            // No playbook: the model numbers the steps itself and tends to say "step 1" again.
            // Keep the count moving forward: one more each time the user has done the last step.
            val before = freeStepN
            val n = when {
                before == null -> (reply.step ?: 1).coerceAtLeast(1)
                acted -> maxOf(reply.step ?: 0, before + 1)
                else -> maxOf(reply.step ?: 0, before)
            }
            reply = reply.copy(step = n, totalSteps = reply.totalSteps?.let { maxOf(it, n) })
        }
        if (playbook != null) {
            // The playbook, not the model, is the authority on length and on which steps are risky.
            val step = playbook.steps.firstOrNull { it.n == reply.step }
            reply = reply.copy(
                totalSteps = playbook.steps.size,
                service = reply.service ?: playbook.service,
                caution = reply.caution || step?.caution == true,
            )
        }
        val step = playbook?.steps?.firstOrNull { it.n == reply.step }
        val scrollStep = reply.action?.trim()?.lowercase() == "scroll" || TargetResolver.isScrollStep(step?.action)
        val towards = TargetResolver.scrollTowards(step?.action ?: reply.subtitle ?: reply.say)
        val result = place(reply, snapshot, scrollStep, towards, verified = playbook?.verified != null)
        if (result is GuideResult.Show || result is GuideResult.Move) {
            lastStep = reply.subtitle ?: reply.say
            if (playbook == null) freeStepN = reply.step
            if (playbook != null) {
                val n = reply.step ?: lastStepN
                lastStepShown = if (n == lastStepN) lastStepShown + 1 else 1
                if (n != lastStepN) pressesOnStep = 0
                lastStepN = n
            }
        }
        return result
    }

    /** Geometry check on a validated reply, and memory of the target for [recheck]. */
    private fun place(reply: GuideReply, snapshot: ScreenSnapshot, scrollStep: Boolean, towards: TargetResolver.Towards, verified: Boolean): GuideResult {
        val result = validate(reply, snapshot, scrollStep, towards, verified)
        val index = when (result) {
            is GuideResult.Show -> result.targetIndex
            is GuideResult.Move -> result.reply.target?.takeIf { result.nudge && it in snapshot.nodes.indices }
            else -> null
        }
        pending = index?.let { Pending(reply, snapshot.nodes[it], scrollStep, towards, verified) }
        return result
    }

    private fun userMessage(snapshot: ScreenSnapshot, playbook: Playbook?, likely: Playbook.Step?): String = buildString {
        // A goal picked for another service (the user chose "birth certificate" on Home, then
        // opened the post office site) must not fight the playbook of the app on screen.
        val typed = goal?.trim()?.takeIf { it.isNotEmpty() }
        val foreign = typed != null && playbook != null && !typed.equals(playbook.goal, ignoreCase = true) &&
            playbooks.all().any { it.id != playbook.id && it.goal.equals(typed, ignoreCase = true) }
        val g = if (foreign) playbook?.goal else typed ?: playbook?.goal
        append("GOAL: ").append(g ?: "not stated; choose the most likely next step on this screen").append('\n')
        append("LAST_STEP: ").append(lastStep ?: "none").append('\n')
        if (userActed) {
            append("USER_ACTION: the user has just done LAST_STEP (pressed its target). Do NOT give that instruction again; continue with what comes after it\n")
        }
        if (playbook == null) {
            append("PLAYBOOK: none\n")
            // Not in the service yet (home screen, another app, another site): say where the goal
            // is done, so the guide leads there through a real browser or the app itself.
            val wanted = g?.let { goalText -> PlaybookStore.matchGoal(goalText, playbooks.all()) }
            val site = wanted?.match?.hosts?.firstOrNull()
            when {
                site != null -> append("DESTINATION: the website ").append(site)
                    .append(" in a web browser. The user is not there yet: lead them to it (see the browser rule).\n")
                wanted != null -> append("DESTINATION: the app \"").append(wanted.service)
                    .append("\". The user is not in it yet: point at that app's icon to open it.\n")
                else -> append("DESTINATION: unknown. If the goal needs looking something up, lead the user to a web browser (see the browser rule).\n")
            }
        } else {
            val n = lastStepN
            val current = if (n == null) null else {
                val repeat = playbook.steps.firstOrNull { it.n == n }?.repeat ?: 1
                if (pressesOnStep >= repeat) n + 1 else n
            }
            append("PLAYBOOK: ").append(COMPACT.encodeToString(CompactPlaybook.serializer(), compact(playbook, current, likely?.n))).append('\n')
            if (current != null) {
                val done = current - 1
                if (done >= 1) append("PROGRESS: steps 1..").append(done).append(" are done; ") else append("PROGRESS: ")
                append("current step is at least ").append(current).append('\n')
            }
            append("LIKELY_STEP: ").append(likely?.n?.toString() ?: "unknown").append('\n')
        }
        append("SCREEN: ").append(snapshot.toJson())
    }

    /** The playbook without match rules and signals; with known progress only the steps around it. */
    private fun compact(playbook: Playbook, current: Int?, likely: Int?): CompactPlaybook {
        val steps = playbook.steps.filter { s ->
            current == null || s.n in (current - 1)..(current + 2) || s.n == likely
        }
        return CompactPlaybook(
            id = playbook.id,
            service = playbook.service,
            goal = playbook.goal,
            total = playbook.steps.size,
            steps = steps.map { CompactStep(it.n, it.action, it.targetHint, it.pitfall, it.caution, it.repeat) },
        )
    }

    @Serializable
    private data class CompactStep(
        val n: Int,
        @SerialName("do") val action: String,
        @SerialName("target_hint") val targetHint: String? = null,
        val pitfall: String? = null,
        val caution: Boolean = false,
        val repeat: Int = 1,
    )

    @Serializable
    private data class CompactPlaybook(
        val id: String,
        val service: String,
        val goal: String,
        val total: Int,
        val steps: List<CompactStep>,
    )

    companion object {
        private const val TAG = "ChiDay"
        const val MIN_CONFIDENCE = 0.5
        const val CALL_TIMEOUT_MS = 7_000L

        // Prompt contract from AGENTS.md. English instructions, Vietnamese output.
        const val SYSTEM_PROMPT =
            "You guide a Vietnamese user who is not confident with phones through a " +
                "public-service app. You see a list of on-screen elements with indices. Choose the " +
                "ONE element the user should act on next. Address the user as \"bác\". Use at most two " +
                "short sentences, everyday words, no technical terms, describe where the button is on " +
                "the screen. If a playbook is given, follow it. If the next action is payment, " +
                "submission or deletion set caution=true. If you cannot tell, set target=null. " +
                "Write \"say\" and \"subtitle\" in Vietnamese. \"target\" is the \"i\" of the chosen element. " +
                "With a playbook: work out which step this screen is (LIKELY_STEP is a hint from " +
                "text matching), set \"step\" to that step's \"n\", point at the element that step " +
                "describes, and mention the step's pitfall if it has one. If the screen fits no step " +
                "of the playbook, guide from the goal alone. The playbook may list only the steps " +
                "around the current one; \"total\" is the full count. PROGRESS tells which steps " +
                "are already done: never go back to a finished step unless the screen clearly " +
                "shows the user went back to the start; text from earlier steps (headers, search " +
                "boxes, menus) often stays visible on later screens and is no reason to go back. " +
                "If what the user needs is not on screen yet, or the step is only to scroll or read, " +
                "set action=\"scroll\", tell the user which way to move the screen, do not say " +
                "\"bấm\", and set target to the element to read if it is visible, else null. " +
                "\"focused\": true on an input field means the user has ALREADY tapped it and the cursor is " +
                "in it; \"keyboard\": true means the keyboard is open. Then never tell the user to tap " +
                "that field again: set action=\"type\", target that field, and tell them exactly what " +
                "words to type on the keyboard and which key to press after (the magnifier or Enter key). " +
                "If that field is already \"filled\", point at the search/submit button or the result to press instead. " +
                "Browser rule: when the user is on the phone's home screen, app list or the phone's own " +
                "search (Finder) and the goal needs a website or looking something up, NEVER send them " +
                "to the phone's search box: its suggestions are useless. Point at the icon of a real " +
                "web browser instead (Chrome first, else \"Internet\"/Samsung Internet). If no browser " +
                "icon is visible but a Google search bar is (labelled \"Google Search\" or with ids " +
                "containing \"googleapp_search_widget\"), point at that bar: it is the real Google, not " +
                "the phone's own search, and typing the DESTINATION address there opens the website. " +
                "Only if neither is visible: on the home screen set action=\"scroll\", target=null and " +
                "tell them to swipe up from the middle of the screen to open the list of all apps; in " +
                "the app list point at Chrome if visible, else tell them to swipe sideways to see more apps. " +
                "A search bar or address bar that is not \"focused\" yet must be tapped first: say only to tap it (action=\"tap\"); say what to type only once it is focused. " +
                "Never ask the user to report back or to tell you anything. Once inside the browser but not on " +
                "the DESTINATION website: point at the address bar at the top (or the search box in " +
                "the middle of a new tab), and when it is focused tell them to type the DESTINATION " +
                "address exactly (for example vnpost.vn) and press the Go/Enter key. " +
                "For privacy, input fields never show what the user typed: \"filled\": true means " +
                "the field already has something in it, false means it is empty. Masked values " +
                "(••••••, [email]) are hidden personal data: never ask the user to read them out. " +
                "Keep it short: \"say\" at most 18 words, \"subtitle\" at most 8 words. " +
                "Reply with JSON only, no markdown, no extra keys: {service, step, total_steps, " +
                "target, action, say, subtitle, caution, confidence}"

        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }
        private val COMPACT = Json { explicitNulls = false; encodeDefaults = false }

        /** Lenient parse: tolerates code fences and prose around the JSON object. */
        fun parse(raw: String): GuideReply? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { lenient.decodeFromString(GuideReply.serializer(), raw.substring(start, end + 1)) }
                .onFailure { Log.w(TAG, "guide reply did not parse: ${it.message?.take(160)}") }
                .getOrNull()
        }

        /** The guardrails from MECHANISM.md §2.3. A target is shown only if its index really exists. */
        fun validate(
            reply: GuideReply,
            snapshot: ScreenSnapshot,
            scrollStep: Boolean = false,
            towards: TargetResolver.Towards = TargetResolver.Towards.BELOW,
            verified: Boolean = false,
        ): GuideResult {
            val target = reply.target
            if (target == null && !scrollStep) return GuideResult.NotSure("target=null")
            if (target != null && target !in snapshot.nodes.indices) {
                return GuideResult.NotSure("unknown index $target of ${snapshot.nodes.size}")
            }
            val confidence = reply.confidence
            if (confidence != null && confidence < MIN_CONFIDENCE) return GuideResult.NotSure("confidence $confidence")
            if (reply.say.isNullOrBlank() && reply.subtitle.isNullOrBlank()) return GuideResult.NotSure("no instruction text")

            // A field the cursor is already in is in use right now: ring it as it is. The
            // cover check misfires here (launcher search draws its input row over stale items).
            if (target != null && snapshot.nodes[target].focused == true) {
                return GuideResult.Show(reply, target, Rect(snapshot.rects[target]), verified)
            }

            // The model chose; geometry decides whether a finger can land there (TargetResolver).
            val boxes = snapshot.nodes.mapIndexed { i, n ->
                val r = snapshot.fullRects[i]
                val v = snapshot.rects[i]
                TargetResolver.Node(listOf(r.left, r.top, r.right, r.bottom), n.clickable == true, listOf(v.left, v.top, v.right, v.bottom))
            }
            val s = snapshot.screen
            return when (val o = TargetResolver.resolve(boxes, listOf(s.left, s.top, s.right, s.bottom), target, scrollStep, towards)) {
                null -> GuideResult.NotSure("target=null")
                is TargetResolver.Outcome.Tap -> GuideResult.Show(reply, o.index, Rect(snapshot.rects[o.index]), verified)
                is TargetResolver.Outcome.Look -> GuideResult.Show(reply, o.index, Rect(snapshot.rects[o.index]), verified, look = true)
                is TargetResolver.Outcome.Scroll -> GuideResult.Move(reply, o.towards, nudge = false, verified = verified)
                is TargetResolver.Outcome.Nudge ->
                    GuideResult.Move(reply.copy(target = o.index), o.towards, nudge = true, anchor = Rect(snapshot.rects[o.index]), verified = verified)
            }
        }

        /** Same element across two reads of the screen: same role and labels. */
        fun sameElement(a: UiNode, b: UiNode) =
            // "filled" counts: typed text is never read (privacy), so a field the user has just
            // typed into differs from the empty one only by this flag, which moves the guide on.
            a.role == b.role && a.text == b.text && a.desc == b.desc && a.id == b.id && a.hint == b.hint && a.filled == b.filled && a.focused == b.focused
    }
}
