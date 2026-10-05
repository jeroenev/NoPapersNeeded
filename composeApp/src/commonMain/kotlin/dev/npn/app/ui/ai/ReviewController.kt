package dev.npn.app.ui.ai

import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc

/**
 * What leaving a Review without applying actually does, which differs between the two flows and
 * must not be worded as if it did not: the interactive run keeps its scope and can suggest a next
 * batch, while a queued run is the only copy of an answer already paid for and is destroyed.
 */
enum class ReviewExit {
    /** Interactive run: this batch is skipped, its documents stay reviewed, the scope remains. */
    SKIP_BATCH,

    /** Queued run: the stored suggestions are discarded and the run leaves the processing queue. */
    DISCARD_RUN,
}

/**
 * Everything the user can do to a Review batch. Both AI-edit viewmodels implement it, the
 * interactive run and a queued run opened from the processing queue, so the batch renders and
 * behaves identically wherever it appears.
 */
interface ReviewController {
    /** What [leaveReview] does to the suggestions. */
    val reviewExit: ReviewExit

    /** Tag additions and removals never share a tag, so one toggle covers both lists. */
    fun toggleChip(
        docId: String,
        tag: String,
    )

    fun toggleField(
        docId: String,
        key: String,
    )

    /** Select or deselect every suggestion of one document. */
    fun setDocSelection(
        docId: String,
        selected: Boolean,
    )

    /** Select or deselect every suggestion of every document. */
    fun setAllSelection(selected: Boolean)

    /** Reset the batch to the domain's pre-select policy; see [selectRecommended]. */
    fun selectRecommended()

    /** Write the selected suggestions. */
    fun apply()

    /** Leave the Review without writing anything; see [reviewExit]. */
    fun leaveReview()
}

/**
 * The selection half of [ReviewController], written once for both AI-edit viewmodels over the
 * batch that [updateReview] reaches.
 */
abstract class ReviewViewModel :
    MessagesViewModel(),
    ReviewController {
    /** Apply [transform] to the batch under review; a no-op while there is none. */
    protected abstract fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>)

    final override fun toggleChip(
        docId: String,
        tag: String,
    ) = updateReview { it.toggleChip(docId, tag) }

    final override fun toggleField(
        docId: String,
        key: String,
    ) = updateReview { it.toggleField(docId, key) }

    final override fun setDocSelection(
        docId: String,
        selected: Boolean,
    ) = updateReview { it.setDocSelection(docId, selected) }

    final override fun setAllSelection(selected: Boolean) = updateReview { it.setAllSelected(selected) }

    final override fun selectRecommended() = updateReview { it.selectRecommended() }
}

/**
 * Selection toggling over a Review batch, as pure list transforms.
 *
 * Which suggestions a run *starts* selected is domain policy and lives in core; which ones the user
 * then ticks is presentation state, the same for both AI-edit viewmodels ([ReviewViewModel]).
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
 * Reset every document to the domain's pre-select policy ([ReviewDoc.recommended]), which is what
 * makes it a safe bulk action: "select all" also ticks the three kinds of suggestion the policy
 * deliberately leaves off (invented tags, tag removals, values that would replace something), so
 * offering it as *the* prominent shortcut would quietly undo the one guard rail this screen has.
 */
internal fun List<ReviewDoc>.selectRecommended(): List<ReviewDoc> = map { it.recommended() }

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
