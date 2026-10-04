@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.resolveEnglish
import dev.npn.core.app.ApplyOutcome
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.EditRunReview
import dev.npn.core.app.ReviewChip
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.ReviewField
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private fun reviewDoc(
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
    override val docs: List<ReviewDoc> = listOf(reviewDoc("a")),
    override val emptyDocs: List<ReviewDoc> = emptyList(),
    override val failures: Int = 0,
    private val outcome: ApplyOutcome = ApplyOutcome.Done(ApplyResult(2, 1, moreRemain = false)),
    private val discardFailure: Throwable? = null,
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
}

private fun sessionWith(review: EditRunReview) =
    FakeLibrarySession().apply { aiEditQueue.reviews[review.jobId] = review }

class AiEditReviewViewModelTest : MainDispatcherTest() {
    @Test
    fun theStoredSuggestionsAreLoadedIntoAReviewPhase() =
        runTest(dispatcher) {
            val review = ScriptedReview(docs = listOf(reviewDoc("a"), reviewDoc("b")))
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()

            val phase = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            phase.docs.map { it.docId } shouldContainExactly listOf("a", "b")
            job.cancel()
        }

    @Test
    fun aRunThatIsGoneReportsItselfRatherThanShowingAnEmptyReview() =
        runTest(dispatcher) {
            val model = AiEditReviewViewModel(FakeLibrarySession(), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()

            model.phase.value shouldBe QueuedReviewPhase.Unavailable
            job.cancel()
        }

    @Test
    fun aRunThatCannotBeOpenedSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply { aiEditQueue.openFailure = IllegalStateException("database locked") }
            val model = AiEditReviewViewModel(session, "job")
            val job = launch { model.phase.collect {} }
            val messages = mutableListOf<String>()
            val messageJob = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()

            model.phase.value shouldBe QueuedReviewPhase.Unavailable
            messages shouldContainExactly listOf("Could not open the AI edit: database locked")
            job.cancel()
            messageJob.cancel()
        }

    @Test
    fun aFailedDiscardSaysSoAndKeepsTheReview() =
        runTest(dispatcher) {
            val review = ScriptedReview(discardFailure = IllegalStateException("disk full"))
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            val messages = mutableListOf<String>()
            val messageJob = launch { model.messages.collect { messages += it.resolveEnglish() } }
            advanceUntilIdle()

            model.skip()
            advanceUntilIdle()

            model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            messages shouldContainExactly listOf("Could not discard the AI edit: disk full")
            job.cancel()
            messageJob.cancel()
        }

    @Test
    fun onlySelectedSuggestionsSurviveIntoTheApply() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()

            model.toggleField("a", FieldKeys.TITLE)
            model.apply()
            advanceUntilIdle()

            val applied = review.applied.single().single()
            applied.chips.single().selected shouldBe true
            applied.fields.single().selected shouldBe false
            job.cancel()
        }

    @Test
    fun aFullyAppliedRunEndsInDone() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            val done = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Done>()
            done.result.applied shouldBe 2
            done.result.moreRemain shouldBe false
            job.cancel()
        }

    @Test
    fun aPartialFailureGoesBackToReviewWithOnlyTheFailedDocuments() =
        runTest(dispatcher) {
            val retryRow = reviewDoc("b", tagSelected = false)
            val review =
                ScriptedReview(
                    docs = listOf(reviewDoc("a"), reviewDoc("b")),
                    outcome = ApplyOutcome.Retry(listOf(retryRow), failedDocs = 1, total = 2),
                )
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            val messages = mutableListOf<Unit>()
            val messageJob = launch { model.messages.collect { messages += Unit } }
            advanceUntilIdle()

            model.apply()
            advanceUntilIdle()

            val phase = model.phase.value.shouldBeInstanceOf<QueuedReviewPhase.Review>()
            phase.docs.map { it.docId } shouldContainExactly listOf("b")
            messages.size shouldBe 1
            job.cancel()
            messageJob.cancel()
        }

    @Test
    fun nothingSelectedLeavesTheReviewExactlyAsItWas() =
        runTest(dispatcher) {
            val review = ScriptedReview(outcome = ApplyOutcome.NothingSelected)
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()
            val before = model.phase.value

            model.apply()
            advanceUntilIdle()

            model.phase.value shouldBe before
            job.cancel()
        }

    @Test
    fun skippingDiscardsTheRunWithoutWriting() =
        runTest(dispatcher) {
            val review = ScriptedReview()
            val model = AiEditReviewViewModel(sessionWith(review), "job")
            val job = launch { model.phase.collect {} }
            advanceUntilIdle()

            model.skip()
            advanceUntilIdle()

            review.discarded shouldBe true
            review.applied shouldBe emptyList()
            model.phase.value
                .shouldBeInstanceOf<QueuedReviewPhase.Done>()
                .result.applied shouldBe 0
            job.cancel()
        }
}
