package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry

/**
 * UI-facing kind of a document history event. Kept separate from the icon choice so tests can
 * assert against stable enum values instead of [androidx.compose.ui.graphics.vector.ImageVector]
 * identity.
 */
enum class HistoryEventKind {
    TAG_ADD,
    TAG_REMOVE,
    PROP_REVIEWED,
    PROP_OCR_ENGINE,
    PROP_STORAGE_PATH,
    FIELD_SET,
    FIELD_REMOVE,
    TRASH,
    RESTORE,
    DELETE,
    RELINK,
    UNKNOWN,
}

/**
 * One human-readable entry in the document history timeline.
 *
 * @property timeMillis When the change was made, on the clock of the device that made it.
 * @property kind Stable classifier for iconography and tests.
 * @property description Localized sentence describing the change.
 * @property deviceId Authoring device for this event.
 */
data class HistoryItem(
    val timeMillis: Long,
    val kind: HistoryEventKind,
    val description: HistoryDescription,
    val deviceId: String,
)

data class HistoryDescription(
    val key: HistoryTextKey,
    val arguments: List<String> = emptyList(),
)

enum class HistoryTextKey {
    UNDECODABLE,
    TAG_ADDED,
    TAG_REMOVED,
    MARKED_REVIEWED,
    MARKED_NEEDS_REVIEW,
    REVIEW_STATE_CLEARED,
    REVIEW_STATE_CHANGED,
    OCR_ENGINE_CLEARED,
    OCR_ENGINE_PINNED,
    STORAGE_PATH_SET,
    STORAGE_PATH_CLEARED,
    FIELD_REMOVED,
    FIELD_SET,
    DOCUMENT_TRASHED,
    DOCUMENT_RESTORED,
    DOCUMENT_DELETED,
    DOCUMENT_RELINKED,
    TAG_RENAMED,
    TAG_MERGED,
    TAG_STYLED,
    SETTING_CHANGED,
    UNKNOWN_EVENT,
    UNKNOWN_EVENT_WITH_PAYLOAD,
}

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
                kind = kind(record.entry),
                description = description(record.entry, fieldLabel),
                deviceId = record.deviceId,
            )
        }

    private fun description(
        entry: HistoryEntry,
        fieldLabel: (String) -> String,
    ): HistoryDescription =
        when (entry) {
            is HistoryEntry.TagAdded -> historyText(HistoryTextKey.TAG_ADDED, entry.tag)
            is HistoryEntry.TagRemoved -> historyText(HistoryTextKey.TAG_REMOVED, entry.tag)
            is HistoryEntry.Reviewed -> reviewedText(entry.reviewed)
            is HistoryEntry.ReviewUnreadable -> historyText(HistoryTextKey.REVIEW_STATE_CHANGED, entry.value)
            is HistoryEntry.OcrEnginePinned -> ocrEngineText(entry.engine)
            is HistoryEntry.StoragePathSet -> storagePathText(entry.path)
            is HistoryEntry.FieldSet -> fieldText(fieldLabel(entry.key), entry.value)
            is HistoryEntry.Trashed -> historyText(HistoryTextKey.DOCUMENT_TRASHED, entry.path)
            HistoryEntry.Restored -> historyText(HistoryTextKey.DOCUMENT_RESTORED)
            HistoryEntry.Deleted -> historyText(HistoryTextKey.DOCUMENT_DELETED)
            is HistoryEntry.Relinked -> historyText(HistoryTextKey.DOCUMENT_RELINKED, shortId(entry.newDocId))
            is HistoryEntry.TagRenamed -> historyText(HistoryTextKey.TAG_RENAMED, entry.from, entry.to)
            is HistoryEntry.TagMerged -> historyText(HistoryTextKey.TAG_MERGED, entry.from, entry.to)
            is HistoryEntry.TagStyled -> historyText(HistoryTextKey.TAG_STYLED, entry.tag)
            is HistoryEntry.SettingChanged -> historyText(HistoryTextKey.SETTING_CHANGED, entry.key)
            is HistoryEntry.Unknown -> unknownText(entry)
            is HistoryEntry.Undecodable -> historyText(HistoryTextKey.UNDECODABLE, entry.operation)
        }

    private fun kind(entry: HistoryEntry): HistoryEventKind =
        when (entry) {
            is HistoryEntry.TagAdded -> {
                HistoryEventKind.TAG_ADD
            }

            is HistoryEntry.TagRemoved -> {
                HistoryEventKind.TAG_REMOVE
            }

            is HistoryEntry.Reviewed, is HistoryEntry.ReviewUnreadable -> {
                HistoryEventKind.PROP_REVIEWED
            }

            is HistoryEntry.OcrEnginePinned -> {
                HistoryEventKind.PROP_OCR_ENGINE
            }

            is HistoryEntry.StoragePathSet -> {
                HistoryEventKind.PROP_STORAGE_PATH
            }

            is HistoryEntry.FieldSet -> {
                if (entry.value == null) HistoryEventKind.FIELD_REMOVE else HistoryEventKind.FIELD_SET
            }

            is HistoryEntry.Trashed -> {
                HistoryEventKind.TRASH
            }

            HistoryEntry.Restored -> {
                HistoryEventKind.RESTORE
            }

            HistoryEntry.Deleted -> {
                HistoryEventKind.DELETE
            }

            is HistoryEntry.Relinked -> {
                HistoryEventKind.RELINK
            }

            is HistoryEntry.TagRenamed,
            is HistoryEntry.TagMerged,
            is HistoryEntry.TagStyled,
            is HistoryEntry.SettingChanged,
            is HistoryEntry.Unknown,
            is HistoryEntry.Undecodable,
            -> {
                HistoryEventKind.UNKNOWN
            }
        }

    private fun reviewedText(reviewed: Boolean?): HistoryDescription =
        when (reviewed) {
            true -> historyText(HistoryTextKey.MARKED_REVIEWED)
            false -> historyText(HistoryTextKey.MARKED_NEEDS_REVIEW)
            null -> historyText(HistoryTextKey.REVIEW_STATE_CLEARED)
        }

    private fun ocrEngineText(engine: String?): HistoryDescription =
        if (engine == null) {
            historyText(HistoryTextKey.OCR_ENGINE_CLEARED)
        } else {
            historyText(HistoryTextKey.OCR_ENGINE_PINNED, engine)
        }

    private fun storagePathText(path: String?): HistoryDescription =
        if (path == null) {
            historyText(HistoryTextKey.STORAGE_PATH_CLEARED)
        } else {
            historyText(HistoryTextKey.STORAGE_PATH_SET, path)
        }

    private fun unknownText(entry: HistoryEntry.Unknown): HistoryDescription =
        entry.payload?.let { historyText(HistoryTextKey.UNKNOWN_EVENT_WITH_PAYLOAD, entry.operation, it) }
            ?: historyText(HistoryTextKey.UNKNOWN_EVENT, entry.operation)

    /** One wording for every field: `label` cleared, or `label` set to a (possibly cut) value. */
    private fun fieldText(
        label: String,
        value: String?,
    ): HistoryDescription =
        if (value == null) {
            historyText(HistoryTextKey.FIELD_REMOVED, label)
        } else {
            historyText(HistoryTextKey.FIELD_SET, label, value.clipped())
        }

    private fun String.clipped(): String = if (length > MAX_VALUE_CHARS) take(MAX_VALUE_CHARS - 1) + "\u2026" else this

    private fun shortId(id: String): String = if (id.length > 8) "${id.take(8)}..." else id
}

private fun historyText(
    key: HistoryTextKey,
    vararg arguments: String,
) = HistoryDescription(key, arguments.toList())
