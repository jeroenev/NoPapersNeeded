package dev.npn.core.app

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.util.runCatchingCancellable

/**
 * Turns one document's raw [AiEditSuggestion] into the [ReviewDoc] a human is shown, selected as
 * the **pre-select policy** ([ReviewDoc.recommended]) says.
 *
 * Shared by the interactive [AiEditRun] and by [AiEditQueue]'s stored runs precisely because a
 * queued run is projected *late*: its suggestion may have been produced hours earlier, so the
 * "would this overwrite something" half of the policy has to be decided against the document as it
 * is when the user opens the Review, not as it was when the model was asked.
 */
internal class ReviewProjector(
    private val content: DocContentFacade,
    private val fieldRegistry: FieldRegistry,
) {
    suspend fun project(
        item: DocListItem,
        suggestion: AiEditSuggestion,
    ): ReviewDoc {
        // Labels are looked up at projection time so a renamed field reads by its current name.
        val labels = fieldRegistry.current().associate { it.key to it.label }
        return ReviewDoc(
            docId = item.doc.docId,
            title = item.doc.title,
            fileName = item.doc.fileName,
            snippet = snippetFor(item.doc.docId),
            chips = suggestion.addTags.map { ReviewChip(it.tag, it.isNew, selected = false) },
            removals = suggestion.removeTags.map { ReviewChip(it, isNew = false, selected = false) },
            fields =
                suggestion.metadata.values.map { (key, value) ->
                    ReviewField(
                        key = key,
                        label = labels[key] ?: key,
                        value = value,
                        // A blank value is no value: filling it replaces nothing.
                        current = item.doc.fields[key]?.takeIf { it.isNotBlank() },
                        selected = false,
                    )
                },
        ).recommended()
    }

    /** First ~[SNIPPET_LENGTH] chars of extracted text, whitespace-collapsed; null when unavailable. */
    private suspend fun snippetFor(docId: String): String? =
        runCatchingCancellable { content.extractedText(docId) }
            .getOrNull()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.take(SNIPPET_LENGTH)

    private companion object {
        /** Max characters of extracted text kept for a Review row's preview snippet. */
        const val SNIPPET_LENGTH = 200
    }
}

/** Whether the model suggested anything for this document; a row without is shown collapsed. */
internal val ReviewDoc.hasSuggestions: Boolean
    get() = chips.isNotEmpty() || removals.isNotEmpty() || fields.isNotEmpty()

/**
 * The Review → journal step of one reviewed batch, shared by the interactive [AiEditRun] and a
 * queued run opened from the processing queue, so the two cannot drift apart: project the reviewed
 * documents onto their *selected* items, write them, and rebuild a Retry from what landed.
 *
 * The write grain is the domain's (CONTEXT.md, "Apply"):
 *
 * - Tag additions and removals are grouped by distinct tag, one [MetadataWriter.addTag] or
 *   [MetadataWriter.removeTag] per tag across all its documents, all-or-nothing per tag.
 * - Field values are per document: one [MetadataWriter.applyEdits] per document. A setter per field
 *   would re-run the storage path rules and rename the file once per field, and one transaction
 *   over every document would let one rejected document lose all the others' writes.
 *
 * A failure is recorded, never rolled back: the Retry holds only the failed documents, with every
 * item that did land deselected, so a retry never writes a `(doc, tag)` or `(doc, field)` twice.
 *
 * Stateful, one per batch: it carries what earlier partial-failure attempts wrote, so the Done that
 * finally comes counts all of them.
 */
