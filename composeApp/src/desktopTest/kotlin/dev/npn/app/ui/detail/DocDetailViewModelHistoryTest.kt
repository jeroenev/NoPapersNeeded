package dev.npn.app.ui.detail

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.listItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.DocProperty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocDetailViewModelHistoryTest : MainDispatcherTest() {
    @Test
    fun loadHistoryPopulatesStateFromSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    documents.historyList =
                        listOf(
                            JournalEvent(
                                1,
                                1,
                                Hlc(100, 0, "dev-a"),
                                "dev-a",
                                "doc-1",
                                JournalOp.TagAdd("invoice"),
                            ).asRecord(),
                            JournalEvent(
                                1,
                                2,
                                Hlc(50, 0, "dev-a"),
                                "dev-a",
                                "doc-1",
                                JournalOp.PropSet(DocProperty.TITLE, "X"),
                            ).asRecord(),
                        )
                }
            val model = DocDetailViewModel("doc-1", session)
            val job = launch { model.metadata.collect {} }
            advanceUntilIdle()

            model.metadata.value.history shouldBe HistoryUiState.NotLoaded

            model.loadHistory()
            advanceUntilIdle()

            (model.metadata.value.history as HistoryUiState.Loaded).items.map { it.description } shouldContainExactly
                listOf(
                    HistoryDescription(HistoryTextKey.TAG_ADDED, listOf("invoice")),
                    HistoryDescription(HistoryTextKey.TITLE_CHANGED, listOf("X")),
                )
            job.cancel()
        }

    @Test
    fun loadHistoryDoesNotReloadWithoutRefreshAndExposesFailure() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = DocDetailViewModel("doc-1", session)
            // metadata shares while subscribed, so keep a collector for the whole test.
            backgroundScope.launch { model.metadata.collect {} }

            model.loadHistory()
            advanceUntilIdle()
            model.metadata.value.history shouldBe HistoryUiState.Empty
            model.loadHistory()
            advanceUntilIdle()
            session.documents.historyCalls shouldBe 1

            session.documents.historyError = IllegalStateException("index unavailable")
            model.loadHistory(refresh = true)
            advanceUntilIdle()
            model.metadata.value.history shouldBe HistoryUiState.Error("index unavailable")
            session.documents.historyCalls shouldBe 2
        }

    private fun JournalEvent.asRecord() = DocumentHistoryRecord(hlc, dev, op.wire, this)
}
