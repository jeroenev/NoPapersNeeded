package dev.npn.app.ui.detail

import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.SystemProperty

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
 * Maps decoded journal events into human-readable timeline items. The input is already ordered by
 * HLC; the output preserves that order. [fieldLabel] resolves a field key to the registry label
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
            record.event?.let { toHistoryItem(it, fieldLabel) } ?: HistoryItem(
                hlc = record.hlc,
                kind = HistoryEventKind.UNKNOWN,
                description = historyText(HistoryTextKey.UNDECODABLE, record.operation),
                deviceId = record.deviceId,
            )
        }

    private fun toHistoryItem(
        event: JournalEvent,
        fieldLabel: (String) -> String,
    ): HistoryItem {
        val op = event.op
        val kind = opKind(op)
        val description =
            when (op) {
                is JournalOp.TagAdd -> {
                    historyText(HistoryTextKey.TAG_ADDED, op.tag)
                }

                is JournalOp.TagRemove -> {
                    historyText(HistoryTextKey.TAG_REMOVED, op.tag)
                }

                is JournalOp.PropSet -> {
                    propDescription(op.key, op.value)
                }

                is JournalOp.FieldSet -> {
                    fieldDescription(fieldLabel(op.key), op.value)
                }

                is JournalOp.DocTrash -> {
                    historyText(HistoryTextKey.DOCUMENT_TRASHED, op.origPath)
                }

                JournalOp.DocRestore -> {
                    historyText(HistoryTextKey.DOCUMENT_RESTORED)
                }

                JournalOp.DocDelete -> {
                    historyText(HistoryTextKey.DOCUMENT_DELETED)
                }

                is JournalOp.Relink -> {
                    historyText(HistoryTextKey.DOCUMENT_RELINKED, shortId(op.newDoc))
                }

                is JournalOp.TagRename -> {
                    historyText(HistoryTextKey.TAG_RENAMED, op.from, op.to)
                }

                is JournalOp.TagMerge -> {
                    historyText(HistoryTextKey.TAG_MERGED, op.from, op.to)
                }

                is JournalOp.TagStyle -> {
                    historyText(HistoryTextKey.TAG_STYLED, op.tag)
                }

                is JournalOp.SettingSet -> {
                    historyText(HistoryTextKey.SETTING_CHANGED, op.key)
                }

                is JournalOp.FieldDefSet -> {
                    // Library-wide; never appears in a document's own history, but the mapper is total.
                    historyText(HistoryTextKey.SETTING_CHANGED, op.key)
                }

                is JournalOp.Unknown -> {
                    if (op.payload.isEmpty()) {
                        historyText(HistoryTextKey.UNKNOWN_EVENT, op.wire)
                    } else {
                        historyText(HistoryTextKey.UNKNOWN_EVENT_WITH_PAYLOAD, op.wire, op.payload.toString())
                    }
                }
            }
        return HistoryItem(
            hlc = event.hlc,
            kind = kind,
            description = description,
            deviceId = event.dev,
        )
    }

    private fun opKind(op: JournalOp): HistoryEventKind =
        when (op) {
            is JournalOp.TagAdd -> {
                HistoryEventKind.TAG_ADD
            }

            is JournalOp.TagRemove -> {
                HistoryEventKind.TAG_REMOVE
            }

            is JournalOp.PropSet -> {
                when (op.key) {
                    SystemProperty.REVIEWED -> HistoryEventKind.PROP_REVIEWED
                    SystemProperty.OCR_ENGINE -> HistoryEventKind.PROP_OCR_ENGINE
                }
            }

            is JournalOp.FieldSet -> {
                if (op.value == null) HistoryEventKind.FIELD_REMOVE else HistoryEventKind.FIELD_SET
            }

            is JournalOp.DocTrash -> {
                HistoryEventKind.TRASH
            }

            JournalOp.DocRestore -> {
                HistoryEventKind.RESTORE
            }

            JournalOp.DocDelete -> {
                HistoryEventKind.DELETE
            }

            is JournalOp.Relink -> {
                HistoryEventKind.RELINK
            }

            else -> {
                HistoryEventKind.UNKNOWN
            }
        }

    private fun propDescription(
        key: SystemProperty,
        value: String?,
    ): HistoryDescription =
        when (key) {
            SystemProperty.REVIEWED -> {
                when (value) {
                    "true" -> historyText(HistoryTextKey.MARKED_REVIEWED)
                    "false" -> historyText(HistoryTextKey.MARKED_NEEDS_REVIEW)
                    null -> historyText(HistoryTextKey.REVIEW_STATE_CLEARED)
                    else -> historyText(HistoryTextKey.REVIEW_STATE_CHANGED, value)
                }
            }

            SystemProperty.OCR_ENGINE -> {
                if (value == null) {
                    historyText(HistoryTextKey.OCR_ENGINE_CLEARED)
                } else {
                    historyText(HistoryTextKey.OCR_ENGINE_PINNED, value)
                }
            }
        }

    /** One wording for every field: `label` cleared, or `label` set to a (possibly cut) value. */
    private fun fieldDescription(
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
