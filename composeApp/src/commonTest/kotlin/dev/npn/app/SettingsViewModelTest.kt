package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.evaluateStoragePath
import dev.npn.app.ui.settings.text
import dev.npn.core.ai.AiCheck
import dev.npn.core.ai.AiCheckOutcome
import dev.npn.core.ai.AiCheckStatus
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.app.AiTagging
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
import dev.npn.core.pathrules.StoragePathError
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow_failed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_not_a_date
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnail_cache_empty
import no_papers_needed.composeapp.generated.resources.common_settings_thumbnails_cleared
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : MainDispatcherTest() {
    private fun vm(
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        session: FakeLibrarySession = FakeLibrarySession(),
        selfTest: suspend (AiConfig) -> AiSelfTestReport = { error("no self-test expected") },
    ) = SettingsViewModel(settings, session, selfTest)

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
    fun stateAggregatesAllSettingsFlows() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings {
                        it.copy(
                            libraryLocation = "/home/lib",
                            appLanguage = "nl",
                            ocrLanguage = "nld",
                            trashRetentionDays = 14,
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
            val model = vm(settings = settings, session = session)
            collecting(model.state)
            advanceUntilIdle()
            val state = model.state.value
            val s = state.snapshot
            s.libraryLocation shouldBe "/home/lib"
            s.appLanguage shouldBe "nl"
            s.ocrLanguage shouldBe "nld"
            s.trashRetentionDays shouldBe 14
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
        val result = evaluateStoragePath("{unknown}/{title}.pdf", PREVIEW_CONTEXT)
        result.error shouldBe StoragePathError.UnknownVariable("unknown")
    }

    @Test
    fun aDatePatternOnANonDateFieldSaysSo() {
        val result = evaluateStoragePath("{field:amount:yyyy}/{title}.pdf", PREVIEW_CONTEXT)

        result.error shouldBe StoragePathError.NotADateField("amount")
        result.error?.text() shouldBe uiText(Res.string.common_settings_storage_error_not_a_date, "amount")
    }

    /**
     * A field the user defined as a date takes a date pattern like the seeded document date does.
     * Judged against the seed types alone, Settings refused to enable a template the import path
     * would have expanded.
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

            evaluateStoragePath(template, PREVIEW_CONTEXT.copy(fieldTypes = types)).error shouldBe null
            model.setStoragePathEnabled(true)
            advanceUntilIdle()

            session.storagePath.storagePathFlow.value.enabled shouldBe true
        }

    @Test
    fun storagePathPreviewUsesProvidedLocalizedSamples() {
        val result = evaluateStoragePath("{docType}/{correspondent}/{tags}/{title}.pdf", PREVIEW_CONTEXT)
        result.preview shouldBe
            "Factuur/Voorbeeld Energie/nutsvoorzieningen, thuis/Elektriciteitsrekening.pdf"
    }

    @Test
    fun editPersistsPlainSettingsThroughRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.edit { it.copy(ocrLanguage = "deu") }
            model.edit { it.copy(trashRetentionDays = 60) }
            model.edit { it.copy(defaultViewType = ViewType.GRID) }
            model.edit { it.copy(landscapeListColumns = true) }
            model.edit { it.copy(appLanguage = "nl") }
            advanceUntilIdle()
            val s = settings.settingsFlow.value
            s?.ocrLanguage shouldBe "deu"
            s?.trashRetentionDays shouldBe 60
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

    @Test
    fun enablingArmsTheTemplateTheScreenShows() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setStoragePathEnabled(true)
            advanceUntilIdle()
            session.storagePath.storagePathFlow.value shouldBe StoragePathSetting("{doc_date:yyyy}/{title}.pdf", true)
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

    private fun aiSettings() =
        FakeSettingsRepository().apply {
            updateSettings {
                it.copy(
                    aiEnabled = true,
                    aiProvider = AiProvider.OLLAMA,
                    aiModel = "qwen3:8b",
                    aiOcrAuto = true,
                )
            }
        }

    private fun report(vision: AiCheckStatus) =
        AiSelfTestReport(
            listOf(
                AiCheckOutcome(AiCheck.API, AiCheckStatus.PASSED),
                AiCheckOutcome(AiCheck.MESSAGES, AiCheckStatus.PASSED),
                AiCheckOutcome(AiCheck.VISION, vision),
            ),
        )

    @Test
    fun aFailedVisionCheckSwitchesAiPageReadingOff() =
        runTest(dispatcher) {
            // The one setting this test is allowed to change on the user's behalf: page reading on
            // a model that cannot read images is a paid request per page that returns nothing.
            val settings = aiSettings()
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })

            model.runAiSelfTest()
            advanceUntilIdle()

            settings.settingsFlow.value?.aiOcrAuto shouldBe false
            model.aiSelfTest.value.aiOcrTurnedOff shouldBe true
            model.aiSelfTest.value.running shouldBe false
        }

    @Test
    fun aFailedSwitchOffOfPageReadingIsReportedNotClaimed() =
        runTest(dispatcher) {
            val settings = aiSettings().apply { failEdit = { error("read-only file system") } }
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })
            val messages = collecting(model.messages.map { it.text })

            model.runAiSelfTest()
            advanceUntilIdle()

            model.aiSelfTest.value.aiOcrTurnedOff shouldBe false
            model.aiSelfTest.value.running shouldBe false
            messages shouldContainExactly
                listOf(failure(Res.string.common_save_setting_failed, "read-only file system"))
        }

    @Test
    fun aPassingOrSkippedVisionCheckLeavesAiPageReadingAlone() =
        runTest(dispatcher) {
            listOf(AiCheckStatus.PASSED, AiCheckStatus.SKIPPED).forEach { status ->
                val settings = aiSettings()
                val model = vm(settings = settings, selfTest = { report(status) })

                model.runAiSelfTest()
                advanceUntilIdle()

                settings.settingsFlow.value?.aiOcrAuto shouldBe true
                model.aiSelfTest.value.aiOcrTurnedOff shouldBe false
            }
        }

    @Test
    fun aFailedVisionCheckReportsNoChangeWhenPageReadingWasAlreadyOff() =
        runTest(dispatcher) {
            // Nothing was switched off, so the screen must not claim it was.
            val settings = aiSettings().apply { updateSettings { it.copy(aiOcrAuto = false) } }
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })

            model.runAiSelfTest()
            advanceUntilIdle()

            model.aiSelfTest.value.aiOcrTurnedOff shouldBe false
            model.aiSelfTest.value.report
                ?.statusOf(AiCheck.VISION) shouldBe AiCheckStatus.FAILED
        }

    private companion object {
        val PREVIEW_CONTEXT =
            StoragePathContext(
                fields =
                    mapOf(
                        FieldKeys.TITLE to "Elektriciteitsrekening",
                        FieldKeys.DOC_DATE to "2026-07-20",
                        FieldKeys.DOC_TYPE to "Factuur",
                        FieldKeys.CORRESPONDENT to "Voorbeeld Energie",
                    ),
                tags = listOf("thuis", "nutsvoorzieningen"),
                originalFileName = "scan.pdf",
                fieldTypes =
                    FieldKeys.seedDefinitions().associate { it.key to it.type } + ("amount" to FieldType.DECIMAL),
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
