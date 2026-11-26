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
    /**
     * A print sends the document's own bytes, never a baked copy, and says it started. The bytes are
     * streamed from the library chunk by chunk when the platform reads them.
     */
    @Test
    fun printStreamsTheDocumentToThePrinter() =
        runTest(dispatcher) {
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = bytes
                    content.streamChunkSize = 2
                    content.pageCountValue = 4
                }
            var printed: Triple<String, List<List<Byte>>, Int?>? = null
            val printer =
                PdfPrinter { fileName, source, pages ->
                    val chunks = mutableListOf<List<Byte>>()
                    source.stream { chunk, length -> chunks += chunk.take(length) } shouldBe true
                    printed = Triple(fileName, chunks, pages)
                    PrintOutcome.Started
                }
            val model = liveDocDetailVm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument(printer)
            advanceUntilIdle()

            printed?.first shouldBe "doc-1.pdf"
            printed?.second shouldBe listOf(listOf<Byte>(1, 2), listOf<Byte>(3, 4), listOf<Byte>(5))
            printed?.third shouldBe 4
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_started))
            model.ui.value.printing shouldBe false
        }

    /** Nothing reads the document until the platform asks for it: Android only does once a printer is picked. */
    @Test
    fun theDocumentIsReadOnlyWhenThePlatformAsksForIt() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = byteArrayOf(1)
                }
            val model = liveDocDetailVm(session = session)
            advanceUntilIdle()

            model.printDocument { _, _, _ -> PrintOutcome.Started }
            advanceUntilIdle()

            session.content.documentStreams shouldBe 0
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
            val printer =
                PdfPrinter { _, _, _ ->
                    entered.complete(Unit)
                    release.await()
                    PrintOutcome.Started
                }
            val model = liveDocDetailVm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument(printer)
            advanceUntilIdle()
            entered.isCompleted shouldBe true
            model.ui.value.printing shouldBe true

            release.complete(Unit)
            advanceUntilIdle()
            model.ui.value.printing shouldBe false
            messages shouldContainExactly listOf(uiText(Res.string.detail_print_started))
        }

    @Test
    fun repeatedPrintWhileThePlatformTakesTheJobStartsOnlyOneJob() =
        runTest(dispatcher) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = byteArrayOf(1)
                }
            var calls = 0
            val printer =
                PdfPrinter { _, _, _ ->
                    calls++
                    entered.complete(Unit)
                    release.await()
                    PrintOutcome.Started
                }
            val model = liveDocDetailVm(session)
            advanceUntilIdle()
            model.printDocument(printer)
            advanceUntilIdle()
            entered.isCompleted shouldBe true
            model.ui.value.printing shouldBe true
            model.printDocument(printer)
            advanceUntilIdle()
            release.complete(Unit)
            advanceUntilIdle()
            calls shouldBe 1
            model.ui.value.printing shouldBe false
        }

    /** A document whose bytes cannot be read when the platform asks for them says so. */
    @Test
    fun printWithoutReadableBytesSaysWhy() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.singleDoc.value = listItem("doc-1")
                    content.documentBytesValue = null
                }
            val printer =
                PdfPrinter { _, source, _ ->
                    if (source.stream { _, _ -> }) {
                        PrintOutcome.Started
                    } else {
                        PrintOutcome.Failed(PrintFailure.DOCUMENT_UNAVAILABLE)
                    }
                }
            val model = liveDocDetailVm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument(printer)
            advanceUntilIdle()

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
            val model = liveDocDetailVm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument { _, _, _ ->
                calls++
                PrintOutcome.Started
            }
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
            val model = liveDocDetailVm(session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument { _, _, _ -> PrintOutcome.Cancelled }
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
            val model = liveDocDetailVm(session = session)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.printDocument { _, _, _ -> PrintOutcome.Failed(PrintFailure.PRINT_FAILED, cause) }
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.detail_print_failed_job))
            logged shouldContainExactly listOf(cause)
        }
}
