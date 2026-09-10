package dev.npn.app

import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.DocLoadState
import dev.npn.app.ui.detail.PageCountState
import dev.npn.core.app.DocListItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailLoadingViewModelTest : MainDispatcherTest() {
    private fun TestScope.vm(session: FakeLibrarySession = FakeLibrarySession()) =
        DocDetailViewModel("doc-1", session).also { model ->
            backgroundScope.launch { model.metadata.collect {} }
        }

    @Test
    fun pageCountResolvedOnInit() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = 7 }
            val model = vm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Ready(7)
        }

    @Test
    fun pageCountFailureIsModelledAsFailedNotZero() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failPageCount = { error("boom") } }
            val model = vm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Failed
        }

    @Test
    fun pageCountZeroIsModelledAsEmpty() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = 0 }
            val model = vm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Empty
        }

    @Test
    fun retryPageCountReResolvesAfterAFailure() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.failPageCount = { error("boom") } }
            val model = vm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Failed

            session.content.failPageCount = null
            session.content.pageCountValue = 4
            model.retryPageCount()
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Ready(4)
        }

    @Test
    fun loadStateIsLoadingBeforeTheFirstObserveDocEmission() =
        runTest(dispatcher) {
            val model = DocDetailViewModel("doc-1", FakeLibrarySession())
            model.metadata.value.document.loadState shouldBe DocLoadState.Loading
        }

    @Test
    fun loadStatePresentWhenSeededWithARow() =
        runTest(dispatcher) {
            val seed = DocListItem(doc("doc-1", title = "Seeded"), listOf("finance"))
            val model = DocDetailViewModel("doc-1", FakeLibrarySession(), initialItem = seed)
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(seed)
        }

    @Test
    fun loadStateBecomesMissingOnceObservedAbsent() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Missing
        }

    @Test
    fun loadStateBecomesPresentWhenTheRowArrives() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val row = DocListItem(doc("doc-1", title = "Arrived"), emptyList())
            session.documents.singleDoc.value = row
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)
        }
}
