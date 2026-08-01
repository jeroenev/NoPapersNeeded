package dev.npn.app

import dev.npn.app.ui.settings.ReviewMigration
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.StoragePathErrorKind
import dev.npn.app.ui.settings.evaluateStoragePath
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import dev.npn.core.pathrules.StoragePathContext
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
                            storagePathTemplate = "{doc_date:yyyy}/{title}.pdf",
                            storagePathEnabled = true,
                            aiEnabled = true,
                            aiBaseUrl = "http://localhost:11434",
                            aiApiKey = "secret",
                            aiModel = "qwen3:8b",
                            aiMaxTagsPerDoc = 12,
                            aiMaxNewTagsPerDoc = 7,
                        )
                    }
                }
            val model = vm(settings = settings)
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            val s = model.state.value
            s.libraryLocation shouldBe "/home/lib"
            s.appLanguage shouldBe "nl"
            s.ocrLanguage shouldBe "nld"
            s.trashRetentionDays shouldBe 14
            s.defaultViewType shouldBe ViewType.GRID
            s.landscapeListColumns shouldBe true
            s.storagePathEnabled shouldBe true
            s.aiEnabled shouldBe true
            s.aiBaseUrl shouldBe "http://localhost:11434"
            s.aiApiKey shouldBe "secret"
            s.aiModel shouldBe "qwen3:8b"
            s.aiMaxTagsPerDoc shouldBe 12
            s.aiMaxNewTagsPerDoc shouldBe 7
            job.cancel()
        }

    @Test
    fun stateReactsToLaterEmissions() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            model.state.value.ocrLanguage shouldBe "eng"
            settings.updateSettings { it.copy(ocrLanguage = "fra") }
            advanceUntilIdle()
            model.state.value.ocrLanguage shouldBe "fra"
            job.cancel()
        }

    @Test
    fun storagePathErrorsAreProjectedAsLocalizableData() {
        val result = evaluateStoragePath("{unknown}/{title}.pdf", PREVIEW_CONTEXT)
        result.error?.kind shouldBe StoragePathErrorKind.UNKNOWN_VARIABLE
        result.error?.argument shouldBe "unknown"
    }

    @Test
    fun storagePathPreviewUsesProvidedLocalizedSamples() {
        val result = evaluateStoragePath("{docType}/{correspondent}/{tags}/{title}.pdf", PREVIEW_CONTEXT)
        result.preview shouldBe
            "Factuur/Voorbeeld Energie/nutsvoorzieningen, thuis/Elektriciteitsrekening.pdf"
    }

    // ---- setters delegate to repository ----

    @Test
    fun settersDelegateToRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setOcrLanguage("deu")
            model.setTrashRetentionDays(60)
            model.setDefaultViewType(ViewType.GRID)
            model.setLandscapeListColumns(true)
            model.setStoragePathTemplate("{docType}/{title}.pdf")
            model.setStoragePathEnabled(true)
            model.setAppLanguage("nl")
            advanceUntilIdle()
            settings.setOcrLanguages shouldContainExactly listOf("deu")
            settings.setRetentionDays shouldContainExactly listOf(60)
            settings.setDefaultViews shouldContainExactly listOf(ViewType.GRID)
            settings.setLandscapeFlags shouldContainExactly listOf(true)
            settings.setStoragePathTemplates shouldContainExactly listOf("{docType}/{title}.pdf")
            settings.setStoragePathEnabledFlags shouldContainExactly listOf(true)
            settings.setAppLanguages shouldContainExactly listOf("nl")
        }

    @Test
    fun clearingLanguageFallsBackToSystem() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            val job = launch { model.state.collect {} }
            advanceUntilIdle()
            model.setAppLanguage("nl")
            advanceUntilIdle()
            model.state.value.appLanguage shouldBe "nl"
            model.setAppLanguage(null)
            advanceUntilIdle()
            model.state.value.appLanguage shouldBe null
            settings.setAppLanguages shouldContainExactly listOf("nl", null)
            job.cancel()
        }

    @Test
    fun aiSettersDelegateToRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setAiEnabled(true)
            model.setAiBaseUrl("http://localhost:11434")
            model.setAiApiKey("secret")
            model.setAiModel("qwen3:8b")
            model.setAiMaxTagsPerDoc(5)
            model.setAiMaxNewTagsPerDoc(3)
            advanceUntilIdle()
            settings.settingsFlow.value?.aiEnabled shouldBe true
            settings.settingsFlow.value?.aiBaseUrl shouldBe "http://localhost:11434"
            settings.settingsFlow.value?.aiApiKey shouldBe "secret"
            settings.settingsFlow.value?.aiModel shouldBe "qwen3:8b"
            settings.settingsFlow.value?.aiMaxTagsPerDoc shouldBe 5
            settings.settingsFlow.value?.aiMaxNewTagsPerDoc shouldBe 3
        }

    // ---- review mode switching ----

    @Test
    fun untaggedOnlyModeSetsModeWithoutMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setUntaggedOnlyMode()
            advanceUntilIdle()
            session.reviewModeSets shouldContainExactly listOf(ReviewMode.UNTAGGED_ONLY)
            session.migrations shouldBe emptyList()
        }

    @Test
    fun inboxWorkflowMarkAllRunsMigrationThenSetsMode() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_ALL)
            advanceUntilIdle()
            // Migration (onlyTagged=false) runs before the mode flip.
            session.migrations shouldContainExactly listOf(false)
            session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    @Test
    fun inboxWorkflowMarkTaggedRunsOnlyTaggedMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_TAGGED)
            advanceUntilIdle()
            session.migrations shouldContainExactly listOf(true)
            session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    @Test
    fun inboxWorkflowMarkNoneSkipsMigration() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setInboxWorkflowMode(ReviewMigration.MARK_NONE)
            advanceUntilIdle()
            session.migrations shouldBe emptyList()
            session.reviewModeSets shouldContainExactly listOf(ReviewMode.INBOX_WORKFLOW)
        }

    private companion object {
        val PREVIEW_CONTEXT =
            StoragePathContext(
                title = "Elektriciteitsrekening",
                docDate = "2026-07-20",
                docType = "Factuur",
                correspondent = "Voorbeeld Energie",
                tags = listOf("thuis", "nutsvoorzieningen"),
                originalFileName = "scan.pdf",
            )
    }
}
