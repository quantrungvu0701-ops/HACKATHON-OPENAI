package vn.chiday.app.playbook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import vn.chiday.app.screen.ScreenSnapshot

/** One task in one service. Schema from MECHANISM.md §3. */
@Serializable
data class Playbook(
    val id: String,
    val service: String,
    val match: Match = Match(),
    val goal: String,
    /** Null for drafts (pasted or recorded): the overlay then shows "chưa kiểm chứng". */
    val verified: Verified? = null,
    val steps: List<Step> = emptyList(),
) {
    @Serializable
    data class Match(val packages: List<String> = emptyList(), val hosts: List<String> = emptyList())

    @Serializable
    data class Verified(val by: String = "", val date: String = "")

    @Serializable
    data class Step(
        val n: Int,
        /** Short texts that appear on this step's screen. */
        val signals: List<String> = emptyList(),
        @SerialName("do") val action: String,
        @SerialName("target_hint") val targetHint: String? = null,
        val pitfall: String? = null,
        /** Payment, submission or deletion: the ring turns amber and the voice says to check first. */
        val caution: Boolean = false,
    )

    /** True when this playbook is meant for the app or website currently on screen. */
    fun appliesTo(snapshot: ScreenSnapshot): Boolean {
        if (match.packages.isNotEmpty() && snapshot.pkg !in match.packages) return false
        if (match.hosts.isNotEmpty()) {
            val host = snapshot.host ?: return false
            if (match.hosts.none { host == it || host.endsWith(".$it") }) return false
        }
        // A playbook with no match rules would attach itself to every app: never apply it.
        return match.packages.isNotEmpty() || match.hosts.isNotEmpty()
    }

    /**
     * The step whose signals best match the visible text, or null if none match. This is the
     * cheap string-matching half of "identify"; the model breaks ties and has the final say.
     */
    fun likelyStep(snapshot: ScreenSnapshot): Step? {
        val screenText = snapshot.nodes.joinToString("\n") { listOfNotNull(it.text, it.desc, it.hint).joinToString(" ") }.lowercase()
        return steps
            .map { step -> step to step.signals.count { it.isNotBlank() && screenText.contains(it.lowercase()) } }
            .filter { it.second > 0 }
            // Most signals matched; on a tie the later step, since earlier signals often stay on screen.
            .maxWithOrNull(compareBy<Pair<Step, Int>> { it.second }.thenBy { it.first.n })
            ?.first
    }
}
