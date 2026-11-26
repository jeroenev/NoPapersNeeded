package dev.npn.app

import androidx.lifecycle.ViewModelStore
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.diagnostics.ProblemReportState
import dev.npn.app.ui.settings.FieldDialog
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.app.LibraryDiagnostics
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.ViewType
import dev.npn.core.diagnostics.Diagnostics
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.util.NpnLog
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_library
import no_papers_needed.composeapp.generated.resources.common_folder_access_not_kept_watched
import no_papers_needed.composeapp.generated.resources.common_folder_in_library_watched
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem_failed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow_failed
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import kotlin.test.Test

class SettingsViewModelTest : MainDispatcherTest() {
    /**
     * A restored process shows Settings as the user left it: the pane picked on a wide window, the
     * search typed into the list and the field being edited. What is typed into the field's dialog,
     * or into the field that adds an OCR language, is the composable's own saveable state.
     */
    @Test
    fun aRestoredProcessKeepsThePaneTheSearchAndTheOpenDialog() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val settings = FakeSettingsRepository()
            val screen = recreatable { settingsVm(settings, session, it) }
            screen.vm.apply {
                pick(SettingsCategoryId.DOCUMENTS)
                searchCategories("field")
                openFieldDialog(FieldDialog.Edit("invoiceNo"))
            }

