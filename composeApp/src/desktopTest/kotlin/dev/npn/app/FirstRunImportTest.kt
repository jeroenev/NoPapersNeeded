package dev.npn.app

import dev.npn.app.platform.ScanResult
import dev.npn.app.platform.ScannedPdf
import dev.npn.app.ui.common.text
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.onboarding.FirstDocument
import dev.npn.app.ui.onboarding.FirstRunExit
import dev.npn.app.ui.onboarding.FirstRunViewModel
import dev.npn.core.app.ImportFailure
import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.ExternalSource
import dev.npn.core.ocr.OcrState
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_scan_import_failed
import no_papers_needed.composeapp.generated.resources.library_import_failed
import java.nio.file.Path
import kotlin.test.Test

/**
 * First run's first document: added from the picker, a drop or a scan, followed while its text is
 * read, and opened. Desktop-only because a picked file is an [ExternalSource], which common code
 * cannot construct.
 */
class FirstRunImportTest : MainDispatcherTest() {
    private val session = FakeLibrarySession()

    private fun file(name: String) = ExternalSource(Path.of(name)) to name

    private fun TestScope.model(): FirstRunViewModel {
        val vm = FirstRunViewModel(session, FakeSettingsRepository(), "en") { null }
        collecting(vm.state)
        runCurrent()
        return vm
    }

    /**
     * The first document shows as added while its text is read, then as read; going on opens it,
     * saying its text is read. One file needs no count: the step shows it.
     */
    @Test
    fun theFirstDocumentIsFollowedWhileItsTextIsReadThenOpened() =
        runTest(dispatcher) {
            val vm = model()
            val messages = collecting(vm.messages.map { it.text })
            session.ingest.importOutcomes = listOf(ImportOutcome.Imported("d1", "bill.pdf"))
            session.documents.singleDoc.value =
                listItem(doc("d1", title = "Electricity bill", ocrState = OcrState.PENDING))

            vm.import(listOf(file("bill.pdf")))
            runCurrent()

            vm.state.value.firstDocument shouldBe FirstDocument.Added("d1", "Electricity bill", readingText = true)
            vm.firstDocumentExit() shouldBe FirstRunExit.Document("d1", textRead = false)

            session.documents.singleDoc.value =
                listItem(doc("d1", title = "Electricity bill", ocrState = OcrState.DONE))
            runCurrent()

            vm.firstDocumentExit() shouldBe FirstRunExit.Document("d1", textRead = true)
            messages shouldBe emptyList()
        }

    /** A file the library had already is the one to open, said as such. */
    @Test
    fun aFileTheLibraryHadIsTheDocumentToOpen() =
        runTest(dispatcher) {
            val vm = model()
            session.ingest.importOutcomes = listOf(ImportOutcome.Duplicate("d0", "old.pdf"))

            vm.import(listOf(file("old.pdf")))
            runCurrent()

            vm.state.value.firstDocument shouldBe
                FirstDocument.Added("d0", "old.pdf", readingText = false, alreadyThere = true)
        }

    /** Several files are counted as the library counts them; the first that went in is followed. */
    @Test
    fun severalFilesAreCountedAndTheFirstAddedIsFollowed() =
        runTest(dispatcher) {
            val vm = model()
            val messages = collecting(vm.messages.map { it.text })
            session.ingest.importOutcomes =
                listOf(ImportOutcome.Failed(ImportFailure.EMPTY_FILE), ImportOutcome.Imported("d2", "b.pdf"))

            vm.import(listOf(file("a.pdf"), file("b.pdf")))
            runCurrent()

            (vm.state.value.firstDocument as FirstDocument.Added).docId shouldBe "d2"
            messages.map { it.english() } shouldContainExactly listOf("1 imported, 1 failed")
        }

    /** An import that throws says why and leaves the step as it was, to try again. */
    @Test
    fun anImportThatFailsSaysWhyAndLeavesTheStepAsItWas() =
        runTest(dispatcher) {
            val vm = model()
            val messages = collecting(vm.messages.map { it.text })
            session.ingest.failImport = { error("disk full") }

            vm.import(listOf(file("a.pdf")))
            runCurrent()

            vm.state.value.firstDocument shouldBe FirstDocument.None
            messages shouldContainExactly listOf(failure(Res.string.library_import_failed, "disk full"))
        }

    /** A scan that went in is the first document; one the library refused says why. */
    @Test
    fun aScanIsTheFirstDocumentOrSaysWhyNot() =
        runTest(dispatcher) {
            val vm = model()
            val messages = collecting(vm.messages.map { it.text })
            val scan = ScanResult.Scanned(ScannedPdf(ExternalSource(Path.of("scan.pdf")), "Scan.pdf"))
            session.ingest.importOutcomes =
                listOf(ImportOutcome.Failed(ImportFailure.UNREADABLE_IMAGES), ImportOutcome.Imported("s1", "Scan.pdf"))

            vm.importScan(scan)
            runCurrent()
            vm.state.value.firstDocument shouldBe FirstDocument.None

            vm.importScan(scan)
            runCurrent()
            (vm.state.value.firstDocument as FirstDocument.Added).docId shouldBe "s1"
            messages shouldContainExactly
                listOf(uiText(Res.string.common_scan_import_failed, ImportFailure.UNREADABLE_IMAGES.text()))
        }
}
