package dev.npn.app

import dev.npn.app.platform.OcrModelSource
import dev.npn.app.platform.OcrModels
import dev.npn.app.platform.ScanResult
import dev.npn.app.platform.installCommand
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.onboarding.FirstRunExit
import dev.npn.app.ui.onboarding.FirstRunPath
import dev.npn.app.ui.onboarding.FirstRunStep
import dev.npn.app.ui.onboarding.FirstRunViewModel
import dev.npn.app.ui.onboarding.steps
import dev.npn.core.index.IndexProgress
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_scan_capture_failed
import kotlin.test.Test

class FirstRunViewModelTest : MainDispatcherTest() {
    private val session = FakeLibrarySession()
    private val settings = FakeSettingsRepository()

    /** What the device's text recognition has; null is a platform that cannot say. */
    private var models: OcrModels? = OcrModels(setOf("eng"), OcrModelSource.APT)

    private fun TestScope.model(appLanguage: String? = "nl"): FirstRunViewModel {
        val vm = FirstRunViewModel(session, settings, appLanguage) { models }
        collecting(vm.state)
        runCurrent()
        return vm
    }

    /** Desktop asks for the languages first; both ways through end on their own last step. */
    @Test
    fun eachWayThroughHasItsOwnSteps() {
        FirstRunPath.NEW.steps(askLanguages = true) shouldContainExactly
            listOf(FirstRunStep.LANGUAGES, FirstRunStep.FIRST_DOCUMENT)
        FirstRunPath.CONNECT.steps(askLanguages = false) shouldContainExactly listOf(FirstRunStep.CONNECTING)
    }

    /**
     * The app language and English come ticked, with the known models the user installed, and a
     * ticked language without its model is named as missing, with the command that installs it.
     */
    @Test
    fun theLanguagesStartFromTheAppLanguageAndTheInstalledModels() =
        runTest(dispatcher) {
            models = OcrModels(setOf("eng", "deu", "osd"), OcrModelSource.APT)
            val vm = model(appLanguage = "nl")

            val choice = vm.state.value.languages!!
            choice.selected shouldContainExactly listOf("nld", "eng", "deu")
            choice.missing shouldContainExactly listOf("nld")
            OcrModelSource.APT.installCommand(choice.missing, withTesseract = false) shouldBe
                "sudo apt install tesseract-ocr-nld"
        }

    /**
     * Where the app ships the models, they say nothing of what the user picked: only the automatic
     * choice is ticked.
     */
    @Test
    fun bundledModelsTickOnlyTheAppLanguage() =
        runTest(dispatcher) {
            models = OcrModels(setOf("eng", "nld", "deu", "fra", "spa", "por"), OcrModelSource.BUNDLED)
            val vm = model(appLanguage = "nl")

            vm.state.value.languages!!
                .selected shouldContainExactly listOf("nld", "eng")
            vm.saveLanguages()
            runCurrent()

            // The automatic choice is kept as such, so it follows a later change of app language.
            settings.settings.value.ocrLanguage shouldBe null
        }

    /** Without Tesseract, every ticked language is missing, and the command installs Tesseract too. */
    @Test
    fun withoutTesseractEveryLanguageIsMissing() =
        runTest(dispatcher) {
            models = OcrModels(installed = null, source = OcrModelSource.DNF)
            val vm = model(appLanguage = "de")

            val choice = vm.state.value.languages!!
            choice.tesseractMissing shouldBe true
            choice.missing shouldContainExactly listOf("deu", "eng")
            OcrModelSource.DNF.installCommand(choice.missing, withTesseract = true) shouldBe
                "sudo dnf install tesseract tesseract-langpack-deu tesseract-langpack-eng"
        }

    /** A changed choice is kept as it is; the last language cannot be unticked. */
    @Test
    fun aChangedChoiceIsSavedAndOneLanguageAlwaysStays() =
        runTest(dispatcher) {
            val vm = model(appLanguage = "nl")

            vm.toggleLanguage("eng")
            vm.toggleLanguage("nld")
            runCurrent()
            vm.state.value.languages!!
                .selected shouldContainExactly listOf("nld")
            vm.toggleLanguage("fra")
            vm.saveLanguages()
            runCurrent()

            settings.settings.value.ocrLanguage shouldBe "nld+fra"
        }

    /** Models installed meanwhile are found when the user asks to check again. */
    @Test
    fun checkingAgainFindsModelsInstalledMeanwhile() =
        runTest(dispatcher) {
            val vm = model(appLanguage = "nl")
            vm.state.value.languages!!
                .missing shouldContainExactly listOf("nld")

            models = OcrModels(setOf("eng", "nld"), OcrModelSource.APT)
            vm.checkModelsAgain()
            runCurrent()

            vm.state.value.languages!!
                .missing shouldBe emptyList()
        }

    /** A choice the user made in settings before is where the step starts. */
    @Test
    fun anEarlierChoiceIsWhereTheLanguagesStart() =
        runTest(dispatcher) {
            settings.updateSettings { it.copy(ocrLanguage = "fra+eng") }
            models = OcrModels(setOf("eng", "deu"), OcrModelSource.APT)
            val vm = model(appLanguage = "nl")

            vm.state.value.languages!!
                .selected shouldContainExactly listOf("fra", "eng")
        }

    /**
     * A library another device uses comes in: the rescan and the cloud's pass show as they go, with
     * the cloud folder by its provider's name and the documents counted.
     */
    @Test
    fun aLibraryComingInShowsItsProgressAndItsCloudFolder() =
        runTest(dispatcher) {
            val vm = model()
            session.sync.configFlow.value = CloudSyncConfig(enabled = true, remote = "drive-3fa9c1:NoPapersNeeded")
            session.ingest.scanProgressFlow.value = IndexProgress(IndexProgress.Phase.HASHING, hashed = 3, total = 10)
            session.documents.documents.value = listOf(listItem("a"), listItem("b"))
            runCurrent()

            val state = vm.state.value
            state.cloudFolder shouldBe "Google Drive › NoPapersNeeded"
            state.scan shouldBe IndexProgress(IndexProgress.Phase.HASHING, hashed = 3, total = 10)
            state.documents shouldBe 2
        }

    /** A scanner that failed says so; one the user backed out of says nothing. */
    @Test
    fun aFailedScanSaysSo() =
        runTest(dispatcher) {
            val vm = model()
            val messages = collecting(vm.messages.map { it.text })

            vm.importScan(ScanResult.Failed)
            vm.importScan(ScanResult.Cancelled)
            runCurrent()

            messages shouldContainExactly listOf(uiText(Res.string.common_scan_capture_failed))
        }

    /** Going on before a document went in leads to the library. */
    @Test
    fun withoutADocumentFirstRunEndsOnTheLibrary() =
        runTest(dispatcher) {
            model().firstDocumentExit() shouldBe FirstRunExit.Library
        }
}
