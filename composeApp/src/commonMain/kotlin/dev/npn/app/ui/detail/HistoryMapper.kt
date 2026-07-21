package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.DocProperty

/**
 * UI-facing kind of a document history event. Kept separate from the icon choice so tests can
 * assert against stable enum values instead of [androidx.compose.ui.graphics.vector.ImageVector]
 * identity.
 */
enum class HistoryEventKind {
    TAG_ADD,
    TAG_REMOVE,
    PROP_TITLE,
    PROP_NOTES,
    PROP_CORRESPONDENT,
    PROP_DOC_TYPE,
    PROP_DOC_DATE,
    PROP_REVIEWED,
    PROP_OCR_ENGINE,
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
 * @property hlc Hybrid logical clock stamp used for causal ordering and display.
 * @property kind Stable classifier for iconography and tests.
 * @property description Localized sentence describing the change.
 * @property deviceId Authoring device for this event.
 */
data class HistoryItem(
    val hlc: Hlc,
    val kind: HistoryEventKind,
    val description: String,
    val deviceId: String,
)

/**
 * Maps decoded journal events into human-readable timeline items. The input is already ordered by
 * HLC; the output preserves that order.
 */
object HistoryMapper {
    fun toHistoryItems(records: List<DocumentHistoryRecord>): List<HistoryItem> =
        records.map { record ->
            record.event?.let(::toHistoryItem) ?: HistoryItem(
                hlc = record.hlc,
                kind = HistoryEventKind.UNKNOWN,
                description = "Could not decode journal event '${record.operation}'",
                deviceId = record.deviceId,
            )
        }

    private fun toHistoryItem(event: JournalEvent): HistoryItem {
        val op = event.op
        val kind = opKind(op)
        val description = when (op) {
            is JournalOp.TagAdd -> "Added tag '${op.tag}'"
            is JournalOp.TagRemove -> "Removed tag '${op.tag}'"
            is JournalOp.PropSet -> propDescription(op.key, op.value)
            is JournalOp.FieldSet -> fieldDescription(op.key, op.value)
            is JournalOp.DocTrash -> "Trashed document from '${op.origPath}'"
            JournalOp.DocRestore -> "Restored document"
            JournalOp.DocDelete -> "Permanently deleted document"
            is JournalOp.Relink -> "Relinked to document ${shortId(op.newDoc)}"
            is JournalOp.TagRename -> "Tag renamed from '${op.from}' to '${op.to}'"
            is JournalOp.TagMerge -> "Tag '${op.from}' merged into '${op.to}'"
            is JournalOp.TagStyle -> "Styled tag '${op.tag}'"
            is JournalOp.SettingSet -> "Changed setting '${op.key}'"
            is JournalOp.Unknown -> buildString {
                append("Unknown event '${op.wire}'")
                if (op.payload.isNotEmpty()) append(": ${op.payload}")
            }
        }
        return HistoryItem(
            hlc = event.hlc,
            kind = kind,
            description = description,
            deviceId = event.dev,
        )
    }

    private fun opKind(op: JournalOp): HistoryEventKind = when (op) {
        is JournalOp.TagAdd -> HistoryEventKind.TAG_ADD
        is JournalOp.TagRemove -> HistoryEventKind.TAG_REMOVE
        is JournalOp.PropSet -> when (op.key) {
            DocProperty.TITLE -> HistoryEventKind.PROP_TITLE
            DocProperty.NOTES -> HistoryEventKind.PROP_NOTES
            DocProperty.CORRESPONDENT -> HistoryEventKind.PROP_CORRESPONDENT
            DocProperty.DOC_TYPE -> HistoryEventKind.PROP_DOC_TYPE
            DocProperty.DOC_DATE -> HistoryEventKind.PROP_DOC_DATE
            DocProperty.REVIEWED -> HistoryEventKind.PROP_REVIEWED
            DocProperty.OCR_ENGINE -> HistoryEventKind.PROP_OCR_ENGINE
        }
        is JournalOp.FieldSet -> if (op.value == null) HistoryEventKind.FIELD_REMOVE else HistoryEventKind.FIELD_SET
        is JournalOp.DocTrash -> HistoryEventKind.TRASH
        JournalOp.DocRestore -> HistoryEventKind.RESTORE
        JournalOp.DocDelete -> HistoryEventKind.DELETE
        is JournalOp.Relink -> HistoryEventKind.RELINK
        else -> HistoryEventKind.UNKNOWN
    }

    private fun propDescription(key: DocProperty, value: String?): String = when (key) {
        DocProperty.TITLE -> if (value == null) "Cleared title" else "Changed title to '$value'"
        DocProperty.NOTES -> if (value == null) "Cleared note" else "Updated note"
        DocProperty.CORRESPONDENT -> if (value == null) {
            "Cleared correspondent"
        } else {
            "Changed correspondent to '$value'"
        }
        DocProperty.DOC_TYPE -> if (value == null) {
            "Cleared document type"
        } else {
            "Changed document type to '$value'"
        }
        DocProperty.DOC_DATE -> if (value == null) "Cleared document date" else "Changed document date to $value"
        DocProperty.REVIEWED -> when (value) {
            "true" -> "Marked reviewed"
            "false" -> "Marked needs review"
            else -> "Set review state to '${value ?: "cleared"}'"
        }
        DocProperty.OCR_ENGINE -> if (value == null) {
            "Cleared OCR engine pin"
        } else {
            "Pinned OCR engine to $value"
        }
    }

    private fun fieldDescription(key: String, value: String?): String =
        if (value == null) {
            "Removed field '$key'"
        } else {
            "Set field '$key' to '$value'"
        }

    private fun shortId(id: String): String = if (id.length > 8) "${id.take(8)}..." else id
}
