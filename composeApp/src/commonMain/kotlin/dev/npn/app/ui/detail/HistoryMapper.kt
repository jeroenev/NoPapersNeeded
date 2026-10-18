package dev.npn.app.ui.detail

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.ocr.OcrEngineRef
import no_papers_needed.composeapp.generated.resources.Res
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
import no_papers_needed.composeapp.generated.resources.detail_history_review_state_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_setting_changed
import no_papers_needed.composeapp.generated.resources.detail_history_storage_path_cleared
import no_papers_needed.composeapp.generated.resources.detail_history_storage_path_set
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
import no_papers_needed.composeapp.generated.resources.detail_history_tag_merged
import no_papers_needed.composeapp.generated.resources.detail_history_tag_removed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_renamed
import no_papers_needed.composeapp.generated.resources.detail_history_tag_styled
import no_papers_needed.composeapp.generated.resources.detail_history_undecodable
import no_papers_needed.composeapp.generated.resources.detail_history_unknown_event
import no_papers_needed.composeapp.generated.resources.detail_history_unknown_event_payload

/**
 * One human-readable entry in the document history timeline.
 *
 * @property timeMillis When the change was made, on the clock of the device that made it.
 * @property entry The change itself, which the timeline picks the row's icon from.
 * @property description Sentence describing the change, resolved in the app's language.
 * @property deviceId Authoring device for this event.
 */
data class HistoryItem(
    val timeMillis: Long,
    val entry: HistoryEntry,
    val description: UiText,
    val deviceId: String,
)

/**
 * Maps a document's history into human-readable timeline items. The input is already in causal
 * order; the output preserves that order. [fieldLabel] resolves a field key to the registry label
 * the user knows it by; a key the registry no longer lists is shown as itself.
 */
object HistoryMapper {
    /** Longer field values are cut in the timeline; the field itself still holds the whole text. */
    private const val MAX_VALUE_CHARS = 80

    fun toHistoryItems(
        records: List<DocumentHistoryRecord>,
        fieldLabel: (String) -> String = { it },
    ): List<HistoryItem> =
        records.map { record ->
            HistoryItem(
                timeMillis = record.timeMillis,
                entry = record.entry,
                description = description(record.entry, fieldLabel),
                deviceId = record.deviceId,
            )
        }

    private fun description(
        entry: HistoryEntry,
        fieldLabel: (String) -> String,
    ): UiText =
        when (entry) {
            is HistoryEntry.TagAdded -> uiText(Res.string.detail_history_tag_added, entry.tag)
            is HistoryEntry.TagRemoved -> uiText(Res.string.detail_history_tag_removed, entry.tag)
            is HistoryEntry.Reviewed -> reviewedText(entry.reviewed)
            is HistoryEntry.ReviewUnreadable -> uiText(Res.string.detail_history_review_state_changed, entry.value)
            is HistoryEntry.OcrEnginePinned -> ocrEngineText(entry.engine)
            is HistoryEntry.StoragePathSet -> storagePathText(entry.path)
            is HistoryEntry.FieldSet -> fieldText(fieldLabel(entry.key), entry.value)
            is HistoryEntry.Trashed -> uiText(Res.string.detail_history_document_trashed, entry.path)
            HistoryEntry.Restored -> uiText(Res.string.detail_history_document_restored)
            HistoryEntry.Deleted -> uiText(Res.string.detail_history_document_deleted)
            is HistoryEntry.Relinked -> uiText(Res.string.detail_history_document_relinked, shortId(entry.newDocId))
            is HistoryEntry.TagRenamed -> uiText(Res.string.detail_history_tag_renamed, entry.from, entry.to)
            is HistoryEntry.TagMerged -> uiText(Res.string.detail_history_tag_merged, entry.from, entry.to)
            is HistoryEntry.TagStyled -> uiText(Res.string.detail_history_tag_styled, entry.tag)
            is HistoryEntry.SettingChanged -> uiText(Res.string.detail_history_setting_changed, entry.key)
            is HistoryEntry.Unknown -> unknownText(entry)
            is HistoryEntry.Undecodable -> uiText(Res.string.detail_history_undecodable, entry.operation)
        }

    private fun reviewedText(reviewed: Boolean?): UiText =
        when (reviewed) {
            true -> uiText(Res.string.detail_history_marked_reviewed)
            false -> uiText(Res.string.detail_history_marked_needs_review)
            null -> uiText(Res.string.detail_history_review_state_cleared)
        }

    private fun ocrEngineText(engine: OcrEngineRef?): UiText =
        if (engine == null) {
            uiText(Res.string.detail_history_ocr_engine_cleared)
        } else {
            uiText(Res.string.detail_history_ocr_engine_pinned, engineLabel(engine))
        }

    private fun storagePathText(path: String?): UiText =
        if (path == null) {
            uiText(Res.string.detail_history_storage_path_cleared)
        } else {
            uiText(Res.string.detail_history_storage_path_set, path)
        }

    private fun unknownText(entry: HistoryEntry.Unknown): UiText =
        entry.payload?.let { uiText(Res.string.detail_history_unknown_event_payload, entry.operation, it) }
            ?: uiText(Res.string.detail_history_unknown_event, entry.operation)

    /** One wording for every field: `label` cleared, or `label` set to a (possibly cut) value. */
    private fun fieldText(
        label: String,
        value: String?,
    ): UiText =
        if (value == null) {
            uiText(Res.string.detail_history_field_removed, label)
        } else {
            uiText(Res.string.detail_history_field_set, label, value.clipped())
        }

    private fun String.clipped(): String = if (length > MAX_VALUE_CHARS) take(MAX_VALUE_CHARS - 1) + "…" else this

    private fun shortId(id: String): String = if (id.length > 8) "${id.take(8)}..." else id
}
