package dev.npn.app.ui.ai

import dev.npn.core.app.DocListItem
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.util.Sensitive.Kind.TAG
import dev.npn.core.util.sensitive

/**
 * What an Apply of a Review batch changed, kept as the writes that put it back: the tags it added
 * come off again, the tags it removed go back on, and each field it wrote gets the value it had
 * before. Only what the Apply wrote is touched, so an edit made elsewhere to anything else stands.
 *
 * Built from the batch's selected items, less what a Retry still owes ([landed]), so it holds only
 * what actually landed; [plus] gathers the attempts of one batch.
 */
data class ApplyUndo(
    /** Tags the Apply added, each with the documents it went onto. */
    val added: Map<String, Set<String>> = emptyMap(),
    /** Tags the Apply removed, each with the documents it came off. */
    val removed: Map<String, Set<String>> = emptyMap(),
    /** Per document, each field the Apply wrote and the value it had before (null when empty). */
    val fields: Map<String, Map<String, String?>> = emptyMap(),
) {
    /** The documents the Apply changed. */
    val docIds: Set<String>
        get() = added.values.flatten().toSet() + removed.values.flatten() + fields.keys

    val isEmpty: Boolean get() = docIds.isEmpty()

    operator fun plus(other: ApplyUndo): ApplyUndo =
        ApplyUndo(
            added = added.merged(other.added),
            removed = removed.merged(other.removed),
            fields =
                (fields.keys + other.fields.keys).associateWith {
                    fields[it].orEmpty() + other.fields[it].orEmpty()
                },
        )

    /**
     * The writes that put it back, at the grain Apply wrote: one per tag across its documents, one
     * per document for its fields, so the storage-path rule runs once per document.
     */
    fun steps(): List<UndoStep> =
        added.map { (tag, docIds) -> UndoStep.RemoveTag(tag, docIds.sorted()) } +
            removed.map { (tag, docIds) -> UndoStep.AddTag(tag, docIds.sorted()) } +
            fields.map { (docId, values) -> UndoStep.RestoreFields(docId, values) }

    companion object {
        val NONE = ApplyUndo()

        /**
         * What an Apply of [attempted] wrote, with the documents as they were just before it
         * ([before], by id; a document missing there falls back to the value the Review was
         * projected against). [remaining] is what a Retry still owes, selected exactly as it is
         * owed: everything selected in [attempted] and not in it landed. Null [remaining] means
         * everything landed.
         */
        fun landed(
            attempted: List<ReviewDoc>,
            remaining: List<ReviewDoc>?,
            before: Map<String, DocListItem>,
        ): ApplyUndo {
            val owed = remaining.orEmpty().associateBy { it.docId }
            val added = mutableMapOf<String, MutableSet<String>>()
            val removed = mutableMapOf<String, MutableSet<String>>()
            val fields = mutableMapOf<String, MutableMap<String, String?>>()
            for (doc in attempted) {
                val still = owed[doc.docId]
                // A tag the document already carried just before is no addition of the Apply's, and
                // one it no longer carried no removal: Undo must not take off or put back either.
                val carried = before[doc.docId]?.tags

                fun carries(tag: String) = carried?.any { it.equals(tag, ignoreCase = true) }
                doc.chips
                    .filter { it.selected && still?.chips?.any { c -> c.selected && c.tag == it.tag } != true }
                    .filter { carries(it.tag) != true }
                    .forEach { added.getOrPut(it.tag) { mutableSetOf() } += doc.docId }
                doc.removals
                    .filter { it.selected && still?.removals?.any { c -> c.selected && c.tag == it.tag } != true }
                    .filter { carries(it.tag) != false }
                    .forEach { removed.getOrPut(it.tag) { mutableSetOf() } += doc.docId }
                val stored = before[doc.docId]?.doc?.fields
                doc.fields
                    .filter { it.selected && still?.fields?.any { f -> f.selected && f.key == it.key } != true }
                    .forEach { field ->
                        val previous =
                            if (stored !=
                                null
                            ) {
                                stored[field.key]?.takeIf { it.isNotBlank() }
                            } else {
                                field.current
                            }
                        fields.getOrPut(doc.docId) { mutableMapOf() }[field.key] = previous
                    }
            }
            return ApplyUndo(added, removed, fields)
        }

        private fun Map<String, Set<String>>.merged(other: Map<String, Set<String>>): Map<String, Set<String>> =
            (keys + other.keys).associateWith { this[it].orEmpty() + other[it].orEmpty() }
    }
}

/**
 * One write of an [ApplyUndo]. A failed step is logged by its string (ReviewController's Undo), and
 * the log reaches the diagnostics bundle: each says what kind of write it is and how big, never a
 * tag, a field's name or a value it restores.
 */
sealed interface UndoStep {
    suspend fun run(meta: MetadataWriter)

    data class RemoveTag(
        val tag: String,
        val docIds: List<String>,
    ) : UndoStep {
        override suspend fun run(meta: MetadataWriter) = meta.removeTag(docIds, tag)

        override fun toString(): String = "RemoveTag(${sensitive(tag, TAG)}, documents=${docIds.size})"
    }

    data class AddTag(
        val tag: String,
        val docIds: List<String>,
    ) : UndoStep {
        override suspend fun run(meta: MetadataWriter) = meta.addTag(docIds, tag)

        override fun toString(): String = "AddTag(${sensitive(tag, TAG)}, documents=${docIds.size})"
    }

    /** A blank value clears the field, as [MetadataWriter.applyEdits] reads it. */
    data class RestoreFields(
        val docId: String,
        val values: Map<String, String?>,
    ) : UndoStep {
        override suspend fun run(meta: MetadataWriter) = meta.applyEdits(listOf(docId), fields = values)

        override fun toString(): String = "RestoreFields(docId=$docId, fields=${values.size})"
    }
}
