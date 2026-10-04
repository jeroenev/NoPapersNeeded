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
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session)

            vm.retry("failed-doc")
            advanceUntilIdle()

            assertEquals(listOf("failed-doc"), session.ocr.reOcrIds)
        }
}
