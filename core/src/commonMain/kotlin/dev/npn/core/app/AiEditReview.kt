package dev.npn.core.app

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.util.runCatchingCancellable

/**
 * Turns one document's raw [AiEditSuggestion] into the [ReviewDoc] a human is shown, applying the
 * **pre-select policy** (CONTEXT.md, "Review"): existing-vocabulary tag additions are pre-selected,
 * new tags and every removal are pre-deselected, field fill-ins are pre-selected and overwrites are
 * pre-deselected.
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
            chips = suggestion.addTags.map { ReviewChip(it.tag, it.isNew, selected = !it.isNew) },
            removals = suggestion.removeTags.map { ReviewChip(it, isNew = false, selected = false) },
            fields =
                suggestion.metadata.values.map { (key, value) ->
                    reviewField(key, labels[key] ?: key, value, item.doc.fields[key])
                },
        )
    }

    /** Fill-ins (no current value) are pre-selected; overwrites require an explicit opt-in. */
    private fun reviewField(
        key: String,
        label: String,
        value: String,
        current: String?,
    ): ReviewField =
        ReviewField(
            key = key,
            label = label,
            value = value,
            current = current?.takeIf { it.isNotBlank() },
            selected = current.isNullOrBlank(),
        )

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

/** What one pass of [ReviewApply.apply] achieved. */
internal sealed interface ReviewOutcome {
    /** No suggestion was selected; nothing was written. */
    data object NothingSelected : ReviewOutcome

    /** Every selected suggestion was written. */
    data class Written(
        val applied: Int,
        val docsChanged: Int,
    ) : ReviewOutcome

    /**
     * Some documents failed. [remaining] holds only those, with their already-succeeded items
     * deselected, and [applied] counts what this pass did write, so a caller accumulating across
     * retries can carry it forward.
     */
    data class Retry(
        val remaining: List<ReviewDoc>,
        val failedDocs: Int,
        val total: Int,
        val applied: Int,
    ) : ReviewOutcome
}

/**
 * The Review → journal step: project reviewed documents onto their *selected* items, hand them to
 * [AiEditApply]'s batched grain, and rebuild the retry rows from what actually landed.
 *
 * This sits one level above [AiEditApply], which owns the write mechanics (per-tag transactions,
 * one `applyEdits` per document, failure as data). What lives here is the part that only a
 * *reviewed* run has: the selection projection and the guarantee that a retry never re-writes a
 * `(doc, tag)` or `(doc, field)` that already succeeded. Both reviewed flows — the interactive
 * [AiEditRun] and a queued run opened from the processing queue — need exactly that, so it is one
 * implementation rather than two that must stay in step.
 *
 * Run-level accounting (how many suggestions earlier retry attempts wrote, whether the scope has
 * more documents left) deliberately stays with the caller: it is what distinguishes the two flows.
 */
internal class ReviewApply(
    meta: MetadataWriter,
) {
    private val editApply = AiEditApply(meta)

    suspend fun apply(
        docs: List<ReviewDoc>,
        onDocWritten: (String) -> Unit = {},
    ): ReviewOutcome {
        val edits = docs.map { it.selectedEdit() }
        if (edits.all { it.isEmpty }) return ReviewOutcome.NothingSelected
        val report = editApply.apply(edits, onDocWritten)
        if (report.failedDocs.isEmpty()) {
            return ReviewOutcome.Written(report.appliedCount, report.changedDocs.size)
        }
        return ReviewOutcome.Retry(
            remaining = docs.filter { it.docId in report.failedDocs }.map { it.withoutWritten(report) },
            failedDocs = report.failedDocs.size,
            total = docs.size,
            applied = report.appliedCount,
        )
    }

    private fun ReviewDoc.selectedEdit(): WritableEdit =
        WritableEdit(
            docId = docId,
            addTags = chips.filter { it.selected }.map { it.tag },
            removeTags = removals.filter { it.selected }.map { it.tag },
            properties = fields.filter { it.selected }.associate { it.key to it.value },
        )

    /**
     * The same row with every item [report] confirms was written deselected, so retrying this
     * document re-writes only what is still owed. Items are deselected rather than dropped: a
     * written suggestion is still part of what the user was shown and reads as "already done".
     */
    private fun ReviewDoc.withoutWritten(report: ApplyReport): ReviewDoc =
        copy(
            chips = chips.map { it.deselectIf((docId to it.tag) in report.addsWritten) },
            removals = removals.map { it.deselectIf((docId to it.tag) in report.removalsWritten) },
            fields = fields.map { it.deselectIf((docId to it.key) in report.fieldsWritten) },
        )

    private fun ReviewChip.deselectIf(written: Boolean): ReviewChip =
        if (selected && written) copy(selected = false) else this

    private fun ReviewField.deselectIf(written: Boolean): ReviewField =
        if (selected && written) copy(selected = false) else this
}
