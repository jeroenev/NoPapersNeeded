package dev.npn.app

import dev.npn.app.ui.library.GettingStarted
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.TagInfo
import dev.npn.core.model.ReviewMode
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The library's "Getting started" card: shown once the library has documents, each step ticked off
 * as it is taken, gone for good once closed.
 */
class LibraryGettingStartedTest : MainDispatcherTest() {
    private val session = FakeLibrarySession()
    private val settings = FakeSettingsRepository()

    @Test
    fun aLibraryWithoutDocumentsShowsNoCard() =
        runTest(dispatcher) {
            val vm = LibraryViewModel(session, settings)
            val card = collecting(vm.gettingStarted)
            advanceUntilIdle()

            card.last().shouldBeNull()
        }

    @Test
    fun theCardTicksOffEachStepAsItIsTaken() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem("doc-1"))
            val vm = LibraryViewModel(session, settings)
            val card = collecting(vm.gettingStarted)
            advanceUntilIdle()
            card.last() shouldBe GettingStarted()

            session.tags.tags.value = listOf(TagInfo("bills", color = null, icon = null, count = 1))
            session.sync.configFlow.value = CloudSyncConfig(enabled = true, remote = "drive:npn")
            session.reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
            advanceUntilIdle()

            card.last() shouldBe GettingStarted(tagged = true, anotherDevice = true, inbox = true)
        }

    /** Searching ticks "Try a search" off, on this device for good. */
    @Test
    fun theFirstSearchIsRemembered() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem("doc-1"))
            val vm = LibraryViewModel(session, settings)
            val card = collecting(vm.gettingStarted)
            advanceUntilIdle()

            vm.onQueryChange("invoice")
            advanceTimeBy(QUERY_SETTLED_MS)
            advanceUntilIdle()

            settings.settings.value.searchTried shouldBe true
            card.last()?.searched shouldBe true
        }

    @Test
    fun closingTheCardKeepsItAwayOnThisDevice() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem("doc-1"))
            val vm = LibraryViewModel(session, settings)
            val shown = collecting(vm.gettingStarted.map { it != null })
            advanceUntilIdle()

            vm.dismissGettingStarted()
            advanceUntilIdle()

            settings.settings.value.gettingStartedDismissed shouldBe true
            shown shouldContainExactly listOf(false, true, false)
            // A new screen on this device does not bring it back.
            val again = LibraryViewModel(session, settings)
            val card = collecting(again.gettingStarted)
            advanceUntilIdle()
            card.last().shouldBeNull()
        }

    /** With every step taken, the optional assistant too, the card has nothing left to offer. */
    @Test
    fun aCompletedCardGoesAway() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem("doc-1"))
            session.tags.tags.value = listOf(TagInfo("bills", color = null, icon = null, count = 1))
            session.sync.configFlow.value = CloudSyncConfig(enabled = true, remote = "drive:npn")
            session.reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
            settings.updateSettings {
                it.copy(searchTried = true, aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b")
            }
            val vm = LibraryViewModel(session, settings)
            val card = collecting(vm.gettingStarted)
            advanceUntilIdle()

            card.last().shouldBeNull()
        }

    private companion object {
        /** Past the search field's debounce, after which the typed query is the filter's. */
        const val QUERY_SETTLED_MS = 1_000L
    }
}
