package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.core.app.aiedit.EditScope
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_queue_failed
import kotlin.test.Test

/**
 * Where a run executes. The default follows the scope size, an explicit choice overrides it, and a
 * backgrounded run hands over the WHOLE scope rather than one review-sized batch.
 */
class AiEditBackgroundTest : MainDispatcherTest() {
    private fun runOver(count: Int) =
        FakeAiEditRun().apply {
            inScope(*Array(count) { "d${it + 1}" })
            batch = review(reviewDoc("d1", tags = listOf("t")))
        }

    @Test
    fun aScopeOverTheThresholdIsQueuedByDefault() =
        runTest(dispatcher) {
            val run = runOver(AiEditLimits.BACKGROUND_THRESHOLD_DOCS + 1)
            val session = FakeLibrarySession()
            val model = vm(run, session, background = null)
            collecting(model.ui)
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            run.requests.shouldBeEmpty()
            session.aiEditQueue.queued
                .single()
                .first shouldHaveSize AiEditLimits.BACKGROUND_THRESHOLD_DOCS + 1
            model.ui.value.phase shouldBe EditPhase.Queued(AiEditLimits.BACKGROUND_THRESHOLD_DOCS + 1)
        }

    @Test
    fun aScopeAtOrUnderTheThresholdRunsHere() =
        runTest(dispatcher) {
            val run = runOver(AiEditLimits.BACKGROUND_THRESHOLD_DOCS)
            val session = FakeLibrarySession()
            val model = vm(run, session, background = null)
            collecting(model.ui)
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            session.aiEditQueue.queued.shouldBeEmpty()
            run.requests shouldHaveSize 1
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
        }

    @Test
    fun anExplicitChoiceBeatsTheSizeDefaultInBothDirections() =
        runTest(dispatcher) {
            val big = FakeLibrarySession()
            val foreground = vm(runOver(AiEditLimits.BACKGROUND_THRESHOLD_DOCS + 1), big, background = false)
            collecting(foreground.ui)
            advanceUntilIdle()
            foreground.setScope(EditScope.ALL)
            foreground.start()
            advanceUntilIdle()
            big.aiEditQueue.queued.shouldBeEmpty()

            val small = FakeLibrarySession()
            val smallRun = runOver(1)
            val background = vm(smallRun, small, background = true)
            collecting(background.ui)
            advanceUntilIdle()
            background.setScope(EditScope.ALL)
            background.start()
            advanceUntilIdle()
            smallRun.requests.shouldBeEmpty()
            small.aiEditQueue.queued shouldHaveSize 1
        }

    @Test
    fun aQueuedRunCoversTheWholeScopeNotOneBatch() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(runOver(AiEditLimits.DEFAULT_BATCH_DOCS + 7), session, background = true)
            collecting(model.ui)
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            session.aiEditQueue.queued
                .single()
                .first shouldHaveSize AiEditLimits.DEFAULT_BATCH_DOCS + 7
        }

    @Test
    fun queuedDocumentsAreSpentSoAFollowUpRunSkipsThem() =
        runTest(dispatcher) {
            val run = runOver(3)
            val model = vm(run, background = true)
            collecting(model.ui)
            collecting(model.preview)
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            model.start()
            advanceUntilIdle()

            run.processed.value shouldBe setOf("d1", "d2", "d3")
            model.preview.value.total shouldBe 0
        }

    @Test
    fun aFailedQueueingReportsAndStaysOnTheSetupPage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiEditQueue.enqueueFailure = IllegalStateException("disk full")
            val run = runOver(3)
            val model = vm(run, session, background = true)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()
            model.setScope(EditScope.ALL)

            model.start()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Idle
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_queue_failed, "disk full"))
            // Nothing was handed over, so nothing is spent.
            run.processed.value.shouldBeEmpty()
        }

    @Test
    fun aRunPlanCountsWhatOneStartActuallySpends() =
        runTest(dispatcher) {
            val model = vm(runOver(AiEditLimits.DEFAULT_BATCH_DOCS + 10), background = false)
            collecting(model.ui)
            collecting(model.plan)
            advanceUntilIdle()
            model.setScope(EditScope.ALL)
            advanceUntilIdle()

            // Reviewed here: one batch is bought now, however big the scope is.
            model.plan.value.documents shouldBe AiEditLimits.DEFAULT_BATCH_DOCS + 10
            model.plan.value.requests shouldBe AiEditLimits.DEFAULT_BATCH_DOCS

            // Queued: the whole scope is bought, so that is what the user is told.
            model.setBackground(true)
            advanceUntilIdle()
            model.plan.value.requests shouldBe AiEditLimits.DEFAULT_BATCH_DOCS + 10
        }
}
