package dev.npn.app.ui.detail

import dev.npn.app.english
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_engine_tesseract
import no_papers_needed.composeapp.generated.resources.detail_history_document_deleted
import no_papers_needed.composeapp.generated.resources.detail_history_document_relinked
import no_papers_needed.composeapp.generated.resources.detail_history_document_restored
import no_papers_needed.composeapp.generated.resources.detail_history_document_trashed
import no_papers_needed.composeapp.generated.resources.detail_history_field_removed
import no_papers_needed.composeapp.generated.resources.detail_history_field_set
import no_papers_needed.composeapp.generated.resources.detail_history_marked_needs_review
import no_papers_needed.composeapp.generated.resources.detail_history_marked_reviewed
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_ocr_engine_pinned
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_changed
import no_papers_needed.composeapp.generated.resources.detail_history_setting_changed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
import no_papers_needed.composeapp.generated.resources.detail_history_tag_merged
import no_papers_needed.composeapp.generated.resources.detail_history_tag_removed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_renamed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_styled
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
                HistoryEntry.OcrEnginePinned(OcrEngineRef.Known(OcrEngineId.TESSERACT)),
                HistoryEntry.FieldSet("amount", "42"),
                HistoryEntry.FieldSet("amount", null),
                HistoryEntry.Trashed("orig.pdf"),
                HistoryEntry.Restored,
                HistoryEntry.Deleted,
                HistoryEntry.Relinked("b".repeat(64)),
            )

        // Registry labels stand in for keys; a key the registry does not know shows as itself.
        val labels = FieldKeys.SEED_LABELS
        val items = HistoryMapper.toTimeline(entries.map { record(it) }) { labels[it] ?: it }.items

        items.map { it.description } shouldContainExactly
            listOf(
                uiText(Res.string.detail_history_tag_added, "invoice"),
                uiText(Res.string.detail_history_tag_removed, "draft"),
                uiText(Res.string.detail_history_field_set, "Title", "New title"),
                uiText(Res.string.detail_history_field_set, "Notes", "Remember this"),
                uiText(Res.string.detail_history_field_set, "Correspondent", "Acme"),
                uiText(Res.string.detail_history_field_set, "Document type", "Invoice"),
                uiText(Res.string.detail_history_field_set, "Document date", "2026-07-20"),
                uiText(Res.string.detail_history_marked_reviewed),
                uiText(Res.string.detail_history_ocr_engine_pinned, uiText(Res.string.detail_engine_tesseract)),
                uiText(Res.string.detail_history_field_set, "amount", "42"),
                uiText(Res.string.detail_history_field_removed, "amount"),
                uiText(Res.string.detail_history_document_trashed, "orig.pdf"),
                uiText(Res.string.detail_history_document_restored),
                uiText(Res.string.detail_history_document_deleted),
                uiText(Res.string.detail_history_document_relinked),
            )
        items.map { it.entry } shouldContainExactly entries
    }

    /** A pin names its engine as the text-source picker does; one this build does not know, by its label. */
    @Test
    fun anEnginePinNamesTheEngineAsThePanelDoes() =
        runTest {
            val items =
                HistoryMapper
                    .toTimeline(
                        listOf(
                            record(HistoryEntry.OcrEnginePinned(OcrEngineRef.Known(OcrEngineId.EMBEDDED))),
                            record(HistoryEntry.OcrEnginePinned(OcrEngineRef.Unknown("future"))),
                        ),
                    ).items

            items.map { it.description.english() } shouldContainExactly
                listOf("Set the text source to Embedded text", "Set the text source to future")
        }

    @Test
    fun preservesEventOrder() {
        val records =
            listOf(
                record(HistoryEntry.TagAdded("second"), timeMillis = 200),
                record(HistoryEntry.TagAdded("first"), timeMillis = 100),
            )
        val items = HistoryMapper.toTimeline(records).items
        items.map { it.description } shouldContainExactly
            listOf(
                uiText(Res.string.detail_history_tag_added, "second"),
                uiText(Res.string.detail_history_tag_added, "first"),
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
        val items = HistoryMapper.toTimeline(entries.map { record(it) }).items
        items.map { it.description } shouldContainExactly
            listOf(
                uiText(Res.string.detail_history_field_removed, FieldKeys.TITLE),
                uiText(Res.string.detail_history_marked_needs_review),
                uiText(Res.string.detail_history_ocr_engine_cleared),
                uiText(Res.string.detail_history_review_state_changed, "maybe"),
            )
    }

    @Test
    fun tagRenamesMergesAndStylesAreNotUnknownEvents() {
        val entries =
            listOf(
                HistoryEntry.TagRenamed("inv", "invoice"),
                HistoryEntry.TagMerged("bill", "invoice"),
                HistoryEntry.TagStyled("invoice"),
                HistoryEntry.SettingChanged("reviewMode"),
            )
        val items = HistoryMapper.toTimeline(entries.map { record(it) }).items

        items.map { it.description } shouldContainExactly
            listOf(
                uiText(Res.string.detail_history_tag_renamed, "inv", "invoice"),
                uiText(Res.string.detail_history_tag_merged, "bill", "invoice"),
                uiText(Res.string.detail_history_tag_styled, "invoice"),
                uiText(Res.string.detail_history_setting_changed, "reviewMode"),
            )
        items.map { it.entry } shouldContainExactly entries
    }

    /**
     * What this build cannot read (an op it does not know, or cannot decode) is no row of its own:
     * the timeline counts it, for one line about a newer version of the app.
     */
    @Test
    fun changesThisBuildCannotReadAreCountedNotListed() {
        val timeline =
            HistoryMapper.toTimeline(
                listOf(
                    record(HistoryEntry.Unknown("future-op", "{}")),
                    record(HistoryEntry.TagAdded("invoice")),
                    record(HistoryEntry.Undecodable("other-op")),
                ),
            )

        timeline.items.map { it.description } shouldContainExactly
            listOf(uiText(Res.string.detail_history_tag_added, "invoice"))
        timeline.newerChanges shouldBe 2
        timeline.isEmpty shouldBe false
        HistoryMapper.toTimeline(listOf(record(HistoryEntry.Undecodable("op")))).isEmpty shouldBe false
        HistoryMapper.toTimeline(emptyList()).isEmpty shouldBe true
    }

    @Test
    fun clipsLongFieldValues() {
        val long = "x".repeat(200)
        val items = HistoryMapper.toTimeline(listOf(record(HistoryEntry.FieldSet(FieldKeys.NOTES, long)))).items
        val shown = (items.single().description as UiText.Res).args[1] as String
        shown.length shouldBe 80
        shown.endsWith("\u2026") shouldBe true
    }
}
