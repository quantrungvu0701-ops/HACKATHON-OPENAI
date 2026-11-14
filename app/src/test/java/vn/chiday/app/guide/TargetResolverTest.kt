package vn.chiday.app.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import vn.chiday.app.guide.TargetResolver.Node
import vn.chiday.app.guide.TargetResolver.Outcome
import vn.chiday.app.guide.TargetResolver.Towards

class TargetResolverTest {
    private val screen = listOf(0, 0, 1080, 2340)
    private fun node(l: Int, t: Int, r: Int, b: Int, clickable: Boolean = true) = Node(listOf(l, t, r, b), clickable)

    @Test fun clickableTargetFullyVisibleIsTapped() {
        val nodes = listOf(node(0, 300, 1080, 500), node(0, 500, 1080, 700))
        assertEquals(Outcome.Tap(1), TargetResolver.resolve(nodes, screen, 1))
    }

    @Test fun plainTextMovesToSmallestClickableAroundIt() {
        val nodes = listOf(
            node(0, 200, 1080, 1400), // list, clickable
            node(0, 300, 1080, 500), // row
            node(40, 340, 600, 400, clickable = false), // label inside the row
        )
        assertEquals(Outcome.Tap(1), TargetResolver.resolve(nodes, screen, 2))
    }

    @Test fun plainTextWithNoTapTargetAroundStaysWhereItIs() {
        val nodes = listOf(node(0, 0, 1080, 2340), node(40, 340, 600, 400, clickable = false))
        // The only clickable container is the whole page: not a button.
        assertEquals(Outcome.Tap(1), TargetResolver.resolve(nodes, screen, 1))
    }

    @Test fun targetCutAtBottomEdgeIsNudgedTowardsBelow() {
        val nodes = listOf(node(0, 2240, 1080, 2440))
        assertEquals(Outcome.Nudge(0, Towards.BELOW), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun targetCutAtTopEdgeIsNudgedTowardsAbove() {
        val nodes = listOf(node(0, -120, 1080, 80))
        assertEquals(Outcome.Nudge(0, Towards.ABOVE), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun slightlyCutTargetIsStillTapped() {
        val nodes = listOf(node(0, 2180, 1080, 2380)) // 80% visible
        assertEquals(Outcome.Tap(0), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun targetBelowTheScreenIsAScroll() {
        val nodes = listOf(node(0, 2340, 1080, 2340))
        assertEquals(Outcome.Scroll(Towards.BELOW), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun rowUnderFloatingButtonIsNudged() {
        val nodes = listOf(
            node(0, 2000, 1080, 2200), // «Display» row
            node(500, 2050, 1040, 2190), // floating «Search» pill drawn later
        )
        assertEquals(Outcome.Nudge(0, Towards.BELOW), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun childrenAndContainersAreNotCovers() {
        val nodes = listOf(
            node(0, 200, 1080, 1400), // list containing the row
            node(0, 300, 1080, 500), // row
            node(600, 310, 1040, 490), // big switch inside the row
        )
        assertEquals(Outcome.Tap(1), TargetResolver.resolve(nodes, screen, 1))
    }

    @Test fun neighbouringRowsAreNotCovers() {
        val nodes = listOf(node(0, 300, 1080, 500), node(0, 498, 1080, 700))
        assertEquals(Outcome.Tap(0), TargetResolver.resolve(nodes, screen, 0))
    }

    @Test fun scrollStepWithoutTargetScrolls() {
        assertEquals(Outcome.Scroll(Towards.BELOW), TargetResolver.resolve(emptyList(), screen, null, scrollStep = true))
    }

    @Test fun scrollStepWithVisibleTargetIsLookNotTap() {
        val nodes = listOf(node(0, 200, 1080, 1400), node(40, 340, 600, 400, clickable = false))
        assertEquals(Outcome.Look(1), TargetResolver.resolve(nodes, screen, 1, scrollStep = true))
    }

    @Test fun noTargetOnATapStepIsNotSure() {
        assertNull(TargetResolver.resolve(listOf(node(0, 0, 10, 10)), screen, null))
        assertNull(TargetResolver.resolve(listOf(node(0, 0, 10, 10)), screen, 7))
    }

    @Test fun scrollStepsAreRecognisedFromThePlaybookText() {
        assertTrue(TargetResolver.isScrollStep("Kéo màn hình xuống tới phần «Thành phần hồ sơ». Đó là danh sách giấy tờ bác cần mang theo"))
        assertFalse(TargetResolver.isScrollStep("Kéo màn hình lên một chút rồi bấm vào mục «Display» (Màn hình)"))
        assertFalse(TargetResolver.isScrollStep("Bấm nút «Tìm kiếm» màu xanh ở dưới"))
        assertFalse(TargetResolver.isScrollStep(null))
    }

    @Test fun scrollDirectionDefaultsToBelow() {
        assertEquals(Towards.BELOW, TargetResolver.scrollTowards("Kéo màn hình xuống tới phần «Thành phần hồ sơ»"))
        assertEquals(Towards.ABOVE, TargetResolver.scrollTowards("Kéo màn hình ngược lên đầu trang"))
    }
}
