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
        /**
         * How many times this step is done in a row on the same screen (CapCut: Split twice).
         * The guide stays on it until it has been shown this many times.
         */
        val repeat: Int = 1,
        /**
         * Words that prove the user has reached what this step points to (a section title or
         * its item labels). While none is on screen, a "read here" answer is turned into
         * "scroll further" with [notYet]: the model alone mistakes similar text for arrival.
         */
        val arrive: List<String> = emptyList(),
        @SerialName("not_yet") val notYet: String? = null,
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
    fun likelyStep(snapshot: ScreenSnapshot, lastStepN: Int? = null, lastStepShown: Int = 0): Step? {
        val scored = score(snapshot)
        if (lastStepN == null) {
            val best = scored.maxOfOrNull { it.second } ?: return null
            return scored.filter { it.second == best }.minByOrNull { it.first.n }?.first
        }
        // Progress only moves forward: headers, search boxes and menus of step 1 often stay
        // visible on later pages, so an earlier step matching is no reason to go back. Only
        // the current step and later ones are candidates. The current step is dropped once
        // it has been pressed as often as it needs (a `repeat` step) and a later one matches.
        var forward = scored.filter { it.first.n >= lastStepN }
        val current = steps.firstOrNull { it.n == lastStepN }
        if (current != null && lastStepShown >= current.repeat && forward.any { it.first.n > lastStepN }) {
            forward = forward.filter { it.first.n > lastStepN }
        }
        val best = forward.maxOfOrNull { it.second } ?: return null
        val tied = forward.filter { it.second == best }.map { it.first }
        // Several steps can share one screen (fill a field, then the next, then press the
        // button). Session progress breaks the tie: the current step while it still wants
        // presses, else the step after it, else the current one again, else the lowest.
        tied.firstOrNull { it.n == lastStepN && lastStepShown < it.repeat }?.let { return it }
        tied.firstOrNull { it.n == lastStepN + 1 }?.let { return it }
        tied.firstOrNull { it.n == lastStepN }?.let { return it }
        return tied.minByOrNull { it.n }
    }

    /**
     * Strong evidence that the user went back to the earlier step [n]: all of its signals are
     * on screen, the current step [lastStepN] has signals and none of them are, and no step
     * from [lastStepN] on matches at all.
     */
    fun clearlyBackAt(snapshot: ScreenSnapshot, n: Int, lastStepN: Int): Boolean {
        if (n >= lastStepN) return false
        val current = steps.firstOrNull { it.n == lastStepN } ?: return false
        if (current.signals.none { it.isNotBlank() }) return false
        val scored = score(snapshot)
        if (scored.any { it.first.n >= lastStepN }) return false
        return scored.any { it.first.n == n && it.second >= 1.0 }
    }

    /** Steps with at least one signal on screen, with the share of their signals found. */
    private fun score(snapshot: ScreenSnapshot): List<Pair<Step, Double>> {
        val screenText = snapshot.nodes.joinToString("\n") { listOfNotNull(it.text, it.desc, it.hint).joinToString(" ") }.lowercase()
        return steps.mapNotNull { step ->
            val signals = step.signals.filter { it.isNotBlank() }
            if (signals.isEmpty()) return@mapNotNull null
            val hit = signals.count { screenText.contains(it.lowercase()) }
            if (hit == 0) null else step to hit.toDouble() / signals.size
        }
    }
}
