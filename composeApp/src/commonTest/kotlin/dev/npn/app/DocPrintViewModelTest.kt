package dev.npn.app

import dev.npn.app.platform.PdfPrinter
import dev.npn.app.platform.PrintFailure
import dev.npn.app.platform.PrintOutcome
import dev.npn.app.ui.common.uiText
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import dev.npn.core.util.NpnLog
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_print_cancelled
import no_papers_needed.composeapp.generated.resources.detail_print_failed_job
import no_papers_needed.composeapp.generated.resources.detail_print_failed_unavailable
import no_papers_needed.composeapp.generated.resources.detail_print_started
import kotlin.test.Test

/** The document's print action: what reaches the platform, and what it tells the user. */
class DocPrintViewModelTest : MainDispatcherTest() {
    /** A print sends the document's own bytes, never a baked copy, and says it started. */
    @Test
    fun printSendsTheDocumentBytesToThePrinter() =
        runTest(dispatcher) {
            val bytes = byteArrayOf(1, 2, 3)
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = bytes
                    content.pageCountValue = 4
                }
            var printed: Triple<String, ByteArray, Int?>? = null
            val model =
                liveDocDetailVm(
                    session = session,
                    printer =
                        PdfPrinter { fileName, data, pages ->
                            printed = Triple(fileName, data, pages)
                            PrintOutcome.Started
                        },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            printed?.first shouldBe "doc-1.pdf"
            printed?.second shouldBe bytes
            printed?.third shouldBe 4
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_started))
            model.ui.value.printing shouldBe false
        }

    /** The action is busy for as long as the platform's print call runs. */
    @Test
    fun printingIsTrueWhileThePlatformTakesTheJob() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = byteArrayOf(1)
                }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val model =
                liveDocDetailVm(
                    session = session,
                    printer =
                        PdfPrinter { _, _, _ ->
                            entered.complete(Unit)
                            release.await()
                            PrintOutcome.Started
                        },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()
            entered.isCompleted shouldBe true
            model.ui.value.printing shouldBe true

            release.complete(Unit)
            advanceUntilIdle()
            model.ui.value.printing shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_started))
        }

    @Test
    fun repeatedPrintWhileLoadingStartsOnlyOneJob() =
        runTest(dispatcher) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.readDocumentBytes = {
                        entered.complete(Unit)
                        release.await()
                        byteArrayOf(1)
                    }
                }
            var calls = 0
            val model =
                liveDocDetailVm(
                    session,
                    printer =
                        PdfPrinter { _, _, _ ->
                            calls++
                            PrintOutcome.Started
                        },
                )
            advanceUntilIdle()
            model.printDocument()
            advanceUntilIdle()
            entered.isCompleted shouldBe true
            model.ui.value.printing shouldBe true
            model.printDocument()
            advanceUntilIdle()
            release.complete(Unit)
            advanceUntilIdle()
            calls shouldBe 1
            model.ui.value.printing shouldBe false
        }

    /** A document whose bytes cannot be read says so instead of opening an empty print dialog. */
    @Test
    fun printWithoutReadableBytesSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = null
                }
            var calls = 0
            val model =
                liveDocDetailVm(
                    session = session,
                    printer =
                        PdfPrinter { _, _, _ ->
                            calls++
                            PrintOutcome.Started
                        },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            calls shouldBe 0
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_failed_unavailable))
            model.ui.value.printing shouldBe false
        }

    /** The library never holds anything but PDFs, but a row that names otherwise is refused. */
    @Test
    fun printRefusesADocumentThatIsNotAPdf() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value =
                        listItem(doc("doc-1", fileName = "scan.txt", relPath = "scan.txt"))
                    content.documentBytesValue = byteArrayOf(1)
                }
            var calls = 0
            val model =
                liveDocDetailVm(
                    session = session,
                    printer =
                        PdfPrinter { _, _, _ ->
                            calls++
                            PrintOutcome.Started
                        },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            calls shouldBe 0
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_failed_unavailable))
        }

    @Test
    fun printCancelledSaysSo() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = byteArrayOf(1)
                }
            val model = liveDocDetailVm(session, printer = PdfPrinter { _, _, _ -> PrintOutcome.Cancelled })
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_print_cancelled))
        }

    /** A failure is the printer's own sentence; the technical cause goes to the log, not the UI. */
    @Test
    fun printFailureSaysWhyAndLogsTheCause() =
        runTest(dispatcher) {
            val cause = IllegalStateException("printer offline")
            val logged = mutableListOf<Throwable?>()
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = byteArrayOf(1)
                    log = NpnLog { _, _, error -> logged += error }
                }
            val model =
                liveDocDetailVm(
                    session = session,
                    printer = PdfPrinter { _, _, _ -> PrintOutcome.Failed(PrintFailure.PRINT_FAILED, cause) },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_print_failed_job))
            logged shouldContainExactly listOf(cause)
        }
}
