package dev.npn.core.db

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FtsQueryTest {

    @Test
    fun singleTokenGetsQuotedAndStarred() {
        FtsQuery.sanitize("invoice") shouldBe "\"invoice\"*"
    }

    @Test
    fun trailingStarAddedToLastTokenOnly() {
        FtsQuery.sanitize("tax invoice 2026") shouldBe "\"tax\" \"invoice\" \"2026\"*"
    }

    @Test
    fun embeddedDoubleQuotesStripped() {
        FtsQuery.sanitize("in\"voice") shouldBe "\"invoice\"*"
    }

    @Test
    fun whitespaceCollapsed() {
        FtsQuery.sanitize("  tax    invoice  ") shouldBe "\"tax\" \"invoice\"*"
    }

    @Test
    fun blankInputReturnsEmptyQuotedString() {
        FtsQuery.sanitize("") shouldBe "\"\""
        FtsQuery.sanitize("   ") shouldBe "\"\""
    }
}
