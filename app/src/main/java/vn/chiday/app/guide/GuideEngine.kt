package vn.chiday.app.guide

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import vn.chiday.app.llm.Llm
import vn.chiday.app.llm.LlmException
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
    /** A validated instruction: [rect] comes from the accessibility tree, never from the model. */
    data class Show(val reply: GuideReply, val targetIndex: Int, val rect: Rect) : GuideResult

    /** The model could not tell, pointed at a node that does not exist, or was not confident. */
    data class NotSure(val why: String) : GuideResult

    /** No key, no network, HTTP error. [message] is for the log; the user sees a fixed sentence. */
    data class Failed(val message: String, val missingKey: Boolean = false) : GuideResult
}

/**
 * Turns one screen read into one validated instruction. Holds the session memory the prompt
 * needs (goal, last step). Never touches the screen: guide-only.
 */
class GuideEngine(private val settings: SettingsStore) {

    /** What the user wants to do, in their words. Null means "work it out from the screen". */
    var goal: String? = null

    /** Subtitle of the previous instruction in this session, for continuity between screens. */
    private var lastStep: String? = null

    fun resetSession() {
        lastStep = null
    }

    suspend fun decide(snapshot: ScreenSnapshot): GuideResult {
        val s = settings.current()
        if (!s.isComplete) return GuideResult.Failed("no API key configured", missingKey = true)
        if (snapshot.nodes.isEmpty()) return GuideResult.NotSure("empty node list")

        val raw = try {
            Llm.clientFor(s).complete(SYSTEM_PROMPT, userMessage(snapshot))
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return GuideResult.Failed(e.message ?: "model call failed")
        } catch (e: Exception) {
            return GuideResult.Failed(e.message ?: e.javaClass.simpleName)
        }

        val reply = parse(raw) ?: return GuideResult.NotSure("unparseable reply: ${raw.take(120)}")
        return validate(reply, snapshot).also {
            if (it is GuideResult.Show) lastStep = reply.subtitle ?: reply.say
        }
    }

    private fun userMessage(snapshot: ScreenSnapshot): String = buildString {
        append("GOAL: ").append(goal?.takeIf { it.isNotBlank() } ?: "not stated; choose the most likely next step on this screen").append('\n')
        append("LAST_STEP: ").append(lastStep ?: "none").append('\n')
        append("PLAYBOOK: none\n")
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
                "Reply with JSON only: {service, step, total_steps, target, action, say, subtitle, " +
                "caution, confidence}"

        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

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
