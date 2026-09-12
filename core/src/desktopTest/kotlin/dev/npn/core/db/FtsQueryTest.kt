package dev.npn.core.db

import dev.npn.core.index.FtsRows
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

class FtsQueryTest {
    @Test
    fun prefixSuggestionRowsDoNotReturnWholeDocumentText() =
        runTest {
            val dir = Files.createTempDirectory("npn-fts-query-test").toFile()
            val db = npnDatabase(dir.resolve("index.db"))
            try {
                val largeText = "invoice " + "payload ".repeat(20_000)
                db.ftsDao().replaceDocument(
                    FtsRows.row("doc", "invoice.pdf", emptyList(), mapOf("notes" to largeText), largeText),
                )

                val row = db.ftsDao().rowsForPrefix("\"inv\"*", 8).single()

                row.content.orEmpty().length shouldBeLessThan 1_000
                row.fields.orEmpty().length shouldBeLessThan 1_000
            } finally {
                db.close()
                dir.deleteRecursively()
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
