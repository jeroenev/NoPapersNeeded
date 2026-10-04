package dev.npn.app.ui.ai

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.AiEditQueueFacade
import dev.npn.core.app.ApplyOutcome
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

    /** Per-doc suggestion chips + field rows awaiting confirmation; nothing written yet. */
    data class Review(
        val docs: List<ReviewDoc>,
        val emptyDocs: List<ReviewDoc>,
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
 * [apply], or [skip]. Leaving the screen any other way keeps it, so a half-finished review costs
 * nothing.
 */
class AiEditReviewViewModel(
    private val queue: AiEditQueueFacade,
    private val jobId: String,
) : MessagesViewModel() {
    constructor(session: LibrarySession, jobId: String) : this(session.aiEditQueue, jobId)

    private val _phase = MutableStateFlow<QueuedReviewPhase>(QueuedReviewPhase.Loading)
    val phase: StateFlow<QueuedReviewPhase> = _phase.asStateFlow()

    /** The opened run, or null until it loads (or when it turned out not to be there). */
    private var review: EditRunReview? = null

    private var run: Job? = null

    init {
        viewModelScope.launch {
            val opened = runCatchingCancellable { queue.openEditRun(jobId) }.getOrNull()
            review = opened
            _phase.value =
                opened?.let { QueuedReviewPhase.Review(it.docs, it.emptyDocs) }
                    ?: QueuedReviewPhase.Unavailable
        }
    }

    fun toggleChip(
        docId: String,
        tag: String,
    ) = updateReview { it.toggleChip(docId, tag) }

    fun toggleField(
        docId: String,
        key: String,
    ) = updateReview { it.toggleField(docId, key) }

    fun setDocSelection(
        docId: String,
        selected: Boolean,
    ) = updateReview { it.setDocSelection(docId, selected) }

    fun setAllSelection(selected: Boolean) = updateReview { it.setAllSelected(selected) }

    /** Reset the batch to the pre-select policy; see [List.selectRecommended]. */
    fun selectRecommended() = updateReview { it.selectRecommended() }

    private fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>) =
        _phase.update { phase ->
            val current = phase as? QueuedReviewPhase.Review ?: return@update phase
            current.copy(docs = transform(current.docs))
        }

    /** Write the selected suggestions; a fully applied run leaves the queue. */
    fun apply() {
        val current = _phase.value as? QueuedReviewPhase.Review ?: return
        val opened = review ?: return
        if (run?.isActive == true) return
        run = viewModelScope.launch { applySelections(opened, current) }
    }

    /**
     * Leave without writing anything, discarding the run. This is the only way a reviewed run is
     * thrown away, so it is deliberately the same button the interactive flow uses to skip a
     * batch — there is no later batch here to carry the suggestions into.
     */
    fun skip() {
        val opened = review ?: return
        if (run?.isActive == true) return
        run =
            viewModelScope.launch {
                runCatchingCancellable { opened.discard() }
                _phase.value = QueuedReviewPhase.Done(ApplyResult(0, 0, moreRemain = false))
            }
    }

    private suspend fun applySelections(
        opened: EditRunReview,
        current: QueuedReviewPhase.Review,
    ) {
        _phase.value = QueuedReviewPhase.Applying
        when (val outcome = opened.apply(current.docs)) {
            ApplyOutcome.NothingSelected -> {
                _phase.value = current
            }

            is ApplyOutcome.Done -> {
                _phase.value = QueuedReviewPhase.Done(outcome.result)
            }

            is ApplyOutcome.Retry -> {
                message(
                    uiText(
                        UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS,
                        UiAction.TAG,
                        outcome.failedDocs,
                        outcome.total,
                    ),
                )
                _phase.value = current.copy(docs = outcome.remaining)
            }
        }
    }
}
