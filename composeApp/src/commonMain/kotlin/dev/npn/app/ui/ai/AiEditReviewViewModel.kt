package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.EditRunReview
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReviewDoc
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_review_discard_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_review_open_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_review_queue_drop_failed

/**
 * The phases of reviewing a run that was suggested in the background. Deliberately shorter than
 * [EditPhase]: the configuring and suggesting already happened, so this picks the flow up at
 * Review and ends at the same Done.
 */
sealed interface QueuedReviewPhase {
    /** Loading the stored suggestions and projecting them onto the documents as they are now. */
    data object Loading : QueuedReviewPhase

    /**
     * The run is not there any more: finished on another device, discarded, or opened while it was
     * still being suggested.
     */
    data object Unavailable : QueuedReviewPhase

    /** The [batch] under review, from the opened [run]; nothing written yet. */
    data class Review(
        val batch: ReviewBatchState,
        val run: EditRunReview,
    ) : QueuedReviewPhase

    /** Writing selected tags and data fields through the journal. */
    data object Applying : QueuedReviewPhase

    /** Terminal state carrying the counts of what was written. */
    data class Done(
        val result: ApplyResult,
    ) : QueuedReviewPhase
}

/**
 * Drives the Review of one background AI edit run.
 *
 * This is the second half of the AI-edit flow with the first half replaced: the suggestions were
 * bought earlier by [dev.npn.core.app.AiEditQueue] and only projected onto Review rows when this
 * screen opens, so what the user confirms is measured against the documents as they are *now*.
 * From there on the semantics are the interactive run's — same pre-select policy, same selection
 * toggles, same partial-failure Retry — because they are the same code
 * ([dev.npn.core.app.ReviewApply] and [toggleChip] and friends).
 *
 * The run is dropped from the queue exactly when it stops being reviewable: a fully applied
 * [apply], or [leaveReview]. Leaving the screen any other way keeps it, so a half-finished review costs
 * nothing.
 */
class AiEditReviewViewModel(
    session: LibrarySession,
    private val jobId: String,
) : ReviewViewModel() {
    private val queue = session.aiEditQueue

    private val _phase = MutableStateFlow<QueuedReviewPhase>(QueuedReviewPhase.Loading)
    val phase: StateFlow<QueuedReviewPhase> = _phase.asStateFlow()

    private var run: Job? = null

    init {
        viewModelScope.launch {
            val opened =
                runCatchingCancellable { queue.openEditRun(jobId) }
                    .onFailure { message(uiText(Res.string.ai_edit_review_open_failed, it.reasonText())) }
                    .getOrNull()
            _phase.value =
                opened?.let { QueuedReviewPhase.Review(ReviewBatchState(it.docs, it.emptyDocs), it) }
                    ?: QueuedReviewPhase.Unavailable
        }
    }

    /** Leaving destroys the only copy of an answer already paid for, so it is a discard. */
    override val reviewExit = ReviewExit.DISCARD_RUN

    override fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>) =
        _phase.update { phase ->
            val current = phase as? QueuedReviewPhase.Review ?: return@update phase
            current.copy(batch = current.batch.copy(docs = transform(current.batch.docs)))
        }

    /**
     * Write the selected suggestions; a fully applied run leaves the queue. A failed write comes back
     * as a Retry. Once every write landed the page shows Done, also when the run then could not leave
     * the queue: that is reported on its own, and the run stays queued. An Apply that throws is
     * reported, and the Review is back as it was.
     */
    override fun apply() {
        val current = _phase.value as? QueuedReviewPhase.Review ?: return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                _phase.value = QueuedReviewPhase.Applying
                applyReview(
                    current.batch,
                    Res.string.ai_edit_review_queue_drop_failed,
                    write = current.run::apply,
                    showReview = { _phase.value = current.copy(batch = it) },
                    showDone = { _phase.value = QueuedReviewPhase.Done(it) },
                )
            }
    }

    /**
     * Leave without writing anything more, discarding the run. This is the only way a reviewed run
     * is thrown away, so it is deliberately the same button the interactive flow uses to skip a
     * batch — there is no later batch here to carry the suggestions into. Done reports what an
     * earlier Apply that partly failed wrote, since that stands. A discard that fails says so and
     * stays on Review, since the run is still queued.
     */
    override fun leaveReview() {
        val opened = (_phase.value as? QueuedReviewPhase.Review)?.run ?: return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                reporting(Res.string.ai_edit_review_discard_failed) {
                    opened.discard()
                    _phase.value = QueuedReviewPhase.Done(opened.landedSoFar())
                }
            }
    }
}
