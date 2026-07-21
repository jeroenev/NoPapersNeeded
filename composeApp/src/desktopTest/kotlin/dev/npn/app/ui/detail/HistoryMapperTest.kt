package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.DocProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HistoryMapperTest {

    private val docId = "a".repeat(64)

    private fun event(op: JournalOp, hlc: Hlc = Hlc(1, 0, "dev")) =
        JournalEvent(1, 1, hlc, hlc.deviceId, docId, op)

    private fun record(event: JournalEvent) =
        DocumentHistoryRecord(event.hlc, event.dev, event.op.wire, event)

    @Test
    fun mapsAllDocumentOpTypes() {
        val events = listOf(
            event(JournalOp.TagAdd("invoice")),
            event(JournalOp.TagRemove("draft", emptyList())),
            event(JournalOp.PropSet(DocProperty.TITLE, "New title")),
            event(JournalOp.PropSet(DocProperty.NOTES, "Remember this")),
            event(JournalOp.PropSet(DocProperty.CORRESPONDENT, "Acme")),
            event(JournalOp.PropSet(DocProperty.DOC_TYPE, "Invoice")),
            event(JournalOp.PropSet(DocProperty.DOC_DATE, "2026-07-20")),
            event(JournalOp.PropSet(DocProperty.REVIEWED, "true")),
            event(JournalOp.PropSet(DocProperty.OCR_ENGINE, "tesseract")),
            event(JournalOp.FieldSet("amount", "42")),
            event(JournalOp.FieldSet("amount", null)),
            event(JournalOp.DocTrash("orig.pdf")),
            event(JournalOp.DocRestore),
            event(JournalOp.DocDelete),
            event(JournalOp.Relink("b".repeat(64))),
            event(JournalOp.Unknown("future-op")),
        )

        val items = HistoryMapper.toHistoryItems(events.map(::record))

        items.map { it.description } shouldContainExactly listOf(
            "Added tag 'invoice'",
            "Removed tag 'draft'",
            "Changed title to 'New title'",
            "Updated note",
            "Changed correspondent to 'Acme'",
            "Changed document type to 'Invoice'",
            "Changed document date to 2026-07-20",
            "Marked reviewed",
            "Pinned OCR engine to tesseract",
            "Set field 'amount' to '42'",
            "Removed field 'amount'",
            "Trashed document from 'orig.pdf'",
            "Restored document",
            "Permanently deleted document",
            "Relinked to document bbbbbbbb...",
            "Unknown event 'future-op'",
        )
        items.map { it.kind } shouldContainExactly listOf(
            HistoryEventKind.TAG_ADD,
            HistoryEventKind.TAG_REMOVE,
            HistoryEventKind.PROP_TITLE,
            HistoryEventKind.PROP_NOTES,
            HistoryEventKind.PROP_CORRESPONDENT,
            HistoryEventKind.PROP_DOC_TYPE,
            HistoryEventKind.PROP_DOC_DATE,
            HistoryEventKind.PROP_REVIEWED,
            HistoryEventKind.PROP_OCR_ENGINE,
            HistoryEventKind.FIELD_SET,
            HistoryEventKind.FIELD_REMOVE,
            HistoryEventKind.TRASH,
            HistoryEventKind.RESTORE,
            HistoryEventKind.DELETE,
            HistoryEventKind.RELINK,
            HistoryEventKind.UNKNOWN,
        )
    }

    @Test
    fun preservesEventOrder() {
        val events = listOf(
            event(JournalOp.TagAdd("second"), Hlc(200, 0, "dev")),
            event(JournalOp.TagAdd("first"), Hlc(100, 0, "dev")),
        )
        val items = HistoryMapper.toHistoryItems(events.map(::record))
        items.map { it.description } shouldContainExactly listOf(
            "Added tag 'second'",
            "Added tag 'first'",
        )
    }

    @Test
    fun clearedPropertiesAndReviewedFalse() {
        val events = listOf(
            event(JournalOp.PropSet(DocProperty.TITLE, null)),
            event(JournalOp.PropSet(DocProperty.REVIEWED, "false")),
            event(JournalOp.PropSet(DocProperty.OCR_ENGINE, null)),
        )
        val items = HistoryMapper.toHistoryItems(events.map(::record))
        items.map { it.description } shouldContainExactly listOf(
            "Cleared title",
            "Marked needs review",
            "Cleared OCR engine pin",
        )
    }

    @Test
    fun mapsUndecodableRecord() {
        val item = HistoryMapper.toHistoryItems(
            listOf(DocumentHistoryRecord(Hlc(1, 2, "dev"), "dev", "future-op", null)),
        ).single()

        item.kind shouldBe HistoryEventKind.UNKNOWN
        item.description shouldBe "Could not decode journal event 'future-op'"
    }
}
