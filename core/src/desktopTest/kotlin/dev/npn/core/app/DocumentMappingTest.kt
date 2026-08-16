package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The one mapping from the disposable index row to the [Doc] every reader sees
 * ([DocumentEntity.toDoc]). Guards the two things the mapping decides on the caller's behalf: the
 * display-title fallback and decoding the stringly-typed OCR columns.
 */
class DocumentMappingTest {
    private fun entity(
        title: String? = null,
        ocrState: String = OcrState.NONE.column,
        ocrEngine: String? = null,
        ocrEngineOverride: String? = null,
    ) = DocumentEntity(
        docId = "a".repeat(64),
        relPath = "bank/statement.pdf",
        fileName = "statement.pdf",
        size = 1234L,
        mtime = 99L,
        pageCount = 3,
        addedAt = 42L,
        reviewed = true,
        correspondent = "Bank",
        docType = "statement",
        docDate = "2026-07-15",
        title = title,
        notes = "a note",
        trashed = true,
        trashedOrigPath = "inbox/statement.pdf",
        deleted = false,
        missing = true,
        ocrState = ocrState,
        ocrEngine = ocrEngine,
        ocrEngineOverride = ocrEngineOverride,
        aiEnrichedAt = 7L,
    )

    @Test
    fun carriesEveryExposedColumn() {
        val doc = entity(title = "July statement").toDoc()

        doc shouldBe
            Doc(
                docId = "a".repeat(64),
                relPath = "bank/statement.pdf",
                fileName = "statement.pdf",
                displayTitle = "July statement",
                title = "July statement",
                size = 1234L,
                pageCount = 3,
                addedAt = 42L,
                reviewed = true,
                correspondent = "Bank",
                docType = "statement",
                docDate = "2026-07-15",
                notes = "a note",
                trashed = true,
                trashedOrigPath = "inbox/statement.pdf",
                missing = true,
                ocrState = OcrState.NONE,
                ocrEngine = null,
                ocrEngineOverride = null,
            )
    }

    @Test
    fun displayTitleFallsBackToFileNameWhenTitleIsNullOrBlank() {
        entity(title = null).toDoc().displayTitle shouldBe "statement.pdf"
        entity(title = "   ").toDoc().displayTitle shouldBe "statement.pdf"
        entity(title = "  Kept  ").toDoc().displayTitle shouldBe "  Kept  "
    }

    @Test
    fun decodesOcrVocabulary() {
        val doc =
            entity(
                ocrState = OcrState.ADOPTED.column,
                ocrEngine = OcrEngineId.MLKIT.column,
                ocrEngineOverride = OcrEngineId.TESSERACT.column,
            ).toDoc()

        doc.ocrState shouldBe OcrState.ADOPTED
        doc.ocrEngine shouldBe OcrEngineRef.Known(OcrEngineId.MLKIT)
        doc.ocrEngineOverride shouldBe OcrEngineRef.Known(OcrEngineId.TESSERACT)
    }

    /**
     * A row written by a newer build (or a legacy "mixed" sidecar) must read, not crash: the index
     * is rebuildable, but a stale row reaching the UI is a normal thing to survive.
     */
    @Test
    fun unknownOcrStateDegradesInsteadOfThrowing() {
        entity(ocrState = "quantum").toDoc().ocrState shouldBe OcrState.NONE
    }

    /**
     * An engine label this build has no enum for must stay distinguishable from "no engine at
     * all". Collapsing the two would make an indexed document read as un-indexed, and would make
     * an engine pin that OcrPipeline still honours read as unpinned — which the detail panel would
     * then offer to clear.
     */
    @Test
    fun unknownEngineKeepsItsLabelRatherThanBecomingAbsent() {
        val doc = entity(ocrEngine = "mixed", ocrEngineOverride = "mixed").toDoc()

        doc.ocrEngine shouldBe OcrEngineRef.Unknown("mixed")
        doc.ocrEngineOverride shouldBe OcrEngineRef.Unknown("mixed")
        // The label round-trips, so re-pinning it writes back the same column value.
        doc.ocrEngineOverride?.label shouldBe "mixed"
    }

    @Test
    fun absentEngineIsNullAndBlankCountsAsAbsent() {
        entity(ocrEngine = null).toDoc().ocrEngine shouldBe null
        entity(ocrEngine = "").toDoc().ocrEngine shouldBe null
    }
}
