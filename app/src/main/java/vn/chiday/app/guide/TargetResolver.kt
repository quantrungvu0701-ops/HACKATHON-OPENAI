package vn.chiday.app.guide

/**
 * Decides what to do with the element the model pointed at, from geometry alone. Pure Kotlin
 * (no android.* imports) so it runs in plain unit tests.
 *
 * The model picks an index; this checks that a finger can actually land there:
 *  - plain text inside a tap target: ring the tap target instead;
 *  - element cut off by the screen edge or hidden under a floating button: do not ring it,
 *    ask the user to move the screen first;
 *  - a step that is about scrolling or reading: never say "bấm".
 */
object TargetResolver {

    /**
     * [bounds] is `[left, top, right, bottom]` in screen pixels, NOT clipped: the element's real
     * size. [shown] is the part of it that is actually drawn (clipped by the screen, by system
     * bars and by scrolling containers); it defaults to [bounds].
     */
    data class Node(val bounds: List<Int>, val clickable: Boolean, val shown: List<Int> = bounds)

    /** Where the content the user needs is, relative to what is on screen now. */
    enum class Towards { BELOW, ABOVE }

    sealed interface Outcome {
        /** Ring node [index] and tell the user to press it. May differ from the model's index. */
        data class Tap(val index: Int) : Outcome

        /** Ring node [index] but only to look at or read: the instruction must not say "bấm". */
        data class Look(val index: Int) : Outcome

        /** Nothing to ring: what the user needs is off screen in direction [towards]. */
        data class Scroll(val towards: Towards) : Outcome

        /** Node [index] is on screen but cut off or covered: move the screen a little first. */
        data class Nudge(val index: Int, val towards: Towards) : Outcome
    }

    /** Below this visible share of its height an element counts as cut off. */
    const val MIN_VISIBLE = 0.7

    /** A floating element hiding more than this share of the target counts as covering it. */
    const val MAX_COVERED = 0.3

    /** A clickable container bigger than this share of the screen is a page, not a button. */
    private const val MAX_CONTAINER = 0.6

    private val PRESS_WORDS = setOf("bấm", "nhấn", "chạm", "ấn", "gõ", "chọn")
    private val WORD = Regex("[\\p{L}\\p{N}]+")

    private fun nfc(s: String?) = java.text.Normalizer.normalize(s.orEmpty(), java.text.Normalizer.Form.NFC).lowercase()

    /**
     * A playbook step is a scroll step when its "do" starts with "Kéo" and asks for no press
     * or typing. "Kéo màn hình lên một chút rồi bấm vào «Display»" is a tap step.
     */
    fun isScrollStep(action: String?): Boolean {
        val words = WORD.findAll(nfc(action)).map { it.value }.toList()
        return words.firstOrNull() == "kéo" && words.none { it in PRESS_WORDS }
    }

    /** Direction named by a scroll instruction. "Kéo xuống" (the usual case) means content below. */
    fun scrollTowards(action: String?): Towards {
        val t = nfc(action)
        return if ("lên đầu" in t || "lên trên" in t || "ngược lên" in t) Towards.ABOVE else Towards.BELOW
    }

    /**
     * @param screen `[left, top, right, bottom]` of the display.
     * @param target the model's index, or null when it named none.
     * @param scrollStep the step is about scrolling or reading, not pressing.
     * @return null when there is no target and the step is not a scroll step ("not sure").
     */
    fun resolve(
        nodes: List<Node>,
        screen: List<Int>,
        target: Int?,
        scrollStep: Boolean = false,
        scrollTowards: Towards = Towards.BELOW,
    ): Outcome? {
        if (target == null || target !in nodes.indices) {
            return if (scrollStep) Outcome.Scroll(scrollTowards) else null
        }
        val index = if (scrollStep) target else tapTarget(nodes, screen, target)
        val b = nodes[index].bounds

        // Cut off by the top or bottom edge of the screen.
        val height = b[3] - b[1]
        val shown = nodes[index].shown
        val visibleHeight = minOf(shown[3], screen[3]) - maxOf(shown[1], screen[1])
        if (height <= 0 || visibleHeight <= 0) {
            return Outcome.Scroll(if (b[1] >= centreY(screen)) Towards.BELOW else Towards.ABOVE)
        }
        if (visibleHeight < height * MIN_VISIBLE) {
            return Outcome.Nudge(index, if (b[3] > minOf(shown[3], screen[3])) Towards.BELOW else Towards.ABOVE)
        }

        // Hidden under something floating (a search pill, a sticky header, a chat button).
        val visible = intersect(shown, screen) ?: return Outcome.Scroll(scrollTowards)
        val visibleArea = area(visible)
        for ((j, other) in nodes.withIndex()) {
            if (j == index || !other.clickable) continue
            val o = other.bounds
            val overlap = intersect(visible, o) ?: continue
            if (area(overlap) <= visibleArea * MAX_COVERED) continue
            // A container of the target or something inside it is not a cover.
            // (Loose fit: a widget's background can be a few pixels shorter than the part on it.)
            if (contains(o, b, WRAP_SLACK) || contains(b, o, WRAP_SLACK)) continue
            // Drawn later (further on in the tree) or smaller: it sits on top of the target.
            if (j > index || area(o) < area(b)) {
                return Outcome.Nudge(index, if (centreY(o) >= centreY(b)) Towards.BELOW else Towards.ABOVE)
            }
        }

        return if (scrollStep) Outcome.Look(index) else Outcome.Tap(index)
    }

    /** The smallest clickable element around a non-clickable target, or the target itself. */
    private fun tapTarget(nodes: List<Node>, screen: List<Int>, target: Int): Int {
        if (nodes[target].clickable) return target
        val b = nodes[target].bounds
        val limit = area(screen) * MAX_CONTAINER
        var best = target
        var bestArea = Long.MAX_VALUE
        for ((j, n) in nodes.withIndex()) {
            if (j == target || !n.clickable) continue
            val a = area(n.bounds)
            if (a < bestArea && a <= limit && contains(n.bounds, b)) {
                best = j
                bestArea = a
            }
        }
        return best
    }

    private const val SLACK = 2

    private const val WRAP_SLACK = 24

    private fun contains(outer: List<Int>, inner: List<Int>, slack: Int = SLACK) =
        outer[0] <= inner[0] + slack && outer[1] <= inner[1] + slack &&
            outer[2] >= inner[2] - slack && outer[3] >= inner[3] - slack

    private fun intersect(a: List<Int>, b: List<Int>): List<Int>? {
        val l = maxOf(a[0], b[0])
        val t = maxOf(a[1], b[1])
        val r = minOf(a[2], b[2])
        val bt = minOf(a[3], b[3])
        return if (r > l && bt > t) listOf(l, t, r, bt) else null
    }

    private fun area(r: List<Int>): Long = (r[2] - r[0]).coerceAtLeast(0).toLong() * (r[3] - r[1]).coerceAtLeast(0)

    private fun centreY(r: List<Int>) = (r[1] + r[3]) / 2
}
