package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.recreatable
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditScope
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Done → next batch, and back to the setup form between batches. */
class AiEditBatchWorkflowTest : MainDispatcherTest() {
    /**
     * A restored process opens AI edit on the run the user had configured, over the documents
     * handed to it (whose hand-off is gone), without the ones a batch already spent: a next Start
     * carries on where the run was.
     */
    @Test
    fun aRestoredProcessKeepsTheRunsSetupHandOffAndSpentDocuments() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            var handOff = listOf("a", "b", "c")
            val screen =
                recreatable { saved ->
                    val run =
                        FakeAiEditRun().apply {
                            inScope("a", "b", "c")
                            batch = review(reviewDoc("a", tags = listOf("t")))
                            outcome = ApplyOutcome.Done(ApplyResult(1, 1, moreRemain = true))
                        }
                    vm(run, session, selection = handOff, background = null, task = null, saved = saved)
                }
            collecting(screen.vm.ui)
            advanceUntilIdle()
            screen.vm.apply {
                setTask(EditTask.CUSTOM)
                setScope(EditScope.SELECTION)
                setBatchSize(1)
                setCustomPrompt("Prefer the sender's own name")
                setBackground(false)
                start()
            }
            advanceUntilIdle()
            screen.vm.apply()
            advanceUntilIdle()
            screen.vm.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Done>()
            screen.vm.reconfigure()
            // The restored page is opened without a hand-off: it went with the process.
            handOff = emptyList()

            val restored = screen.recreate(this, { combine(it.ui, it.preview, ::Pair) })
            restored.ui.value.selectionSize shouldBe 3
            restored.preview.value.batch
                .map { it.doc.docId } shouldBe listOf("b")
        }

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
