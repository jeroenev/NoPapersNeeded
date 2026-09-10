package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HistoryMapperTest {
    private val docId = "a".repeat(64)

    private fun event(
        op: JournalOp,
        hlc: Hlc = Hlc(1, 0, "dev"),
    ) = JournalEvent(1, 1, hlc, hlc.deviceId, docId, op)

    private fun record(event: JournalEvent) = DocumentHistoryRecord(event.hlc, event.dev, event.op.wire, event)

    @Test
    fun mapsAllDocumentOpTypes() {
        val events =
            listOf(
                event(JournalOp.TagAdd("invoice")),
                event(JournalOp.TagRemove("draft", emptyList())),
                event(JournalOp.FieldSet(FieldKeys.TITLE, "New title")),
                event(JournalOp.FieldSet(FieldKeys.NOTES, "Remember this")),
                event(JournalOp.FieldSet(FieldKeys.CORRESPONDENT, "Acme")),
                event(JournalOp.FieldSet(FieldKeys.DOC_TYPE, "Invoice")),
                event(JournalOp.FieldSet(FieldKeys.DOC_DATE, "2026-07-20")),
                event(JournalOp.PropSet(SystemProperty.REVIEWED, "true")),
                event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, "tesseract")),
                event(JournalOp.FieldSet("amount", "42")),
                event(JournalOp.FieldSet("amount", null)),
                event(JournalOp.DocTrash("orig.pdf")),
                event(JournalOp.DocRestore),
                event(JournalOp.DocDelete),
                event(JournalOp.Relink("b".repeat(64))),
                event(JournalOp.Unknown("future-op")),
            )

        // Registry labels stand in for keys; a key the registry does not know shows as itself.
        val labels = FieldKeys.SEED_LABELS
        val items = HistoryMapper.toHistoryItems(events.map(::record)) { labels[it] ?: it }

        items.map { it.description } shouldContainExactly
            listOf(
                history(HistoryTextKey.TAG_ADDED, "invoice"),
                history(HistoryTextKey.TAG_REMOVED, "draft"),
                history(HistoryTextKey.FIELD_SET, "Title", "New title"),
                history(HistoryTextKey.FIELD_SET, "Notes", "Remember this"),
                history(HistoryTextKey.FIELD_SET, "Correspondent", "Acme"),
                history(HistoryTextKey.FIELD_SET, "Document type", "Invoice"),
                history(HistoryTextKey.FIELD_SET, "Document date", "2026-07-20"),
                history(HistoryTextKey.MARKED_REVIEWED),
                history(HistoryTextKey.OCR_ENGINE_PINNED, "tesseract"),
                history(HistoryTextKey.FIELD_SET, "amount", "42"),
                history(HistoryTextKey.FIELD_REMOVED, "amount"),
                history(HistoryTextKey.DOCUMENT_TRASHED, "orig.pdf"),
                history(HistoryTextKey.DOCUMENT_RESTORED),
                history(HistoryTextKey.DOCUMENT_DELETED),
                history(HistoryTextKey.DOCUMENT_RELINKED, "bbbbbbbb..."),
                history(HistoryTextKey.UNKNOWN_EVENT, "future-op"),
            )
        items.map { it.kind } shouldContainExactly
            listOf(
                HistoryEventKind.TAG_ADD,
                HistoryEventKind.TAG_REMOVE,
                HistoryEventKind.FIELD_SET,
                HistoryEventKind.FIELD_SET,
                HistoryEventKind.FIELD_SET,
                HistoryEventKind.FIELD_SET,
                HistoryEventKind.FIELD_SET,
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
        val events =
            listOf(
                event(JournalOp.TagAdd("second"), Hlc(200, 0, "dev")),
                event(JournalOp.TagAdd("first"), Hlc(100, 0, "dev")),
            )
        val items = HistoryMapper.toHistoryItems(events.map(::record))
        items.map { it.description } shouldContainExactly
            listOf(
                history(HistoryTextKey.TAG_ADDED, "second"),
                history(HistoryTextKey.TAG_ADDED, "first"),
            )
    }

    @Test
    fun clearedPropertiesAndReviewedFalse() {
        val events =
            listOf(
                event(JournalOp.FieldSet(FieldKeys.TITLE, null)),
                event(JournalOp.PropSet(SystemProperty.REVIEWED, "false")),
                event(JournalOp.PropSet(SystemProperty.OCR_ENGINE, null)),
            )
        val items = HistoryMapper.toHistoryItems(events.map(::record))
        items.map { it.description } shouldContainExactly
            listOf(
                history(HistoryTextKey.FIELD_REMOVED, FieldKeys.TITLE),
                history(HistoryTextKey.MARKED_NEEDS_REVIEW),
                history(HistoryTextKey.OCR_ENGINE_CLEARED),
            )
    }

    @Test
    fun mapsUndecodableRecord() {
        val item =
            HistoryMapper
                .toHistoryItems(
                    listOf(DocumentHistoryRecord(Hlc(1, 2, "dev"), "dev", "future-op", null)),
                ).single()

        item.kind shouldBe HistoryEventKind.UNKNOWN
        item.description shouldBe history(HistoryTextKey.UNDECODABLE, "future-op")
    }

    private fun history(
        key: HistoryTextKey,
        vararg arguments: String,
    ) = HistoryDescription(key, arguments.toList())

    @Test
    fun clipsLongFieldValues() {
        val long = "x".repeat(200)
        val items = HistoryMapper.toHistoryItems(listOf(record(event(JournalOp.FieldSet(FieldKeys.NOTES, long)))))
        val shown = items.single().description.arguments[1]
        shown.length shouldBe 80
        shown.endsWith("\u2026") shouldBe true
    }
}
