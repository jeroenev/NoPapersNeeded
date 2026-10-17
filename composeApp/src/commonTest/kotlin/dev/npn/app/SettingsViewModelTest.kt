package dev.npn.app

import androidx.lifecycle.ViewModelStore
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_library
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_watched
import no_papers_needed.composeapp.generated.resources.common_folder_in_library_watched
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow_failed
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {
    @Test
    fun clearingTheThumbnailCacheSaysWhatItCleared() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val messages = collecting(model.messages.map { it.text })

            session.content.clearedThumbnails = 3
            model.clearThumbnailCache()
            advanceUntilIdle()
            session.content.clearedThumbnails = 0
            model.clearThumbnailCache()
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.common_settings_thumbnails_cleared, 3, 3),
                    uiText(Res.string.common_settings_thumbnail_cache_empty),
                )
        }

    @Test
    fun aWatchedFolderWhoseAccessIsNotKeptLeavesTheOneBeforeAndSaysSo() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository().apply { updateSettings { it.copy(watchedFolder = "/inbox") } }
            val model = settingsVm(settings = settings)
            val messages = collecting(model.messages.map { it.text })

            model.watchedFolderPicked(FolderPick.AccessNotKept)
            advanceUntilIdle()
            settings.settingsFlow.value?.watchedFolder shouldBe "/inbox"
            messages shouldContainExactly listOf(uiText(Res.string.common_folder_access_not_kept_watched))

            model.watchedFolderPicked(FolderPick.Location(TREE))
            advanceUntilIdle()
            settings.settingsFlow.value?.watchedFolder shouldBe TREE
            messages shouldContainExactly listOf(uiText(Res.string.common_folder_access_not_kept_watched))
        }

    /** A folder in the library holds the library's own files: watching it is refused, and said so. */
    @Test
    fun aWatchedFolderInTheLibraryLeavesTheOneBeforeAndSaysSo() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(libraryLocation = "/home/lib", watchedFolder = "/inbox") }
                }
            val model = settingsVm(settings = settings)
            val messages = collecting(model.messages.map { it.text })

            model.watchedFolderPicked(FolderPick.Location("/home/lib/scans"))
            advanceUntilIdle()
            model.watchedFolderPicked(FolderPick.Location("/home/lib"))
            advanceUntilIdle()
            settings.settingsFlow.value?.watchedFolder shouldBe "/inbox"
            messages shouldContainExactly List(2) { uiText(Res.string.common_folder_in_library_watched) }

            model.watchedFolderPicked(FolderPick.Location("/home/scans"))
            advanceUntilIdle()
            settings.settingsFlow.value?.watchedFolder shouldBe "/home/scans"
            messages.size shouldBe 2
        }

    @Test
    fun aLibraryFolderWhoseAccessIsNotKeptIsSaid() =
        runTest(dispatcher) {
            val model = settingsVm()
            val messages = collecting(model.messages.map { it.text })

            model.libraryAccessNotKept()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.common_folder_access_not_kept_library))
        }

    @Test
    fun stateAggregatesAllSettingsFlows() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings {
                        it.copy(
                            libraryLocation = "/home/lib",
                            appLanguage = "nl",
                            ocrLanguage = "nld",
                            defaultViewType = ViewType.GRID,
                            landscapeListColumns = true,
                            aiEnabled = true,
                            aiProvider = AiProvider.OLLAMA,
                            aiBaseUrl = "http://localhost:11434",
                            aiApiKey = "secret",
                            aiModel = "qwen3:8b",
                            aiReasoningAssistant = AiReasoningLevel.MEDIUM,
                            aiReasoningEdit = AiReasoningLevel.NONE,
                            aiMaxTagsPerDoc = 12,
                            aiMaxNewTagsPerDoc = 7,
                        )
                    }
                }
            val session = FakeLibrarySession()
            session.storagePath.storagePathFlow.value =
                StoragePathSetting("{doc_date:yyyy}/{title}.pdf", enabled = true)
            session.trashRetention.days.value = 14
            val model = settingsVm(settings = settings, session = session)
            collecting(model.state)
            advanceUntilIdle()
            val state = model.state.value
            val s = state.snapshot
            s.libraryLocation shouldBe "/home/lib"
            s.appLanguage shouldBe "nl"
            s.ocrLanguage shouldBe "nld"
            state.trashRetentionDays shouldBe 14
            s.defaultViewType shouldBe ViewType.GRID
            s.landscapeListColumns shouldBe true
            state.storagePathTemplate shouldBe "{doc_date:yyyy}/{title}.pdf"
            state.storagePathEnabled shouldBe true
            s.aiEnabled shouldBe true
            s.aiProvider shouldBe AiProvider.OLLAMA
            s.aiBaseUrl shouldBe "http://localhost:11434"
            s.aiApiKey shouldBe "secret"
            s.aiModel shouldBe "qwen3:8b"
            s.aiReasoningAssistant shouldBe AiReasoningLevel.MEDIUM
            s.aiReasoningEdit shouldBe AiReasoningLevel.NONE
            s.aiMaxTagsPerDoc shouldBe 12
            s.aiMaxNewTagsPerDoc shouldBe 7
        }

    @Test
    fun stateReactsToLaterEmissions() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            collecting(model.state)
            advanceUntilIdle()
            model.state.value.snapshot.ocrLanguage shouldBe null
            settings.updateSettings { it.copy(ocrLanguage = "fra") }
            advanceUntilIdle()
            model.state.value.snapshot.ocrLanguage shouldBe "fra"
        }

    /**
     * The retention is the library's, and each saved value a journal event: the field commits every
     * number typed, the screen follows each at once, and only the last is saved, once typing pauses.
     */
    @Test
    fun aTrashRetentionIsSavedToTheLibraryOnceTypingPauses() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            collecting(model.state)
            for (typed in listOf(1, 14)) {
                model.setTrashRetentionDays(typed)
                runCurrent()
                model.state.value.trashRetentionDays shouldBe typed
            }
            session.trashRetention.writes shouldBe emptyList()

            advanceUntilIdle()

            session.trashRetention.writes shouldContainExactly listOf(14)
            model.state.value.trashRetentionDays shouldBe 14
        }

    /** A retention typed just before the screen is left is saved all the same. */
    @Test
    fun aTrashRetentionTypedBeforeLeavingIsSaved() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val store = ViewModelStore().apply { put("settings", model) }

            model.setTrashRetentionDays(7)
            runCurrent()
            store.clear()
            advanceUntilIdle()

            session.trashRetention.writes shouldContainExactly listOf(7)
        }

    /** A retention whose save failed stays on screen as typed, and the failure is said. */
    @Test
    fun aTrashRetentionWhoseSaveFailsStaysAsTyped() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { trashRetention.fail = { error("writes blocked") } }
            val model = settingsVm(session = session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })

            model.setTrashRetentionDays(7)
            advanceUntilIdle()

            model.state.value.trashRetentionDays shouldBe 7
            messages shouldContainExactly listOf(failure(Res.string.common_save_setting_failed, "writes blocked"))
        }

    @Test
    fun editPersistsPlainSettingsThroughRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            model.edit { it.copy(ocrLanguage = "deu") }
            model.edit { it.copy(defaultViewType = ViewType.GRID) }
            model.edit { it.copy(landscapeListColumns = true) }
            model.edit { it.copy(appLanguage = "nl") }
            advanceUntilIdle()
            val s = settings.settingsFlow.value
            s?.ocrLanguage shouldBe "deu"
            s?.defaultViewType shouldBe ViewType.GRID
            s?.landscapeListColumns shouldBe true
            s?.appLanguage shouldBe "nl"
        }

    @Test
    fun clearingLanguageFallsBackToSystem() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            collecting(model.state)
            advanceUntilIdle()
            model.edit { it.copy(appLanguage = "nl") }
            advanceUntilIdle()
            model.state.value.snapshot.appLanguage shouldBe "nl"
            model.edit { it.copy(appLanguage = null) }
            advanceUntilIdle()
            model.state.value.snapshot.appLanguage shouldBe null
            settings.settingsFlow.value?.appLanguage shouldBe null
        }

    @Test
    fun untaggedOnlyModeSetsModeWithoutMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setUntaggedOnlyMode()
            advanceUntilIdle()
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.UNTAGGED_ONLY)
            session.reviewConfig.migrations shouldBe emptyList()
        }

    @Test
    fun aFailedReviewModeSwitchIsReportedNotCrashed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.failSetReviewMode = { error("writes blocked") } }
            val model = settingsVm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.setUntaggedOnlyMode()
            model.setInboxWorkflowMode(ReviewMigration.MARK_NONE)
            advanceUntilIdle()

            messages shouldContainExactly
                List(2) { failure(Res.string.common_settings_review_workflow_failed, "writes blocked") }
        }

    @Test
    fun inboxWorkflowMarkAllRunsMigrationThenSetsMode() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_ALL)
            advanceUntilIdle()
            // Migration (onlyTagged=false) runs before the mode flip.
            session.reviewConfig.migrations shouldContainExactly listOf(false)
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    @Test
    fun inboxWorkflowMarkTaggedRunsOnlyTaggedMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_TAGGED)
            advanceUntilIdle()
            session.reviewConfig.migrations shouldContainExactly listOf(true)
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    @Test
    fun inboxWorkflowMarkNoneSkipsMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_NONE)
            advanceUntilIdle()
            session.reviewConfig.migrations shouldBe emptyList()
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    private companion object {
        const val TREE = "content://com.example.documents/tree/primary%3AInbox"
    }

    @Test
    fun fieldManagerActionsReachTheRegistry() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val seeded = FieldKeys.seedDefinitions().map { it.key }

            // Moving swaps with the neighbour; a move past either end changes nothing.
            model.moveField(seeded[1], -1)
            model.moveField(seeded[1], -1)
            advanceUntilIdle()
            session.fieldRegistry.reorders shouldContainExactly
                listOf(listOf(seeded[1], seeded[0]) + seeded.drop(2))

            val def =
                FieldDef("amount", "Amount", FieldType.DECIMAL, showByDefault = true, aiEnabled = true, position = 9)
            model.saveField(def)
            model.removeField("amount", clearValues = true)
            advanceUntilIdle()
            session.fieldRegistry.upserts shouldContainExactly listOf(def)
            session.fieldRegistry.removals shouldContainExactly listOf("amount" to true)
        }
}
