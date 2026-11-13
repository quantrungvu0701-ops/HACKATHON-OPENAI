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
    data class Show(val reply: GuideReply, val targetIndex: Int, val rect: Rect, val verified: Boolean = false) : GuideResult

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

    fun resetSession() {
        lastStep = null
        lastStepN = null
        sessionPlaybook = null
    }

    suspend fun decide(snapshot: ScreenSnapshot): GuideResult {
        val s = settings.current()
        if (!s.isComplete) return GuideResult.Failed("no API key configured", missingKey = true)
        if (snapshot.nodes.isEmpty()) return GuideResult.NotSure("empty node list")

        val playbook = sessionPlaybook?.takeIf { it.appliesTo(snapshot) } ?: playbooks.select(snapshot, goal)
        if (playbook?.id != sessionPlaybook?.id) lastStepN = null
        sessionPlaybook = playbook
        val likely = playbook?.likelyStep(snapshot, lastStepN)
        Log.i(TAG, "playbook=${playbook?.id ?: "none"} likelyStep=${likely?.n ?: "-"}")

        val raw = try {
            Llm.clientFor(s).complete(SYSTEM_PROMPT, userMessage(snapshot, playbook, likely))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return GuideResult.Failed(e.message ?: "model call failed")
        } catch (e: Exception) {
            return GuideResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        var reply = parse(raw) ?: return GuideResult.NotSure("unparseable reply: ${raw.take(120)}")
        if (playbook != null) {
            // The playbook, not the model, is the authority on length and on which steps are risky.
            val step = playbook.steps.firstOrNull { it.n == reply.step }
            reply = reply.copy(
                totalSteps = playbook.steps.size,
                service = reply.service ?: playbook.service,
                caution = reply.caution || step?.caution == true,
            )
        }
        return when (val result = validate(reply, snapshot)) {
            is GuideResult.Show -> {
                lastStep = reply.subtitle ?: reply.say
                if (playbook != null) lastStepN = reply.step ?: lastStepN
                result.copy(verified = playbook?.verified != null)
            }
            else -> result
        }
    }

    private fun userMessage(snapshot: ScreenSnapshot, playbook: Playbook?, likely: Playbook.Step?): String = buildString {
        val g = goal?.takeIf { it.isNotBlank() } ?: playbook?.goal
        append("GOAL: ").append(g ?: "not stated; choose the most likely next step on this screen").append('\n')
        append("LAST_STEP: ").append(lastStep ?: "none").append('\n')
        if (playbook == null) {
            append("PLAYBOOK: none\n")
        } else {
            append("PLAYBOOK: ").append(COMPACT.encodeToString(playbook)).append('\n')
            append("LIKELY_STEP: ").append(likely?.n?.toString() ?: "unknown").append('\n')
        }
        append("SCREEN: ").append(snapshot.toJson())
    }

    companion object {
        private const val TAG = "ChiDay"
        const val MIN_CONFIDENCE = 0.5

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
                "of the playbook, guide from the goal alone. " +
                "Reply with JSON only: {service, step, total_steps, target, action, say, subtitle, " +
                "caution, confidence}"

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
        fun validate(reply: GuideReply, snapshot: ScreenSnapshot): GuideResult {
            val target = reply.target ?: return GuideResult.NotSure("target=null")
            if (target !in snapshot.nodes.indices) return GuideResult.NotSure("unknown index $target of ${snapshot.nodes.size}")
            val confidence = reply.confidence
            if (confidence != null && confidence < MIN_CONFIDENCE) return GuideResult.NotSure("confidence $confidence")
            if (reply.say.isNullOrBlank() && reply.subtitle.isNullOrBlank()) return GuideResult.NotSure("no instruction text")
            return GuideResult.Show(reply, target, Rect(snapshot.rects[target]))
        }
    }
}
