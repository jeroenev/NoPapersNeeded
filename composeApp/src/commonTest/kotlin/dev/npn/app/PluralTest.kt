package dev.npn.app

import dev.npn.app.ui.common.plural
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PluralTest {
    @Test
    fun singularOnlyForExactlyOne() {
        plural(1, "document") shouldBe "document"
    }

    @Test
    fun defaultPluralAppendsS() {
        plural(0, "document") shouldBe "documents"
        plural(2, "tag") shouldBe "tags"
        plural(42, "file") shouldBe "files"
    }

    @Test
    fun negativeAndZeroCountsUsePlural() {
        plural(0, "item") shouldBe "items"
        plural(-1, "item") shouldBe "items"
    }

    @Test
    fun explicitPluralOverridesDefault() {
        plural(3, "index", "indices") shouldBe "indices"
        plural(1, "index", "indices") shouldBe "index"
    }
}
