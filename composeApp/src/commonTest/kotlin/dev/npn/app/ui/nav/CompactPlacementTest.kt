package dev.npn.app.ui.nav

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The compact shell splits the top-level destinations between the bottom bar and its "More" sheet.
 * The split used to be a hand-written list beside the enum, which drifted: a destination added to
 * the enum simply never appeared on compact layouts. These tests pin the properties that made the
 * drift possible.
 */
class CompactPlacementTest {
    @Test
    fun everyDestinationIsReachableOnCompactLayouts() {
        assertEquals(
            TopLevelDestination.entries.toSet(),
            (TopLevelDestination.compactBar + TopLevelDestination.compactMore).toSet(),
            "a destination in neither group is unreachable on compact layouts",
        )
    }

    @Test
    fun noDestinationIsInBothGroups() {
        val both = TopLevelDestination.compactBar.intersect(TopLevelDestination.compactMore.toSet())
        assertTrue(both.isEmpty(), "destinations in both the bar and the sheet: $both")
    }

    /**
     * The bar also carries the AI assistant and the "More" item itself, so the destinations on it
     * have to leave room for two more before Material's five-item ceiling.
     */
    @Test
    fun theBarLeavesRoomForTheAiAndMoreItems() {
        assertTrue(
            TopLevelDestination.compactBar.size <= MAX_BAR_ITEMS - 2,
            "${TopLevelDestination.compactBar.size} bar destinations plus AI and More overflow " +
                "a $MAX_BAR_ITEMS-item navigation bar",
        )
    }

    /** A bar of one real destination plus "More" reads as broken; keep at least two. */
    @Test
    fun theBarIsNotDegenerateWithoutTheAiItem() {
        assertTrue(
            TopLevelDestination.compactBar.size >= 2,
            "the bar must stand on its own when the AI assistant is not configured",
        )
    }

    @Test
    fun libraryLeadsTheBar() {
        assertEquals(TopLevelDestination.LIBRARY, TopLevelDestination.compactBar.first())
    }

    /** The sheet is the only way to those destinations; dropping one strands a screen. */
    @Test
    fun theSheetCarriesEveryOverflowDestination() {
        val listed =
            moreRows(aiEdit = true)
                .filterIsInstance<MoreRow.Destination>()
                .map { it.dest }

        assertEquals(TopLevelDestination.compactMore.toSet(), listed.toSet())
        assertEquals(listed.size, listed.distinct().size, "a destination is listed twice")
    }

    /** Settings closes the sheet the way the rail pins it to the bottom. */
    @Test
    fun settingsSitsLastInTheSheet() {
        val last = moreRows(aiEdit = true).last()

        assertEquals(MoreRow.Destination(TopLevelDestination.SETTINGS), last)
    }

    /** AI edit is a sheet row, not a destination, and follows the bar's AI assistant item. */
    @Test
    fun aiEditRidesInTheSheetOnlyWhenTheAiItemsAreShown() {
        assertTrue(MoreRow.AiEdit in moreRows(aiEdit = true))
        assertTrue(MoreRow.AiEdit !in moreRows(aiEdit = false))
    }

    /** With no AI, the sheet is exactly the overflow destinations — no gaps, no stray rows. */
    @Test
    fun theSheetWithoutAiIsJustTheOverflowDestinations() {
        assertEquals(
            TopLevelDestination.compactMore.map { MoreRow.Destination(it) },
            moreRows(aiEdit = false),
        )
    }

    private companion object {
        /** Material's ceiling for a navigation bar. */
        const val MAX_BAR_ITEMS = 5
    }
}
