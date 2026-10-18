package dev.npn.core.db

import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FtsQueryTest {
    @Test
    fun prefixSuggestionRowsDoNotReturnWholeDocumentText() =
        runTest {
            TempIndexDb("npn-fts-query-test").use { temp ->
                val db = temp.db
                val largeText = "invoice " + "payload ".repeat(20_000)
                db.ftsDao().upsertContent(
                    FtsRows.row("doc", "invoice.pdf", emptyList(), mapOf("notes" to largeText), largeText),
                )

                val row = db.ftsDao().rowsForPrefix("\"inv\"*", 8).single()

                row.content.orEmpty().length shouldBeLessThan 1_000
                row.fields.orEmpty().length shouldBeLessThan 1_000
            }
        }

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
    fun nulCharactersCannotBreakTheMatchExpression() {
        FtsQuery.sanitize("inv\u0000oice") shouldBe "\"invoice\"*"
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
