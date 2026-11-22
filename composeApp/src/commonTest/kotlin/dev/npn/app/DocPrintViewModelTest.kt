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
                }
            var printed: Pair<String, ByteArray>? = null
            val model =
                liveDocDetailVm(
                    session = session,
                    printer =
                        PdfPrinter { fileName, data ->
                            printed = fileName to data
                            PrintOutcome.Started
                        },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            printed?.first shouldBe "doc-1.pdf"
            printed?.second shouldBe bytes
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_started))
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
                        PdfPrinter { _, _ ->
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
                        PdfPrinter { _, _ ->
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
            val model = liveDocDetailVm(session, printer = PdfPrinter { _, _ -> PrintOutcome.Cancelled })
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
                    printer = PdfPrinter { _, _ -> PrintOutcome.Failed(PrintFailure.PRINT_FAILED, cause) },
                )
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_print_failed_job))
            logged shouldContainExactly listOf(cause)
        }
}
