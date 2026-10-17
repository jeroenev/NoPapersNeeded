package dev.npn.app

import androidx.lifecycle.ViewModelStore
import dev.npn.app.platform.FolderPick
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.TextSetting
import dev.npn.app.ui.settings.text
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathError
import dev.npn.core.pathrules.StoragePathExpansion
import dev.npn.core.pathrules.StoragePathTemplate
import dev.npn.core.pathrules.pathOrNull
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_not_a_date
import no_papers_needed.composeapp.generated.resources.common_settings_storage_rule_stays_off
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {
    private fun vm(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        session: FakeLibrarySession = FakeLibrarySession(),
    ) = SettingsViewModel(
        settings,
        session,
        inLibrary = { folder, library -> folder == library || folder.startsWith("$library/") },
    )

    @Test
    fun clearingTheThumbnailCacheSaysWhatItCleared() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
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
            val model = vm(settings = settings)
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
            val model = vm(settings = settings)
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
            val model = vm()
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
            val model = vm(settings = settings, session = session)
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
            val model = vm(settings = settings)
            collecting(model.state)
            advanceUntilIdle()
            model.state.value.snapshot.ocrLanguage shouldBe null
            settings.updateSettings { it.copy(ocrLanguage = "fra") }
            advanceUntilIdle()
            model.state.value.snapshot.ocrLanguage shouldBe "fra"
        }

    @Test
    fun storagePathErrorsAreProjectedAsLocalizableData() {
        val result = StoragePathTemplate.expand("{unknown}/{title}.pdf", PREVIEW_CONTEXT)
        result shouldBe StoragePathExpansion.Invalid(StoragePathError.UnknownVariable("unknown"))
    }

    @Test
    fun aDatePatternOnANonDateFieldSaysSo() {
        val result = StoragePathTemplate.expand("{field:amount:yyyy}/{title}.pdf", PREVIEW_CONTEXT)

        val error = (result as StoragePathExpansion.Invalid).error
        error shouldBe StoragePathError.NotADateField("amount")
        error.text() shouldBe uiText(Res.string.common_settings_storage_error_not_a_date, "amount")
    }

    /**
     * A field the user defined as a date takes a date pattern like the seeded document date does;
     * judged against the seed types alone, Settings would refuse a template the import path expands.
     */
    @Test
    fun aDatePatternOnAUserDefinedDateFieldValidatesAndEnables() =
        runTest(dispatcher) {
            val invoiceDate =
                FieldDef(
                    "invoiceDate",
                    "Invoice date",
                    FieldType.DATE,
                    showByDefault = false,
                    aiEnabled = false,
                    position = 9,
                )
            val session =
                FakeLibrarySession().apply {
                    fieldRegistry.defs.value = fieldRegistry.defs.value + invoiceDate
                }
            val template = "{field:invoiceDate:yyyy}/{title}.pdf"
            session.storagePath.storagePathFlow.value = StoragePathSetting(template)
            val model = vm(session = session)
            val types =
                session.fieldRegistry.defs.value
                    .associate { it.key to it.type }

            StoragePathTemplate.expand(template, PREVIEW_CONTEXT.copy(fieldTypes = types)).pathOrNull shouldNotBe null
            model.setStoragePathEnabled(true)
            advanceUntilIdle()

            session.storagePath.storagePathFlow.value.enabled shouldBe true
        }

    @Test
    fun storagePathPreviewUsesProvidedLocalizedSamples() {
        val result = StoragePathTemplate.expand("{docType}/{correspondent}/{tags}/{title}.pdf", PREVIEW_CONTEXT)
        result.pathOrNull shouldBe
            "Factuur/Voorbeeld Energie/nutsvoorzieningen, thuis/Elektriciteitsrekening.pdf"
    }

    @Test
    fun editPersistsPlainSettingsThroughRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
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
    fun storagePathEditsWriteThroughTheLibrary() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setStoragePathTemplate("{docType}/{title}.pdf")
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe
                StoragePathSetting("{docType}/{title}.pdf", enabled = true)
        }

    @Test
    fun aTemplateIsSavedOnceTypingPauses() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            collecting(model.state)
            for (typed in listOf("{d", "{docType}", "{docType}/{title}.pdf")) {
                model.setStoragePathTemplate(typed)
                runCurrent()
                // The screen follows every keystroke before anything is saved.
                model.state.value.storagePathTemplate shouldBe typed
            }
            session.storagePath.templateWrites shouldBe emptyList()

            advanceUntilIdle()

            session.storagePath.templateWrites shouldContainExactly listOf("{docType}/{title}.pdf")
            model.state.value.storagePathTemplate shouldBe "{docType}/{title}.pdf"
        }

    /** A template whose save failed stays on screen as typed, and the failure is said. */
    @Test
    fun aTemplateWhoseSaveFailsStaysAsTyped() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { storagePath.failTemplate = { error("read-only file system") } }
            val model = vm(session = session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })

            model.setStoragePathTemplate("{docType}/{title}.pdf")
            advanceUntilIdle()

            model.state.value.storagePathTemplate shouldBe "{docType}/{title}.pdf"
            messages shouldContainExactly
                listOf(failure(Res.string.common_save_setting_failed, "read-only file system"))

            // Turning the rule on arms what the screen shows, so it is the way to try again.
            session.storagePath.failTemplate = null
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe StoragePathSetting("{docType}/{title}.pdf", true)
            // Saved, it is no draft any more: the screen follows the library's template again.
            session.storagePath.storagePathFlow.value = StoragePathSetting("{title}.pdf", true)
            runCurrent()
            model.state.value.storagePathTemplate shouldBe "{title}.pdf"
        }

    @Test
    fun enablingArmsTheTemplateTheScreenShows() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe StoragePathSetting("{doc_date:yyyy}/{title}.pdf", true)
        }

    /**
     * Turning the rule on with a template that does not expand leaves it off and says why, in the
     * words the template field's own error uses.
     */
    @Test
    fun enablingAnInvalidTemplateSaysWhyTheRuleStaysOff() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val messages = collecting(model.messages.map { it.text })

            model.setStoragePathTemplate("{author}/{title}.pdf")
            model.setStoragePathEnabled(true)
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(
                        Res.string.common_settings_storage_rule_stays_off,
                        StoragePathError.UnknownVariable("author").text(),
                    ),
                )
            session.storagePath.storagePathFlow.value.enabled shouldBe false
        }

    /**
     * The retention is the library's, and each saved value a journal event: the field commits every
     * number typed, the screen follows each at once, and only the last is saved, once typing pauses.
     */
    @Test
    fun aTrashRetentionIsSavedToTheLibraryOnceTypingPauses() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
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
            val model = vm(session = session)
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
            val model = vm(session = session)
            collecting(model.state)
            val messages = collecting(model.messages.map { it.text })

            model.setTrashRetentionDays(7)
            advanceUntilIdle()

            model.state.value.trashRetentionDays shouldBe 7
            messages shouldContainExactly listOf(failure(Res.string.common_save_setting_failed, "writes blocked"))
        }

    @Test
    fun clearingLanguageFallsBackToSystem() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
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
    fun aiEditsPersistThroughRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.edit { it.copy(aiEnabled = true) }
            model.setAiProvider(AiProvider.ANTHROPIC)
            model.edit { it.copy(aiBaseUrl = "http://localhost:11434") }
            model.edit { it.copy(aiApiKey = "secret") }
            model.edit { it.copy(aiModel = "qwen3:8b") }
            model.edit { it.copy(aiReasoningAssistant = AiReasoningLevel.HIGH) }
            model.edit { it.copy(aiReasoningEdit = AiReasoningLevel.MEDIUM) }
            model.edit { it.copy(aiMaxTagsPerDoc = 5) }
            model.edit { it.copy(aiMaxNewTagsPerDoc = 3) }
            advanceUntilIdle()
            settings.settingsFlow.value?.aiEnabled shouldBe true
            settings.settingsFlow.value?.aiProvider shouldBe AiProvider.ANTHROPIC
            settings.settingsFlow.value?.aiBaseUrl shouldBe "http://localhost:11434"
            settings.settingsFlow.value?.aiApiKey shouldBe "secret"
            settings.settingsFlow.value?.aiModel shouldBe "qwen3:8b"
            settings.settingsFlow.value?.aiReasoningAssistant shouldBe AiReasoningLevel.HIGH
            settings.settingsFlow.value?.aiReasoningEdit shouldBe AiReasoningLevel.MEDIUM
            settings.settingsFlow.value?.aiMaxTagsPerDoc shouldBe 5
            settings.settingsFlow.value?.aiMaxNewTagsPerDoc shouldBe 3
        }

    /**
     * Each save of a device setting writes the whole settings file: what is typed into an endpoint
     * field shows at once, and only the last value is saved, once typing pauses.
     */
    @Test
    fun anEndpointFieldIsSavedOnceTypingPauses() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            collecting(model.state)
            val writes = collecting(settings.settingsFlow.map { it?.aiBaseUrl })
            for (typed in listOf("http://192", "http://192.168.1.20:11434")) {
                model.type(TextSetting.AI_BASE_URL, typed)
                runCurrent()
                model.state.value.snapshot.aiBaseUrl shouldBe typed
            }
            settings.settingsFlow.value?.aiBaseUrl shouldBe ""

            advanceUntilIdle()

            writes shouldContainExactly listOf("", "http://192.168.1.20:11434")
            model.state.value.snapshot.aiBaseUrl shouldBe "http://192.168.1.20:11434"
        }

    /** Leaving a field, or the screen, saves what was typed in it at once. */
    @Test
    fun anEndpointFieldIsSavedWhenLeft() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            val store = ViewModelStore().apply { put("settings", model) }

            model.type(TextSetting.AI_MODEL, "qwen3:8b")
            model.doneTyping(TextSetting.AI_MODEL)
            runCurrent()
            settings.settingsFlow.value?.aiModel shouldBe "qwen3:8b"

            model.type(TextSetting.AI_API_KEY, "sk-secret")
            runCurrent()
            store.clear()
            advanceUntilIdle()
            settings.settingsFlow.value?.aiApiKey shouldBe "sk-secret"
        }

    /** A base URL typed for one provider is not saved for the next. */
    @Test
    fun switchingProviderDropsATypedBaseUrl() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            collecting(model.state)
            model.setAiProvider(AiProvider.CUSTOM)
            advanceUntilIdle()

            model.type(TextSetting.AI_BASE_URL, "http://192.168.1.20:11434")
            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()

            settings.settingsFlow.value?.aiBaseUrl shouldBe ""
            model.state.value.snapshot.aiBaseUrl shouldBe ""
        }

    @Test
    fun editClampsOutOfRangeValuesInsideTheRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            // The caller does not clamp: the repository coerces into AiTagging.RANGE / NEW_TAGS_RANGE.
            model.edit { it.copy(aiMaxTagsPerDoc = 999, aiMaxNewTagsPerDoc = -5) }
            advanceUntilIdle()
            settings.settingsFlow.value?.aiMaxTagsPerDoc shouldBe AiTagging.MAX_TAGS_PER_DOC
            settings.settingsFlow.value?.aiMaxNewTagsPerDoc shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
        }

    @Test
    fun switchingProviderClearsFetchedModelList() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()
            model.aiModels.value.models shouldBe emptyList()
            model.aiModels.value.error shouldBe null
            model.aiModels.value.loading shouldBe false
        }

    @Test
    fun switchingProviderDropsTheBaseUrlOfThePreviousOne() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(aiProvider = AiProvider.OLLAMA, aiBaseUrl = "http://192.168.1.20:11434") }
                }
            val model = vm(settings = settings)

            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()
            settings.settingsFlow.value?.aiBaseUrl shouldBe "http://192.168.1.20:11434"

            model.setAiProvider(AiProvider.ANTHROPIC)
            advanceUntilIdle()
            settings.settingsFlow.value?.aiProvider shouldBe AiProvider.ANTHROPIC
            settings.settingsFlow.value?.aiBaseUrl shouldBe ""
        }

    @Test
    fun untaggedOnlyModeSetsModeWithoutMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setUntaggedOnlyMode()
            advanceUntilIdle()
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.UNTAGGED_ONLY)
            session.reviewConfig.migrations shouldBe emptyList()
        }

    @Test
    fun aFailedReviewModeSwitchIsReportedNotCrashed() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.failSetReviewMode = { error("writes blocked") } }
            val model = vm(session = session)
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
            val model = vm(session = session)
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
            val model = vm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_TAGGED)
            advanceUntilIdle()
            session.reviewConfig.migrations shouldContainExactly listOf(true)
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    @Test
    fun inboxWorkflowMarkNoneSkipsMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_NONE)
            advanceUntilIdle()
            session.reviewConfig.migrations shouldBe emptyList()
            session.reviewConfig.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    private companion object {
        const val TREE = "content://com.example.documents/tree/primary%3AInbox"

        val PREVIEW_CONTEXT =
            StoragePathContext.sample(
                fieldTypes =
                    FieldKeys.seedDefinitions().associate { it.key to it.type } + ("amount" to FieldType.DECIMAL),
                title = "Elektriciteitsrekening",
                docType = "Factuur",
                correspondent = "Voorbeeld Energie",
                tags = listOf("thuis", "nutsvoorzieningen"),
            )
    }

    @Test
    fun fieldManagerActionsReachTheRegistry() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
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
