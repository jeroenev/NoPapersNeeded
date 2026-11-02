package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.BulkFailure
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.util.runLogged
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applied
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_finish_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_undo_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_undo_failed_some
import no_papers_needed.composeapp.generated.resources.ai_edit_undone
import no_papers_needed.composeapp.generated.resources.library_undo
import org.jetbrains.compose.resources.StringResource

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
 * One batch under Review: per-document suggestion chips and field rows awaiting confirmation
 * ([docs]), and the documents the model had nothing for ([emptyDocs]). Nothing is written yet,
 * unless an earlier Apply of the batch partly failed: [undo] is what that one wrote, which the
 * Undo offered once the batch is done puts back with the rest.
 */
data class ReviewBatchState(
    val docs: List<ReviewDoc>,
    val emptyDocs: List<ReviewDoc>,
    val undo: ApplyUndo = ApplyUndo.NONE,
)

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
 * batch that [updateReview] reaches, and the Undo of an Apply: [meta] writes it, from the
 * documents as [documents] reads them just before the Apply.
 */
abstract class ReviewViewModel(
    private val meta: MetadataWriter,
    private val documents: DocumentRepository,
) : MessagesViewModel(),
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

    /**
     * Write [batch]'s selected suggestions through [write] and show what that came to: Done with
     * what landed (a [ApplyOutcome.Done.followUpFailure] reported with [followUpFailed]) and an Undo
     * of all of it, or the Review again — what a Retry still owes, said as such, or the batch as it
     * was when nothing was selected or the Apply threw, which is reported.
     */
    protected suspend fun applyReview(
        batch: ReviewBatchState,
        followUpFailed: StringResource,
        write: suspend (List<ReviewDoc>) -> ApplyOutcome,
        showReview: (ReviewBatchState) -> Unit,
        showDone: (ApplyResult) -> Unit,
    ) {
        val before = snapshot(batch.docs)
        var outcome: ApplyOutcome? = null
        reporting(Res.string.ai_edit_apply_finish_failed) { outcome = write(batch.docs) }
        when (val applied = outcome) {
            null, ApplyOutcome.NothingSelected -> {
                showReview(batch)
            }

            is ApplyOutcome.Done -> {
                applied.followUpFailure?.let { message(uiText(followUpFailed, it.reasonText())) }
                showDone(applied.result)
                offerUndo(batch.undo + ApplyUndo.landed(batch.docs, remaining = null, before))
            }

            is ApplyOutcome.Retry -> {
                message(applied.failureText())
                val landed = ApplyUndo.landed(batch.docs, applied.remaining, before)
                showReview(batch.copy(docs = applied.remaining, undo = batch.undo + landed))
            }
        }
    }

    /**
     * The documents [docs] would write to, as they are before the Apply, for the field values an
     * Undo puts back. When they cannot be read, the values the Review was projected against stand in.
     */
    private suspend fun snapshot(docs: List<ReviewDoc>): Map<String, DocListItem> {
        val ids = docs.filter { it.selectedFieldCount > 0 }.map { it.docId }
        if (ids.isEmpty()) return emptyMap()
        return log
            .runLogged("Reading the documents before applying suggestions") {
                documents.liveDocuments(ids).associateBy { it.doc.docId }
            }.orEmpty()
    }

    /** Say how many documents the Apply changed, with an Undo that puts back what it wrote. */
    private fun offerUndo(undo: ApplyUndo) {
        if (undo.isEmpty) return
        val count = undo.docIds.size
        message(
            uiText(Res.plurals.ai_edit_applied, count, count),
            UiMessage.Action(uiText(Res.string.library_undo)) { undo(undo) },
        )
    }

    /** Put back what an Apply wrote; each part that fails is reported, and the rest still goes back. */
    private fun undo(undo: ApplyUndo) {
        viewModelScope.launch {
            val failed = forEachReporting(undo.steps(), UndoFailure) { it.run(meta) }
            if (failed.isEmpty()) {
                val count = undo.docIds.size
                message(uiText(Res.plurals.ai_edit_undone, count, count))
            }
        }
    }

    private companion object {
        val UndoFailure = BulkFailure(Res.string.ai_edit_undo_failed, Res.plurals.ai_edit_undo_failed_some)
    }
}

/** The tag additions and removals of this document that are ticked. */
internal val ReviewDoc.selectedTagCount: Int
    get() = chips.count { it.selected } + removals.count { it.selected }

/** The field values of this document that are ticked. */
internal val ReviewDoc.selectedFieldCount: Int
    get() = fields.count { it.selected }

/** Every ticked suggestion of this document. */
internal val ReviewDoc.selectedCount: Int
    get() = selectedTagCount + selectedFieldCount

/** Every suggestion of this document, ticked or not. */
internal val ReviewDoc.suggestionCount: Int
    get() = chips.size + removals.size + fields.size

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
