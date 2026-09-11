package dev.npn.app.ui.ai

import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc

/**
 * What leaving a Review without applying actually does, which differs between the two flows and
 * must not be worded as if it did not: the interactive run keeps its scope and can suggest a next
 * batch, while a queued run is the only copy of an answer already paid for and is destroyed.
 */
internal enum class ReviewExit {
    /** Interactive run: this batch is skipped, its documents stay reviewed, the scope remains. */
    SKIP_BATCH,

    /** Queued run: the stored suggestions are discarded and the run leaves the processing queue. */
    DISCARD_RUN,
}

/**
 * Everything the user can do to a Review batch, bundled so the batch renders identically wherever
 * it appears: the interactive AI-edit run and a queued run opened from the processing queue drive
 * the same widgets and must not grow separate ones.
 */
internal data class ReviewActions(
    val onToggleChip: (String, String) -> Unit,
    val onToggleField: (docId: String, key: String) -> Unit,
    val onSetDoc: (String, Boolean) -> Unit,
    val onSetAll: (Boolean) -> Unit,
    val onSelectRecommended: () -> Unit,
    val onApply: () -> Unit,
    val onExit: () -> Unit,
    val onOpenDoc: (String) -> Unit,
    val exit: ReviewExit,
)

/**
 * Selection toggling over a Review batch — the tri-state half of the domain's pre-select policy,
 * as pure list transforms.
 *
 * Which suggestions a run *starts* selected is domain policy and lives in core; which ones the user
 * then ticks is presentation state, and both AI-edit viewmodels drive exactly the same widgets over
 * it. Keeping the transforms here rather than in either viewmodel is what stops the two from
 * drifting into subtly different rules for the same checkbox.
 */
internal fun List<ReviewDoc>.toggleChip(
    docId: String,
    tag: String,
): List<ReviewDoc> =
    mapDoc(docId) { doc ->
        // Tag additions and removals never share a tag, so one toggle covers both lists.
        doc.copy(chips = doc.chips.flipChip(tag), removals = doc.removals.flipChip(tag))
    }

internal fun List<ReviewDoc>.toggleField(
    docId: String,
    key: String,
): List<ReviewDoc> =
    mapDoc(docId) { doc ->
        doc.copy(fields = doc.fields.map { if (it.key == key) it.copy(selected = !it.selected) else it })
    }

/** Select or deselect every suggestion of one document. */
internal fun List<ReviewDoc>.setDocSelection(
    docId: String,
    selected: Boolean,
): List<ReviewDoc> = mapDoc(docId) { it.withAllSelected(selected) }

/** Select or deselect every suggestion of every document. */
internal fun List<ReviewDoc>.setAllSelected(selected: Boolean): List<ReviewDoc> = map { it.withAllSelected(selected) }

/**
 * Select exactly the suggestions that need no judgement — reusing a tag the library already has,
 * filling a field that is empty — and deselect the rest.
 *
 * This is the domain's own pre-select policy expressed as a transform, which is what makes it a
 * safe bulk action: "select all" also ticks the three kinds of suggestion the projector
 * deliberately left off (invented tags, tag removals, values that would replace something), so
 * offering it as *the* prominent shortcut quietly undoes the one guard rail this screen has.
 */
internal fun List<ReviewDoc>.selectRecommended(): List<ReviewDoc> =
    map { doc ->
        doc.copy(
            chips = doc.chips.map { it.copy(selected = !it.isNew) },
            removals = doc.removals.map { it.copy(selected = false) },
            fields = doc.fields.map { it.copy(selected = it.current == null) },
        )
    }

/**
 * How many suggestions in the batch are the kind a person has to decide about, so the header can
 * say so instead of leaving the user to notice one overwrite among two hundred rows.
 */
internal fun List<ReviewDoc>.attentionCount(): Int =
    sumOf { doc ->
        doc.chips.count { it.isNew } + doc.removals.size + doc.fields.count { it.current != null }
    }

private fun List<ReviewDoc>.mapDoc(
    docId: String,
    transform: (ReviewDoc) -> ReviewDoc,
): List<ReviewDoc> = map { if (it.docId == docId) transform(it) else it }

private fun List<ReviewChip>.flipChip(tag: String): List<ReviewChip> =
    map { if (it.tag == tag) it.copy(selected = !it.selected) else it }

private fun ReviewDoc.withAllSelected(selected: Boolean): ReviewDoc =
    copy(
        chips = chips.map { it.copy(selected = selected) },
        removals = removals.map { it.copy(selected = selected) },
        fields = fields.map { it.copy(selected = selected) },
    )
