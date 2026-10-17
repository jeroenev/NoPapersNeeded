@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Done → next batch, and back to the setup form between batches. */
class AiEditBatchWorkflowTest : MainDispatcherTest() {
    @Test
    fun theNextBatchAsksTheRunAgain() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    batch = review(reviewDoc("a", tags = listOf("t")))
                    outcome = ApplyOutcome.Done(ApplyResult(1, 1, moreRemain = true))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            run.batch = review(reviewDoc("b", tags = listOf("t")))
            model.nextBatch()
            advanceUntilIdle()

            run.requests shouldHaveSize 2
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
                .batch.docs
                .single()
                .docId shouldBe "b"
        }

    @Test
    fun aNextBatchThatIsGoneStopsBeingOffered() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    batch = review(reviewDoc("a", tags = listOf("t")))
                    outcome = ApplyOutcome.Done(ApplyResult(1, 1, moreRemain = true))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            // b was reviewed or removed elsewhere in the meantime.
            run.documents.value = run.documents.value.filter { it.doc.docId == "a" }
            model.nextBatch()
            advanceUntilIdle()

            run.requests shouldHaveSize 1
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(1, 1, moreRemain = false))
        }

    @Test
    fun reconfiguringReturnsToTheSetupFormKeepingTheSpentDocuments() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    batch = review(reviewDoc("a", tags = listOf("t")))
                    outcome = ApplyOutcome.Done(ApplyResult(1, 1, moreRemain = true))
                }
            val model = vm(run)
            collecting(model.ui)
            collecting(model.preview)
            advanceUntilIdle()
            model.start()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            model.reconfigure()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Idle
            model.preview.value.total shouldBe 1
        }
}
