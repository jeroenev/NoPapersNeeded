package dev.npn.app.ui.common

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** Money is always fixed-decimal: no scientific notation at the cent fractions AI requests produce. */
class FormatTest {
    @Test
    fun smallCostsKeepTheirSignificantDecimals() {
        formatUsd(0.0) shouldBe "$0.00"
        formatUsd(0.0004) shouldBe "$0.0004"
        formatUsd(0.0100) shouldBe "$0.01"
        formatUsd(1.5) shouldBe "$1.50"
    }

    @Test
    fun dustBelowTheDisplayedPrecisionIsMarked() {
        formatUsd(0.00001) shouldBe "<$0.0001"
    }
}
