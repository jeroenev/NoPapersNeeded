package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.TagRepository
import dev.npn.core.util.runCatchingCancellable

/**
 * Turns one document's raw [AiEditSuggestion] into the [ReviewDoc] a human is shown, selected as
 * the **pre-select policy** ([ReviewDoc.recommended]) says.
 *
 * Shared by the interactive [AiEditRun] and by [AiEditQueue]'s stored runs precisely because a
 * queued run is projected *late*: its suggestion may have been produced hours earlier, so it is
 * decided against the document and the library as they are when the user opens the Review, not as
 * they were when the model was asked. A row offers only what would still change something: a tag
 * to add that the document does not carry, one to remove that it does (both in any case), a value
 * other than the field's current one; and a tag is new only when the library's vocabulary does not
 * hold it now. What an earlier Apply wrote is then not offered again.
 */
internal class ReviewProjector(
    private val content: DocContentFacade,
    private val fieldRegistry: FieldRegistry,
    private val tags: TagRepository,
) {
    suspend fun project(
        item: DocListItem,
        suggestion: AiEditSuggestion,
    ): ReviewDoc {
        // Labels are looked up at projection time so a renamed field reads by its current name.
        val labels = fieldRegistry.current().associate { it.key to it.label }
        val vocabulary = tags.allTagNames()
        return ReviewDoc(
            docId = item.doc.docId,
            title = item.doc.title,
            fileName = item.doc.fileName,
            snippet = snippetFor(item.doc.docId),
            chips =
                suggestion.addTags
                    .filter { item.tags.spelling(it.tag) == null }
                    .map { suggested ->
                        val known = vocabulary.spelling(suggested.tag)
                        ReviewChip(known ?: suggested.tag, isNew = known == null, selected = false)
                    },
            removals =
                suggestion.removeTags
                    .mapNotNull { item.tags.spelling(it) }
                    .distinct()
                    .map { ReviewChip(it, isNew = false, selected = false) },
            fields =
                suggestion.metadata.values
                    // Canonical values compare as values: an equal one would change nothing.
                    .filter { (key, value) -> item.doc.fields[key] != value }
                    .map { (key, value) ->
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

    /** How [tag] is spelled among these tags, matched in any case; null when none of them is it. */
    private fun List<String>.spelling(tag: String): String? = firstOrNull { it.equals(tag, ignoreCase = true) }

    /** First ~[SNIPPET_LENGTH] chars of extracted text, whitespace-collapsed; null when unavailable. */
    private suspend fun snippetFor(docId: String): String? =
        content
            .extractedText(docId)
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
 * A failure is recorded, never rolled back: the Retry holds only the failed documents, without the
 * items that did land, and whatever a later attempt selects, an item that landed is not written
 * again, so the batch never writes a `(doc, tag)` or `(doc, field)` twice (a second tag add would be
 * another OR-set dot, a second field write a newer value over a remote edit).
 *
 * Stateful, one per batch: it carries what earlier partial-failure attempts wrote, so the Done that
 * finally comes counts all of them, suggestions and the distinct documents they changed alike.
 */
internal class ReviewApply(
    private val meta: MetadataWriter,
) {
    /** What every attempt of this batch wrote so far. */
    private val landed = Landed()

    /**
     * Write the selected items of [docs], less what an earlier attempt already wrote. [moreRemain] is
     * asked only once everything landed, for [ApplyResult.moreRemain]. The writes stand when it fails: the Done
     * offers no more and carries the failure.
     */
    suspend fun apply(
        docs: List<ReviewDoc>,
        moreRemain: suspend () -> Boolean,
    ): ApplyOutcome {
        val edits = docs.map { it.selectedEdit() }
        if (edits.all { it.isEmpty }) return ApplyOutcome.NothingSelected
        val written = write(edits)
        landed += written.landed
        if (written.failedDocs.isEmpty()) {
            val more = runCatchingCancellable { moreRemain() }
            val result = landedSoFar().copy(moreRemain = more.getOrDefault(false))
            return ApplyOutcome.Done(result, followUpFailure = more.exceptionOrNull())
        }
        return ApplyOutcome.Retry(
            remaining = docs.filter { it.docId in written.failedDocs }.map { it.withoutLanded() },
            failedDocs = written.failedDocs.size,
            total = docs.size,
        )
    }

    /**
     * What every attempt of this batch wrote so far: its suggestions and the distinct documents they
     * changed. What a Done reports also when the user leaves the rest of the batch, since those
     * writes stand. [ApplyResult.moreRemain] is false; only the caller can say otherwise.
     */
    fun landedSoFar(): ApplyResult = ApplyResult(landed.count, landed.changedDocs.size, moreRemain = false)

    /** One document's selected items: binary, unlike the Review's tri-state chips. */
    private class SelectedEdit(
        val docId: String,
        val addTags: List<String>,
        val removeTags: List<String>,
        val fields: Map<String, String?>,
    ) {
        val isEmpty: Boolean get() = addTags.isEmpty() && removeTags.isEmpty() && fields.isEmpty()
    }

    /** What landed, per `(docId, tag or field key)`. */
    private class Landed {
        val adds = mutableSetOf<Pair<String, String>>()
        val removals = mutableSetOf<Pair<String, String>>()
        val fields = mutableSetOf<Pair<String, String>>()

        val count: Int get() = adds.size + removals.size + fields.size
        val changedDocs: Set<String> get() = (adds + removals + fields).mapTo(HashSet()) { it.first }

        operator fun plusAssign(other: Landed) {
            adds += other.adds
            removals += other.removals
            fields += other.fields
        }
    }

    /** What one write pass landed, and which documents failed. */
    private class Written {
        val landed = Landed()
        val failedDocs = mutableSetOf<String>()
    }

    private suspend fun write(edits: List<SelectedEdit>): Written {
        val written = Written()
        for ((tag, docIds) in edits.byTag { it.addTags }) {
            writeTag(written.landed.adds, written, docIds, tag) { meta.addTag(docIds, tag) }
        }
        for ((tag, docIds) in edits.byTag { it.removeTags }) {
            writeTag(written.landed.removals, written, docIds, tag) { meta.removeTag(docIds, tag) }
        }
        for (edit in edits.filter { it.fields.isNotEmpty() }) {
            val landed = runCatchingCancellable { meta.applyEdits(listOf(edit.docId), fields = edit.fields) }.isSuccess
            if (landed) {
                edit.fields.keys.forEach { written.landed.fields += edit.docId to it }
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
        call: suspend () -> Unit,
    ) {
        if (runCatchingCancellable { call() }.isSuccess) {
            docIds.forEach { into += it to tag }
        } else {
            written.failedDocs += docIds
        }
    }

    private fun List<SelectedEdit>.byTag(tags: (SelectedEdit) -> List<String>): Map<String, List<String>> =
        flatMap { edit -> tags(edit).map { it to edit.docId } }.groupBy({ it.first }, { it.second })

    /** This row's selected items that no attempt of the batch has written yet. */
    private fun ReviewDoc.selectedEdit(): SelectedEdit =
        SelectedEdit(
            docId = docId,
            addTags = chips.filter { it.selected && (docId to it.tag) !in landed.adds }.map { it.tag },
            removeTags = removals.filter { it.selected && (docId to it.tag) !in landed.removals }.map { it.tag },
            fields =
                fields
                    .filter { it.selected && (docId to it.key) !in landed.fields }
                    .associate { it.key to it.value },
        )

    /**
     * The same row without the items that landed, so a retry offers only what is still owed: an item
     * left in it, even deselected, would be ticked again by selecting the recommended items or all.
     */
    private fun ReviewDoc.withoutLanded(): ReviewDoc =
        copy(
            chips = chips.filterNot { (docId to it.tag) in landed.adds },
            removals = removals.filterNot { (docId to it.tag) in landed.removals },
            fields = fields.filterNot { (docId to it.key) in landed.fields },
        )
}
