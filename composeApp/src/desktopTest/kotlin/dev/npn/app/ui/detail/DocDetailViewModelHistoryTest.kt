package dev.npn.app.ui.detail

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.listItem
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_history_field_set
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
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
                            DocumentHistoryRecord(100, "dev-a", HistoryEntry.TagAdded("invoice")),
                            DocumentHistoryRecord(50, "dev-a", HistoryEntry.FieldSet(FieldKeys.TITLE, "X")),
                        )
                }
            val model = DocDetailViewModel("doc-1", session, FakeSettingsRepository())
            val job = launch { model.metadata.collect {} }
            advanceUntilIdle()

            model.metadata.value.history shouldBe HistoryUiState.NotLoaded

            model.loadHistory()
            advanceUntilIdle()

            (model.metadata.value.history as HistoryUiState.Loaded).items.map { it.description } shouldContainExactly
                listOf(
                    uiText(Res.string.detail_history_tag_added, "invoice"),
                    uiText(Res.string.detail_history_field_set, "Title", "X"),
                )
            job.cancel()
        }

    @Test
    fun loadHistoryDoesNotReloadWithoutRefreshAndExposesFailure() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = DocDetailViewModel("doc-1", session, FakeSettingsRepository())
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
}
