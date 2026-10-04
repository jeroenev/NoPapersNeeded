package dev.npn.core.app

import dev.npn.core.journal.JournalOp
import dev.npn.core.model.SystemProperty

/**
 * One change in a document's history: when it was made ([timeMillis], the wall time of the device
 * that made it), on which device, and what it was. Records come in the journal's causal order.
 */
data class DocumentHistoryRecord(
    val timeMillis: Long,
    val deviceId: String,
    val entry: HistoryEntry,
)

/**
 * What one history record changed, as the domain names it. Built from the journal in core, so a
 * new journal op is mapped here (or arrives as [Unknown] in builds that do not know it) and the UI
 * never depends on the journal's wire types.
 */
sealed interface HistoryEntry {
    data class TagAdded(
        val tag: String,
    ) : HistoryEntry

    data class TagRemoved(
        val tag: String,
    ) : HistoryEntry

    /** Marked reviewed (true) or as needing review (false), or the mark cleared (null). */
    data class Reviewed(
        val reviewed: Boolean?,
    ) : HistoryEntry

    /** A review mark this build cannot read, kept as written. */
    data class ReviewUnreadable(
        val value: String,
    ) : HistoryEntry

    /** The OCR engine whose text is searched pinned to [engine], or the pin cleared (null). */
    data class OcrEnginePinned(
        val engine: String?,
    ) : HistoryEntry

    /** The document's storage path set to [path], or cleared (null). */
    data class StoragePathSet(
        val path: String?,
    ) : HistoryEntry

    /** The field under [key] set to [value], or cleared (null). */
    data class FieldSet(
        val key: String,
        val value: String?,
    ) : HistoryEntry

    data class Trashed(
        val path: String,
    ) : HistoryEntry

    /** Out of the trash, or back after a permanent delete. */
    data object Restored : HistoryEntry

    data object Deleted : HistoryEntry

    /** The document's identity moved to [newDocId] (its bytes changed, as a bake does). */
    data class Relinked(
        val newDocId: String,
    ) : HistoryEntry

    data class TagRenamed(
        val from: String,
        val to: String,
    ) : HistoryEntry

    data class TagMerged(
        val from: String,
        val to: String,
    ) : HistoryEntry

    data class TagStyled(
        val tag: String,
    ) : HistoryEntry

    /** A library-wide setting or field definition, should one ever be recorded against a document. */
    data class SettingChanged(
        val key: String,
    ) : HistoryEntry

    /** An event of a kind this build does not know, named [operation], with its [payload] if it has one. */
    data class Unknown(
        val operation: String,
        val payload: String?,
    ) : HistoryEntry

    /** An event that could not be read at all; [operation] is the kind it was stored under. */
    data class Undecodable(
        val operation: String,
    ) : HistoryEntry

    companion object {
        /** The entry a journal op stands for. */
        internal fun of(op: JournalOp): HistoryEntry =
            when (op) {
                is JournalOp.TagAdd -> TagAdded(op.tag)
                is JournalOp.TagRemove -> TagRemoved(op.tag)
                is JournalOp.PropSet -> ofProperty(op.key, op.value)
                is JournalOp.FieldSet -> FieldSet(op.key, op.value)
                is JournalOp.DocTrash -> Trashed(op.origPath)
                JournalOp.DocRestore, JournalOp.DocUndelete -> Restored
                JournalOp.DocDelete -> Deleted
                is JournalOp.Relink -> Relinked(op.newDoc)
                is JournalOp.TagRename -> TagRenamed(op.from, op.to)
                is JournalOp.TagMerge -> TagMerged(op.from, op.to)
                is JournalOp.TagStyle -> TagStyled(op.tag)
                is JournalOp.SettingSet -> SettingChanged(op.key)
                is JournalOp.FieldDefSet -> SettingChanged(op.key)
                is JournalOp.Unknown -> Unknown(op.wire, op.payload.takeIf { it.isNotEmpty() }?.toString())
            }

        private fun ofProperty(
            key: SystemProperty,
            value: String?,
        ): HistoryEntry =
            when (key) {
                SystemProperty.REVIEWED -> {
                    when (value) {
                        null -> Reviewed(null)
                        else -> value.toBooleanStrictOrNull()?.let(::Reviewed) ?: ReviewUnreadable(value)
                    }
                }

                SystemProperty.OCR_ENGINE -> {
                    OcrEnginePinned(value)
                }

                SystemProperty.STORAGE_PATH -> {
                    StoragePathSet(value)
                }
            }
    }
}
