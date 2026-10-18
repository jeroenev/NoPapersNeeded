package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.app.recreatable
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditRunReview
import dev.npn.core.app.aiedit.ReviewChip
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.app.aiedit.ReviewField
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_review_discard_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_review_open_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_review_queue_drop_failed
import kotlin.test.Test

/**
 * A stored row as the review screen reopens it: one tag chip and a title value, both selected unless
 * a test says otherwise. Unlike [reviewDoc], no pre-select policy decides.
 */
private fun selectedReviewDoc(
    docId: String,
    tag: String = "invoice",
    tagSelected: Boolean = true,
    fieldSelected: Boolean = true,
) = ReviewDoc(
    docId = docId,
    title = null,
    fileName = "$docId.pdf",
    snippet = null,
    chips = listOf(ReviewChip(tag, isNew = false, selected = tagSelected)),
    removals = emptyList(),
    fields =
        listOf(
            ReviewField(
                key = FieldKeys.TITLE,
                label = "Title",
                value = "Proposed",
                current = null,
                selected = fieldSelected,
            ),
        ),
)

/** Serves a scripted review and records what the viewmodel asked it to do. */
private class ScriptedReview(
    override val jobId: String = "job",
    override val docs: List<ReviewDoc> = listOf(selectedReviewDoc("a")),
    override val emptyDocs: List<ReviewDoc> = emptyList(),
    override val failures: Int = 0,
    private val outcome: ApplyOutcome = ApplyOutcome.Done(ApplyResult(2, 1, moreRemain = false)),
    private val discardFailure: Throwable? = null,
    /** What [landedSoFar] answers. */
    private val landed: ApplyResult = ApplyResult(0, 0, moreRemain = false),
) : EditRunReview {
    val applied = mutableListOf<List<ReviewDoc>>()
    var discarded = false

    override suspend fun apply(reviewed: List<ReviewDoc>): ApplyOutcome {
        applied += reviewed
        return outcome
    }

    override suspend fun discard() {
        discardFailure?.let { throw it }
        discarded = true
    }

    override fun landedSoFar(): ApplyResult = landed
}

private fun sessionWith(review: EditRunReview) =
    FakeLibrarySession().apply { aiEditQueue.reviews[review.jobId] = review }

class AiEditReviewViewModelTest : MainDispatcherTest() {
    /** A restored process reopens the run with what the user had ticked and unticked, not the stored selection. */
    @Test
    fun aRestoredProcessKeepsTheReviewAsTheUserTickedIt() =
        runTest(dispatcher) {
            val review = ScriptedReview(docs = listOf(selectedReviewDoc("a"), selectedReviewDoc("b", tag = "tax")))
            val session = sessionWith(review)
            val screen = recreatable { AiEditReviewViewModel(session, "job", it) }
            advanceUntilIdle()
            screen.vm.toggleChip("a", "invoice")
            screen.vm.toggleField("b", FieldKeys.TITLE)

            val restored = screen.recreate(this, { it.phase })
            val phase = restored.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            phase.batch.docs.map { doc -> doc.chips.single().selected to doc.fields.single().selected } shouldBe
                listOf(false to true, true to false)
        }

    @Test
    fun theStoredSuggestionsAreLoadedIntoAReviewPhase() =
        runTest(dispatcher) {
            val review = ScriptedReview(docs = listOf(selectedReviewDoc("a"), selectedReviewDoc("b")))
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()

            val phase = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            phase.batch.docs.map { it.docId } shouldContainExactly listOf("a", "b")
        }

    @Test
    fun aRunThatIsGoneReportsItselfRatherThanShowingAnEmptyReview() =
        runTest(dispatcher) {
            val model = AiEditReviewViewModel(FakeLibrarySession(), "job")
            collecting(model.phase)
            advanceUntilIdle()

            model.phase.value shouldBe QueuedReviewPhase.Unavailable
        }

    @Test
    fun aRunThatCannotBeOpenedSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { aiEditQueue.openFailure = IllegalStateException("database locked") }
            val model = AiEditReviewViewModel(session, "job")
            collecting(model.phase)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.phase.value shouldBe QueuedReviewPhase.Unavailable
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_review_open_failed, "database locked"))
        }

    @Test
    fun aFailedDiscardSaysSoAndKeepsTheReview() =
        runTest(dispatcher) {
            val review = ScriptedReview(discardFailure = IllegalStateException("disk full"))
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.leaveReview()
            advanceUntilIdle()

            model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_review_discard_failed, "disk full"))
        }

    @Test
    fun onlySelectedSuggestionsSurviveIntoTheApply() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()

            model.toggleField("a", FieldKeys.TITLE)
            model.apply()
            advanceUntilIdle()

            val applied = review.applied.single().single()
            applied.chips.single().selected shouldBe true
            applied.fields.single().selected shouldBe false
        }

    @Test
    fun aFullyAppliedRunEndsInDone() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            val done = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Done>()
            done.result.applied shouldBe 2
            done.result.moreRemain shouldBe false
        }

    /**
     * Once the writes landed the run is applied, even when it then cannot leave the queue: the page
     * shows Done with what was written and says the run could not be removed from the queue, and
     * there is no second Apply to write it all again.
     */
    @Test
    fun anAppliedRunThatCannotLeaveTheQueueIsDoneAndSaysSo() =
        runTest(dispatcher) {
            val result = ApplyResult(2, 1, moreRemain = false)
            val failed = IllegalStateException("database closed")
            val review = ScriptedReview(outcome = ApplyOutcome.Done(result, followUpFailure = failed))
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            model.phase.value shouldBe QueuedReviewPhase.Done(result)
            messages shouldContainExactly
                listOf(failure(Res.string.ai_edit_review_queue_drop_failed, "database closed"))
            review.applied.size shouldBe 1
        }

    /** A partly failed Apply goes back to the Review with the rows still owed (see [ReviewControllerTest]). */
    @Test
    fun aPartialFailureGoesBackToReviewWithOnlyTheFailedDocuments() =
        runTest(dispatcher) {
            val retryRow = selectedReviewDoc("b", tagSelected = false)
            val review =
                ScriptedReview(
                    docs = listOf(selectedReviewDoc("a"), selectedReviewDoc("b")),
                    outcome = ApplyOutcome.Retry(listOf(retryRow), failedDocs = 1, total = 2),
                )
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            val phase = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            phase.batch.docs.map { it.docId } shouldContainExactly listOf("b")
        }

    /**
     * Discarding the run after an Apply that partly landed: those writes stand, so Done reports them
     * rather than that no document was changed.
     */
    @Test
    fun discardingAfterAPartlyFailedApplyReportsWhatLanded() =
        runTest(dispatcher) {
            val landed = ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
            val titleOwed = selectedReviewDoc("a", tagSelected = false)
            val review =
                ScriptedReview(
                    docs = listOf(selectedReviewDoc("a")),
                    outcome = ApplyOutcome.Retry(listOf(titleOwed), failedDocs = 1, total = 1),
                    landed = landed,
                )
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            model.leaveReview()
            advanceUntilIdle()

            review.discarded shouldBe true
            model.phase.value shouldBe QueuedReviewPhase.Done(landed)
        }

    @Test
    fun skippingDiscardsTheRunWithoutWriting() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            collecting(model.phase)
            advanceUntilIdle()

            model.leaveReview()
            advanceUntilIdle()

            review.discarded shouldBe true
            review.applied shouldBe emptyList()
            model.phase.value
                .shouldBeInstanceOf<QueuedReviewPhase.Done>()
                .result.applied shouldBe 0
        }
}
