package dev.npn.core.ai

import dev.npn.core.db.documentEntity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class LibraryAiToolsTest {
    private fun doc(
        docId: String = "a".repeat(64),
        relPath: String = "inbox/report.pdf",
        fileName: String = "report.pdf",
        title: String? = null,
        docDate: String? = null,
        correspondent: String? = null,
    ) = documentEntity(docId, relPath, fileName, title = title, docDate = docDate, correspondent = correspondent)

    @Test
    fun docLineIncludesDocIdAndFileName() {
        val id = "a".repeat(64)
        val line = LibraryAiTools.docLine(doc(docId = id, fileName = "report.pdf"))
        line shouldContain "[doc:$id]"
        line shouldContain "file=\"report.pdf\""
    }

    @Test
    fun docLineIncludesOptionalMetadataWhenPresent() {
        val line =
            LibraryAiTools.docLine(
                doc(title = "Q3 Report", docDate = "2026-07-15", correspondent = "ACME"),
            )
        line shouldContain "title=\"Q3 Report\""
        line shouldContain "date=2026-07-15"
        line shouldContain "correspondent=\"ACME\""
    }

    @Test
    fun docLineOmitsMetadataWhenNull() {
        val line = LibraryAiTools.docLine(doc(title = null, docDate = null, correspondent = null))
        line shouldNotContain "title="
        line shouldNotContain "date="
        line shouldNotContain "correspondent="
    }

    @Test
    fun docLineStripsSnippetMarkers() {
        val snippet = "the \u0001invoice\u0002 total"
        val line = LibraryAiTools.docLine(doc(), snippet)
        line shouldContain "snippet=\"the invoice total\""
        line shouldNotContain "\u0001"
        line shouldNotContain "\u0002"
    }

    @Test
    fun stripSnippetMarkersRemovesControlChars() {
        LibraryAiTools.stripSnippetMarkers("a\u0001b\u0002c") shouldBe "abc"
    }

    @Test
    fun docLineEscapesQuotedValuesAndLineBreaks() {
        val line =
            LibraryAiTools.docLine(
                doc(fileName = "a\"b.pdf", title = "first\nsecond"),
                snippet = "tab\there",
            )

        line shouldContain "file=\"a\\\"b.pdf\""
        line shouldContain "title=\"first\\nsecond\""
        line shouldContain "snippet=\"tab\\there\""
        line.lines().size shouldBe 1
    }
}
