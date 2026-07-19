package dev.npn.app.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseSplitPointsTest {

    @Test
    fun singleCutPoint() {
        assertEquals(listOf(0..2, 3..5), parseSplitPoints("3", pageCount = 6))
    }

    @Test
    fun multipleCutPointsAnySeparatorOrderAndDuplicates() {
        assertEquals(listOf(0..0, 1..2, 3..5), parseSplitPoints("3, 1", pageCount = 6))
        assertEquals(listOf(0..0, 1..2, 3..5), parseSplitPoints("1;3 3", pageCount = 6))
    }

    @Test
    fun everyPageItsOwnPart() {
        assertEquals(listOf(0..0, 1..1), parseSplitPoints("1", pageCount = 2))
    }

    @Test
    fun invalidInputsReturnNull() {
        assertNull(parseSplitPoints("", pageCount = 6))
        assertNull(parseSplitPoints("  ", pageCount = 6))
        assertNull(parseSplitPoints("abc", pageCount = 6))
        assertNull(parseSplitPoints("0", pageCount = 6))       // before page 1
        assertNull(parseSplitPoints("6", pageCount = 6))       // after the last page
        assertNull(parseSplitPoints("2, x", pageCount = 6))
        assertNull(parseSplitPoints("1", pageCount = 1))       // nothing to split
    }
}