            val restored =
                screen.recreate(this, { model ->
                    combine(model.paneState, model.picked, model.categoryQuery) { pane, picked, query ->
                        listOf(pane, picked, query)
                    }
                })
            restored.paneState.value.fieldDialog shouldBe FieldDialog.Edit("invoiceNo")
        }

    /** The Automation pane counts the library's rules from the pane state, as they change. */
    @Test
    fun thePaneStateCarriesTheLibrarysRules() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            collecting(model.paneState)
            advanceUntilIdle()
            model.paneState.value.rules shouldBe emptyList()

            val rule = AutoMatchRule(id = "r", name = "Power", pattern = "electricity", enabled = false)
            session.rules.rules.value = listOf(rule)
            advanceUntilIdle()

            model.paneState.value.rules shouldBe listOf(rule)
        }

    @Test
    fun reportingAProblemBuildsAReadyBundleAndClearsItOnceHandedOff() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.maintenance.diagnosticsValue = LibraryDiagnostics(liveDocuments = 3, tags = 2, journalObjects = 9)
            val model = settingsVm(session = session, compute = dispatcher)
            collecting(model.paneState)
            advanceUntilIdle()

            model.reportProblem()
            advanceUntilIdle()

            val ready = model.paneState.value.problemReport as ProblemReportState.Ready
            ready.fileName shouldBe "npn-diagnostics-${BuildInfo.VERSION}.txt"
            ready.text shouldContain "Documents: 3"
            ready.text shouldContain "Journal objects: 9"
            ready.issueUrl shouldContain Diagnostics.ISSUE_BASE

            model.problemReportHandled()
            advanceUntilIdle()
            model.paneState.value.problemReport shouldBe ProblemReportState.Idle
        }

    /**
     * A broken library is what a report is often for: counting it fails, and the bundle still
     * comes, without the library's section and with the log that says why.
     */
    @Test
    fun aLibraryThatCannotBeCountedStillGivesABundle() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.maintenance.failDiagnostics = { throw IllegalStateException("journal unreadable") }
            val logged = mutableListOf<String>()
            session.log = NpnLog { _, message, _ -> logged += message }
            val model = settingsVm(session = session, compute = dispatcher)
            collecting(model.paneState)
            advanceUntilIdle()

            model.reportProblem()
            advanceUntilIdle()

            val ready = model.paneState.value.problemReport as ProblemReportState.Ready
            ready.text shouldContain "Version: ${BuildInfo.VERSION}"
            ready.text shouldNotContain "## Library"
            ready.text shouldContain "## Cloud sync"
            logged shouldContainExactly listOf("Collecting the library counts for a problem report failed")
        }

    @Test
    fun failedReportSaveShowsTheReasonAndAllowsRetry() =
        runTest(dispatcher) {
            val model = settingsVm(session = FakeLibrarySession(), compute = dispatcher)
            collecting(model.paneState)
            val messages = collecting(model.messages.map { it.text })
            model.reportProblem()
            advanceUntilIdle()

            model.problemReportFailed(IllegalStateException("disk full"))
            advanceUntilIdle()
            model.paneState.value.problemReport shouldBe ProblemReportState.Idle
            messages shouldContainExactly listOf(failure(Res.string.common_settings_report_problem_failed, "disk full"))

            model.reportProblem()
            advanceUntilIdle()
            (model.paneState.value.problemReport is ProblemReportState.Ready) shouldBe true
        }

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
            settings.settingsFlow.value.watchedFolder shouldBe "/inbox"
            messages shouldContainExactly listOf(uiText(Res.string.common_folder_access_not_kept_watched))

            model.watchedFolderPicked(FolderPick.Location(TREE))
            advanceUntilIdle()
            settings.settingsFlow.value.watchedFolder shouldBe TREE
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
            settings.settingsFlow.value.watchedFolder shouldBe "/inbox"
            messages shouldContainExactly List(2) { uiText(Res.string.common_folder_in_library_watched) }

            model.watchedFolderPicked(FolderPick.Location("/home/scans"))
            advanceUntilIdle()
            settings.settingsFlow.value.watchedFolder shouldBe "/home/scans"
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
            collecting(model.paneState)
            advanceUntilIdle()
            val state = model.paneState.value.settings
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
            collecting(model.paneState)
            advanceUntilIdle()
            model.paneState.value.settings.snapshot.ocrLanguage shouldBe null
            settings.updateSettings { it.copy(ocrLanguage = "fra") }
            advanceUntilIdle()
            model.paneState.value.settings.snapshot.ocrLanguage shouldBe "fra"
        }

    /**
     * The retention is the library's, and each saved value a journal event the purge acts on at
     * once: the field commits every number typed, the screen follows each at once, and only the last
     * is saved, when the field loses focus, never on a pause (the 1 of 14 must not be saved).
     */
    @Test
    fun aTrashRetentionIsSavedToTheLibraryWhenTheFieldLosesFocus() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            collecting(model.paneState)
            for (typed in listOf(1, 14)) {
                model.setTrashRetentionDays(typed)
                advanceUntilIdle()
                model.paneState.value.settings.trashRetentionDays shouldBe typed
            }
            session.trashRetention.writes shouldBe emptyList()

            model.doneTypingTrashRetention()
            advanceUntilIdle()

            session.trashRetention.writes shouldContainExactly listOf(14)
            model.paneState.value.settings.trashRetentionDays shouldBe 14
        }

    /**
     * Clearing the field leaves its last number (the 3 of 30) behind in the viewmodel: it must not
     * be saved when the field loses focus or the screen is left, as the field shows no number.
     */
    @Test
    fun aTrashRetentionClearedFromTheFieldIsNotSaved() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val store = ViewModelStore().apply { put("settings", model) }

            model.setTrashRetentionDays(3)
            model.setTrashRetentionDays(null)
            model.doneTypingTrashRetention()
            store.clear()
            advanceUntilIdle()

            session.trashRetention.writes shouldBe emptyList()
        }

    /** A number typed after the field was cleared is saved as usual. */
    @Test
    fun aTrashRetentionTypedAfterClearingIsSaved() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)

            model.setTrashRetentionDays(3)
            model.setTrashRetentionDays(null)
            model.setTrashRetentionDays(60)
            model.doneTypingTrashRetention()
            advanceUntilIdle()

            session.trashRetention.writes shouldContainExactly listOf(60)
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
            collecting(model.paneState)
            val messages = collecting(model.messages.map { it.text })

            model.setTrashRetentionDays(7)
            model.doneTypingTrashRetention()
            advanceUntilIdle()

            model.paneState.value.settings.trashRetentionDays shouldBe 7
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
            s.ocrLanguage shouldBe "deu"
            s.defaultViewType shouldBe ViewType.GRID
            s.landscapeListColumns shouldBe true
            s.appLanguage shouldBe "nl"
        }

    @Test
    fun clearingLanguageFallsBackToSystem() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            collecting(model.paneState)
            advanceUntilIdle()
            model.edit { it.copy(appLanguage = "nl") }
            advanceUntilIdle()
            model.paneState.value.settings.snapshot.appLanguage shouldBe "nl"
            model.edit { it.copy(appLanguage = null) }
            advanceUntilIdle()
            model.paneState.value.settings.snapshot.appLanguage shouldBe null
            settings.settingsFlow.value.appLanguage shouldBe null
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
            session.fieldRegistry.moves shouldContainExactly listOf(seeded[1] to -1, seeded[1] to -1)
            session.fieldRegistry.defs.value
                .map { it.key } shouldContainExactly
                listOf(seeded[1], seeded[0]) + seeded.drop(2)

            val def =
                FieldDef("amount", "Amount", FieldType.DECIMAL, showByDefault = true, aiEnabled = true, position = 9)
            model.saveField(def)
            model.removeField("amount", clearValues = true)
            advanceUntilIdle()
            session.fieldRegistry.upserts shouldContainExactly listOf(def)
            session.fieldRegistry.removals shouldContainExactly listOf("amount" to true)
        }
}
