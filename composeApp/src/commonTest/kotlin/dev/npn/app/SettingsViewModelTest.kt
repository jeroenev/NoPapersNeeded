package dev.npn.app

import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {

    private fun vm(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        session: FakeLibrarySession = FakeLibrarySession(),
    ) = SettingsViewModel(settings, session)

    // ---- state combine ----

    @Test
    fun stateAggregatesAllSettingsFlows() = runTest(dispatcher) {
        val settings = FakeSettingsRepository().apply {
            libraryLocationFlow.value = "/home/lib"
            ocrLanguageFlow.value = "nld"
            trashRetentionDaysFlow.value = 14
            defaultViewTypeFlow.value = ViewType.GRID
            landscapeListColumnsFlow.value = true
        }
        val model = vm(settings = settings)
        val job = launch { model.state.collect {} }
        advanceUntilIdle()
        val s = model.state.value
        s.libraryLocation shouldBe "/home/lib"
        s.ocrLanguage shouldBe "nld"
        s.trashRetentionDays shouldBe 14
        s.defaultViewType shouldBe ViewType.GRID
        s.landscapeListColumns shouldBe true
        job.cancel()
    }

    @Test
    fun stateReactsToLaterEmissions() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val model = vm(settings = settings)
        val job = launch { model.state.collect {} }
        advanceUntilIdle()
        model.state.value.ocrLanguage shouldBe "eng"
        settings.ocrLanguageFlow.value = "fra"
        advanceUntilIdle()
        model.state.value.ocrLanguage shouldBe "fra"
        job.cancel()
    }

    // ---- setters delegate to repository ----

    @Test
    fun settersDelegateToRepository() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val model = vm(settings = settings)
        model.setOcrLanguage("deu")
        model.setTrashRetentionDays(60)
        model.setDefaultViewType(ViewType.GRID)
        model.setLandscapeListColumns(true)
        advanceUntilIdle()
        settings.setOcrLanguages shouldContainExactly listOf("deu")
        settings.setRetentionDays shouldContainExactly listOf(60)
        settings.setDefaultViews shouldContainExactly listOf(ViewType.GRID)
        settings.setLandscapeFlags shouldContainExactly listOf(true)
    }

    // ---- review mode switching ----

    @Test
    fun untaggedOnlyModeSetsModeWithoutMigration() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setUntaggedOnlyMode()
        advanceUntilIdle()
        session.reviewModeSets shouldContainExactly listOf(ReviewMode.UNTAGGED_ONLY)
        session.migrations shouldBe emptyList()
    }

    @Test
    fun inboxWorkflowMarkAllRunsMigrationThenSetsMode() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setInboxWorkflowMode(ReviewMigration.MARK_ALL)
        advanceUntilIdle()
        // Migration (onlyTagged=false) runs before the mode flip.
        session.migrations shouldContainExactly listOf(false)
        session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
    }

    @Test
    fun inboxWorkflowMarkTaggedRunsOnlyTaggedMigration() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setInboxWorkflowMode(ReviewMigration.MARK_TAGGED)
        advanceUntilIdle()
        session.migrations shouldContainExactly listOf(true)
        session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
    }

    @Test
    fun inboxWorkflowMarkNoneSkipsMigration() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.setInboxWorkflowMode(ReviewMigration.MARK_NONE)
        advanceUntilIdle()
        session.migrations shouldBe emptyList()
        session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
    }
}
