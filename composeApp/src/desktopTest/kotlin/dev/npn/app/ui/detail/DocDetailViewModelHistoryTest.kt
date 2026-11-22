package dev.npn.app.ui.detail

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.liveDocDetailVm
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.HistoryEntry
import dev.npn.core.app.HistoryOrigin
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_history_field_set
import no_papers_needed.composeapp.generated.resources.detail_history_tag_added
import kotlin.test.Test

class DocDetailViewModelHistoryTest : MainDispatcherTest() {
    @Test
    fun loadHistoryPopulatesStateFromSession() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    documents.historyList =
                        listOf(
                            DocumentHistoryRecord(100, HistoryOrigin.THIS_DEVICE, HistoryEntry.TagAdded("invoice")),
                            DocumentHistoryRecord(
                                50,
                                HistoryOrigin.THIS_DEVICE,
                                HistoryEntry.FieldSet(FieldKeys.TITLE, "X"),
                            ),
                        )
                }
            val model = DocDetailViewModel("doc-1", session, FakeSettingsRepository())
            collecting(model.metadata)
            advanceUntilIdle()

            model.metadata.value.history shouldBe HistoryUiState.NotLoaded

            model.loadHistory()
            advanceUntilIdle()

            (model.metadata.value.history as HistoryUiState.Loaded).items.map { it.description } shouldContainExactly
                listOf(
                    uiText(Res.string.detail_history_tag_added, "invoice"),
                    uiText(Res.string.detail_history_field_set, "Title", "X"),
                )
        }

    /**
     * Changes only a newer version of the app can read are one count, not a row each; with nothing
     * else, they are still a history.
     */
    @Test
    fun changesFromANewerVersionAreCountedNotListed() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    documents.historyList =
                        listOf(
                            DocumentHistoryRecord(
                                100,
                                HistoryOrigin.THIS_DEVICE,
                                HistoryEntry.Unknown("future-op", null),
                            ),
                            DocumentHistoryRecord(
                                90,
                                HistoryOrigin.ANOTHER_DEVICE,
                                HistoryEntry.Undecodable("other-op"),
                            ),
                        )
                }
            val model = liveDocDetailVm(session, settings = FakeSettingsRepository())

            model.loadHistory()
            advanceUntilIdle()

            model.metadata.value.history shouldBe HistoryUiState.Loaded(items = emptyList(), newerChanges = 2)
        }

    @Test
    fun loadHistoryDoesNotReloadWithoutRefreshAndExposesFailure() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = liveDocDetailVm(session, settings = FakeSettingsRepository())

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

    /** A failure without a message still says what failed, as every error text does (`reason()`). */
    @Test
    fun aHistoryFailureWithoutAMessageNamesItsCause() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { documents.historyError = IllegalStateException() }
            val model = liveDocDetailVm(session, settings = FakeSettingsRepository())

            model.loadHistory()
            advanceUntilIdle()
            model.metadata.value.history shouldBe HistoryUiState.Error("IllegalStateException")
        }
}