internal class ReviewApply(
    private val meta: MetadataWriter,
) {
    /** Suggestions written across earlier partial-failure attempts of this batch. */
    private var appliedSoFar = 0

    /**
     * Write the selected items of [docs]. [onDocWritten] fires with each document's id as a write
     * covering it lands; [moreRemain] is asked only once everything landed, for [ApplyResult.moreRemain].
     * The writes stand when it fails: the Done offers no more and carries the failure.
     */
    suspend fun apply(
        docs: List<ReviewDoc>,
        onDocWritten: (String) -> Unit = {},
        moreRemain: suspend () -> Boolean,
    ): ApplyOutcome {
        val edits = docs.map { it.selectedEdit() }
        if (edits.all { it.isEmpty }) return ApplyOutcome.NothingSelected
        val written = write(edits, onDocWritten)
        if (written.failedDocs.isEmpty()) {
            val more = runCatchingCancellable { moreRemain() }
            val result = ApplyResult(written.count + appliedSoFar, written.changedDocs, more.getOrDefault(false))
            return ApplyOutcome.Done(result, followUpFailure = more.exceptionOrNull())
        }
        appliedSoFar += written.count
        return ApplyOutcome.Retry(
            remaining = docs.filter { it.docId in written.failedDocs }.map { it.withoutWritten(written) },
            failedDocs = written.failedDocs.size,
            total = docs.size,
        )
    }

    /** One document's selected items: binary, unlike the Review's tri-state chips. */
    private class SelectedEdit(
        val docId: String,
        val addTags: List<String>,
        val removeTags: List<String>,
        val fields: Map<String, String?>,
    ) {
        val isEmpty: Boolean get() = addTags.isEmpty() && removeTags.isEmpty() && fields.isEmpty()
    }

    /** What one write pass landed, per `(docId, tag or field key)`, and which documents failed. */
    private class Written {
        val adds = mutableSetOf<Pair<String, String>>()
        val removals = mutableSetOf<Pair<String, String>>()
        val fields = mutableSetOf<Pair<String, String>>()
        val failedDocs = mutableSetOf<String>()

        val count: Int get() = adds.size + removals.size + fields.size
        val changedDocs: Int get() = (adds + removals + fields).map { it.first }.toSet().size
    }

    private suspend fun write(
        edits: List<SelectedEdit>,
        onDocWritten: (String) -> Unit,
    ): Written {
        val written = Written()
        for ((tag, docIds) in edits.byTag { it.addTags }) {
            writeTag(written.adds, written, docIds, tag, onDocWritten) { meta.addTag(docIds, tag) }
        }
        for ((tag, docIds) in edits.byTag { it.removeTags }) {
            writeTag(written.removals, written, docIds, tag, onDocWritten) { meta.removeTag(docIds, tag) }
        }
        for (edit in edits.filter { it.fields.isNotEmpty() }) {
            val landed = runCatchingCancellable { meta.applyEdits(listOf(edit.docId), fields = edit.fields) }.isSuccess
            if (landed) {
                edit.fields.keys.forEach { written.fields += edit.docId to it }
                onDocWritten(edit.docId)
            } else {
                written.failedDocs += edit.docId
            }
        }
        return written
    }

    /** One grouped tag call: every `(doc, tag)` of it lands, or all of its documents failed. */
    private suspend fun writeTag(
        into: MutableSet<Pair<String, String>>,
        written: Written,
        docIds: List<String>,
        tag: String,
        onDocWritten: (String) -> Unit,
        call: suspend () -> Unit,
    ) {
        if (runCatchingCancellable { call() }.isSuccess) {
            docIds.forEach { docId ->
                into += docId to tag
                onDocWritten(docId)
            }
        } else {
            written.failedDocs += docIds
        }
    }

    private fun List<SelectedEdit>.byTag(tags: (SelectedEdit) -> List<String>): Map<String, List<String>> =
        flatMap { edit -> tags(edit).map { it to edit.docId } }.groupBy({ it.first }, { it.second })

    private fun ReviewDoc.selectedEdit(): SelectedEdit =
        SelectedEdit(
            docId = docId,
            addTags = chips.filter { it.selected }.map { it.tag },
            removeTags = removals.filter { it.selected }.map { it.tag },
            fields = fields.filter { it.selected }.associate { it.key to it.value },
        )

    /**
     * The same row with every item [written] confirms deselected, so retrying this document
     * re-writes only what is still owed. Items are deselected rather than dropped: a written
     * suggestion is still part of what the user was shown and reads as "already done".
     */
    private fun ReviewDoc.withoutWritten(written: Written): ReviewDoc =
        copy(
            chips = chips.map { it.deselectIf((docId to it.tag) in written.adds) },
            removals = removals.map { it.deselectIf((docId to it.tag) in written.removals) },
            fields = fields.map { it.deselectIf((docId to it.key) in written.fields) },
        )

    private fun ReviewChip.deselectIf(written: Boolean): ReviewChip =
        if (selected && written) copy(selected = false) else this

    private fun ReviewField.deselectIf(written: Boolean): ReviewField =
        if (selected && written) copy(selected = false) else this
}
