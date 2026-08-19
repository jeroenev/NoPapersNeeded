package dev.npn.app.ui.ai

import dev.npn.core.ai.AiField
import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc

/**
 * Everything the user can do to a Review batch, bundled so the batch renders identically wherever
 * it appears: the interactive AI-edit run and a queued run opened from the processing queue drive
 * the same widgets and must not grow separate ones.
 */
internal data class ReviewActions(
    val onToggleChip: (String, String) -> Unit,
    val onToggleField: (String, AiField) -> Unit,
    val onSetDoc: (String, Boolean) -> Unit,
    val onSetAll: (Boolean) -> Unit,
    val onApply: () -> Unit,
    val onSkip: () -> Unit,
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
    field: AiField,
): List<ReviewDoc> =
    mapDoc(docId) { doc ->
        doc.copy(fields = doc.fields.map { if (it.field == field) it.copy(selected = !it.selected) else it })
    }

/** Select or deselect every suggestion of one document. */
internal fun List<ReviewDoc>.setDocSelection(
    docId: String,
    selected: Boolean,
): List<ReviewDoc> = mapDoc(docId) { it.withAllSelected(selected) }

/** Select or deselect every suggestion of every document. */
internal fun List<ReviewDoc>.setAllSelected(selected: Boolean): List<ReviewDoc> = map { it.withAllSelected(selected) }

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
