package dev.npn.app

import dev.npn.app.ui.queue.QueueViewModel
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.WorkState
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard_failed
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueViewModelTest : MainDispatcherTest() {
    /** A restored process still asks whether to discard the run whose answers were already paid for. */
    @Test
    fun aRestoredProcessKeepsTheOpenDiscardConfirmation() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.work.editRunEntries.value =
                listOf(
                    QueuedEditRun(
                        jobId = "job",
                        total = 4,
                        done = 1,
                        failures = 0,
                        state = WorkState.RUNNING,
                        createdAt = 0L,
                        updatedAt = 0L,
                        attempts = 0,
                    ),
                )
            val screen = recreatable { QueueViewModel(session, it) }
            screen.vm.confirmDiscard("job")

            val restored = screen.recreate(this, { combine(it.items, it.editRuns, it.confirmingDiscard, ::Triple) })
            restored.confirmingDiscard.value shouldBe "job"
        }

    @Test
    fun retryRequeuesTheFailedDocumentThroughOcrFacade() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session)

            vm.retry("failed-doc")
            advanceUntilIdle()

            assertEquals(listOf("failed-doc"), session.ocr.reOcrIds)
        }

    @Test
    fun discardingAnEditRunDropsItQuietly() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session)
            val messages = collecting(vm.messages.map { it.text })

            vm.discardEditRun("run")
            advanceUntilIdle()

            session.aiEditQueue.discarded shouldContainExactly listOf("run")
            messages.shouldBeEmpty()
        }

    /** The run stays on screen when the delete fails, so the user must be told why it did not go. */
    @Test
    fun aDiscardThatFailsSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiEditQueue.discardFailure = IllegalStateException("database is locked")
            val vm = QueueViewModel(session)
            val messages = collecting(vm.messages.map { it.text })

            vm.discardEditRun("run")
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(failure(Res.string.queue_edit_run_discard_failed, "database is locked"))
        }
}
