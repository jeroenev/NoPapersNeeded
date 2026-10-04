package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class HistoryMapperTest {
    private fun record(
        entry: HistoryEntry,
        timeMillis: Long = 1,
    ) = DocumentHistoryRecord(timeMillis, "dev", entry)

    @Test
    fun mapsAllDocumentEntries() {
        val entries =
            listOf(
                HistoryEntry.TagAdded("invoice"),
                HistoryEntry.TagRemoved("draft"),
                HistoryEntry.FieldSet(FieldKeys.TITLE, "New title"),
                HistoryEntry.FieldSet(FieldKeys.NOTES, "Remember this"),
                HistoryEntry.FieldSet(FieldKeys.CORRESPONDENT, "Acme"),
                HistoryEntry.FieldSet(FieldKeys.DOC_TYPE, "Invoice"),
                HistoryEntry.FieldSet(FieldKeys.DOC_DATE, "2026-07-20"),
                HistoryEntry.Reviewed(true),
                HistoryEntry.OcrEnginePinned("tesseract"),
                HistoryEntry.FieldSet("amount", "42"),
                HistoryEntry.FieldSet("amount", null),
                HistoryEntry.Trashed("orig.pdf"),
                HistoryEntry.Restored,
                HistoryEntry.Deleted,
                HistoryEntry.Relinked("b".repeat(64)),
                HistoryEntry.Unknown("future-op", null),
            )

        // Registry labels stand in for keys; a key the registry does not know shows as itself.
        val labels = FieldKeys.SEED_LABELS
        val items = HistoryMapper.toHistoryItems(entries.map { record(it) }) { labels[it] ?: it }

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
        val records =
            listOf(
                record(HistoryEntry.TagAdded("second"), timeMillis = 200),
                record(HistoryEntry.TagAdded("first"), timeMillis = 100),
            )
        val items = HistoryMapper.toHistoryItems(records)
        items.map { it.description } shouldContainExactly
            listOf(
                history(HistoryTextKey.TAG_ADDED, "second"),
                history(HistoryTextKey.TAG_ADDED, "first"),
            )
        items.map { it.timeMillis } shouldContainExactly listOf(200L, 100L)
    }

    @Test
    fun clearedPropertiesAndReviewedFalse() {
        val entries =
            listOf(
                HistoryEntry.FieldSet(FieldKeys.TITLE, null),
                HistoryEntry.Reviewed(false),
                HistoryEntry.OcrEnginePinned(null),
                HistoryEntry.ReviewUnreadable("maybe"),
            )
        val items = HistoryMapper.toHistoryItems(entries.map { record(it) })
        items.map { it.description } shouldContainExactly
            listOf(
                history(HistoryTextKey.FIELD_REMOVED, FieldKeys.TITLE),
                history(HistoryTextKey.MARKED_NEEDS_REVIEW),
                history(HistoryTextKey.OCR_ENGINE_CLEARED),
                history(HistoryTextKey.REVIEW_STATE_CHANGED, "maybe"),
            )
    }

    @Test
    fun mapsUndecodableRecord() {
        val item = HistoryMapper.toHistoryItems(listOf(record(HistoryEntry.Undecodable("future-op")))).single()

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
        val items = HistoryMapper.toHistoryItems(listOf(record(HistoryEntry.FieldSet(FieldKeys.NOTES, long))))
        val shown = items.single().description.arguments[1]
        shown.length shouldBe 80
        shown.endsWith("\u2026") shouldBe true
    }
}
