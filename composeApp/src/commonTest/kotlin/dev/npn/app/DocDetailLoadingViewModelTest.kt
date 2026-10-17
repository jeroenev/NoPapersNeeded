package dev.npn.app

import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.DocLoadState
import dev.npn.app.ui.detail.PageCountState
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailLoadingViewModelTest : MainDispatcherTest() {
    @Test
    fun pageCountResolvedOnInit() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = 7 }
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Ready(7)
        }

    @Test
    fun pageCountFailureIsModelledAsFailedNotZero() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = null }
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Failed
        }

    @Test
    fun pageCountZeroIsModelledAsEmpty() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = 0 }
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Empty
        }

    @Test
    fun retryPageCountReResolvesAfterAFailure() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { content.pageCountValue = null }
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Failed

            session.content.pageCountValue = 4
            model.retryPageCount()
            advanceUntilIdle()
            model.ui.value.pageCount shouldBe PageCountState.Ready(4)
        }

    @Test
    fun loadStateIsLoadingBeforeTheFirstObserveDocEmission() =
        runTest(dispatcher) {
            val model = docDetailVm()
            model.metadata.value.document.loadState shouldBe DocLoadState.Loading
        }

    @Test
    fun loadStatePresentWhenSeededWithARow() =
        runTest(dispatcher) {
            val seed = listItem(doc("doc-1", title = "Seeded"), listOf("finance"))
            val model = DocDetailViewModel("doc-1", FakeLibrarySession(), aiConfiguredSettings(), initialItem = seed)
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(seed)
        }

    @Test
    fun loadStateBecomesMissingOnceObservedAbsent() =
        runTest(dispatcher) {
            val model = liveDocDetailVm()
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Missing
        }

    @Test
    fun loadStateBecomesPresentWhenTheRowArrives() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = liveDocDetailVm(session)
            val row = listItem(doc("doc-1", title = "Arrived"))
            session.documents.singleDoc.value = row
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)
        }

    @Test
    fun aTrashedRowStopsBeingEditableAndRestoringItBringsItBack() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val row = listItem("doc-1")
            session.documents.singleDoc.value = row
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)

            session.documents.singleDoc.value = row.copy(doc = row.doc.copy(trashed = true))
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Missing
            model.metadata.value.document.item shouldBe null

            session.documents.singleDoc.value = row
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)
        }

    @Test
    fun aDeletedRowStopsBeingEditableAndUndeletingItBringsItBack() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val row = listItem("doc-1")
            session.documents.singleDoc.value = row
            val model = liveDocDetailVm(session)
            advanceUntilIdle()

            session.documents.singleDoc.value = row.copy(doc = row.doc.copy(deleted = true))
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Missing
            model.metadata.value.document.item shouldBe null

            session.documents.singleDoc.value = row
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)
        }

    @Test
    fun aLifecycleTombstoneSeedNeverShowsAnEditableDocument() =
        runTest(dispatcher) {
            val row = listItem("doc-1")
            for (hidden in listOf(row.doc.copy(trashed = true), row.doc.copy(deleted = true))) {
                val model =
                    DocDetailViewModel(
                        "doc-1",
                        FakeLibrarySession(),
                        aiConfiguredSettings(),
                        initialItem = row.copy(doc = hidden),
                    )
                model.metadata.value.document.loadState shouldBe DocLoadState.Missing
                model.metadata.value.document.item shouldBe null
            }
        }

    @Test
    fun aMissingFileStillHasEditableMetadata() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val row = listItem(doc("doc-1").copy(missing = true))
            session.documents.singleDoc.value = row
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.metadata.value.document.loadState shouldBe DocLoadState.Present(row)
        }
}
