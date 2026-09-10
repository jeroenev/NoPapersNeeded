package dev.npn.app

import dev.npn.app.ui.queue.QueueViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest : MainDispatcherTest() {
    @Test
    fun retryRequeuesTheFailedDocumentThroughOcrFacade() =
        runTest(dispatcher) {
            val work = FakeWorkFacade()
            val edits = FakeAiEditQueue()
            val ocr = FakeOcrFacade()
            val vm = QueueViewModel(work, edits, ocr)

            vm.retry("failed-doc")
            advanceUntilIdle()

            assertEquals(listOf("failed-doc"), ocr.reOcrIds)
        }
}
