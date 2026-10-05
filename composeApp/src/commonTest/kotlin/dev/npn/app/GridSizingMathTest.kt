package dev.npn.app

import dev.npn.app.ui.library.gridColumnsFor
import dev.npn.app.ui.library.gridWidthForColumns
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The grid zoom steps one column per wheel notch / button press by inverting the
 * GridCells.Adaptive column formula. The invariant that makes that work: the width computed for a
 * target column count must produce exactly that count when fed back into the count formula.
 */
class GridSizingMathTest {
    @Test
    fun widthForColumnsRoundTripsThroughColumnsFor() {
        val availables = listOf(300f, 360f, 500f, 776f, 1000f, 1576f)
        for (available in availables) {
            for (columns in 1..12) {
                val width = gridWidthForColumns(available, columns)
                if (width <= 0f) continue // count not achievable in this space
                gridColumnsFor(available, width) shouldBe columns
            }
        }
    }

    @Test
    fun moreColumnsMeansStrictlySmallerWidth() {
        val available = 776f
        for (columns in 1..11) {
            gridWidthForColumns(available, columns + 1) shouldBeLessThan
                gridWidthForColumns(available, columns)
        }
    }

    @Test
    fun columnsForMatchesAdaptiveFormulaExamples() {
        // available 776dp, spacing 8dp: floor(784 / (width + 8)).
        gridColumnsFor(776f, 160f) shouldBe 4
        gridColumnsFor(776f, 148f) shouldBe 5
        gridColumnsFor(776f, 100f) shouldBe 7
        gridColumnsFor(776f, 280f) shouldBe 2
        // Never below one column, however large the min width.
        gridColumnsFor(100f, 280f) shouldBe 1
    }
}
