package dev.npn.app.platform

import dev.npn.core.pdf.pdfPrintJob
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.print.Pageable
import java.awt.print.PrinterAbortException
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class DesktopPdfPrinterTest {
    /** Stands in for AWT's print dialog: records what it was asked to print, touches no printer. */
    private class RecordingSurface(
        private val outcome: PrintOutcome = PrintOutcome.Started,
    ) : PrinterSurface {
        var fileName: String? = null
        var pages: Pageable? = null

        override suspend fun print(
            fileName: String,
            pages: Pageable,
            io: CoroutineDispatcher,
        ): PrintOutcome {
            this.fileName = fileName
            this.pages = pages
            return outcome
        }
    }

    private fun twoPagePdf(): ByteArray =
        PDDocument().use { document ->
            repeat(2) { document.addPage(PDPage(PDRectangle.A4)) }
            ByteArrayOutputStream().use { out ->
                document.save(out)
                out.toByteArray()
            }
        }

    /** This test's own temporary folder, so no other run's print files are counted. */
    private val tempDir: Path = Files.createTempDirectory("npn-print-test-")

    @AfterTest
    fun removeTempDir() {
        Files.list(tempDir).use { files -> files.forEach(Files::delete) }
        Files.delete(tempDir)
    }

    private fun printer(surface: PrinterSurface) = DesktopPdfPrinter(surface, tempDir)

    /** The library's bytes, streamed [chunk] bytes at a time as the facade does. */
    private fun ByteArray.asSource(chunk: Int = 1024) =
        PrintSource { sink ->
            for (offset in indices step chunk) {
                val part = copyOfRange(offset, minOf(offset + chunk, size))
                sink(part, part.size)
            }
            true
        }

    private suspend fun DesktopPdfPrinter.print(
        fileName: String,
        bytes: ByteArray,
    ) = print(fileName, bytes.asSource(), Dispatchers.Unconfined)

    @Test
    fun aPdfBecomesAPrintableWithItsPageCount() {
        val file = Files.createTempFile("npn-test-", ".pdf")
        try {
            Files.write(file, twoPagePdf())
            pdfPrintJob(file).use { job ->
                job.pageCount shouldBe 2
                job.pages.numberOfPages shouldBe 2
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    /** The streamed document is printed from a temporary file, which is gone once the job is. */
    @Test
    fun theStreamedDocumentIsPrintedAndItsTemporaryFileRemoved() =
        runTest {
            var printedFrom: List<Path> = emptyList()
            val surface =
                object : PrinterSurface {
                    override suspend fun print(
                        fileName: String,
                        pages: Pageable,
                        io: CoroutineDispatcher,
                    ): PrintOutcome {
                        printedFrom = npnPrintFiles()
                        pages.numberOfPages shouldBe 2
                        return PrintOutcome.Started
                    }
                }
            val before = npnPrintFiles()

            printer(surface).print("invoice.pdf", twoPagePdf().asSource(chunk = 100), Dispatchers.Unconfined) shouldBe
                PrintOutcome.Started

            (printedFrom - before.toSet()).size shouldBe 1
            npnPrintFiles() shouldBe before
        }

    /** A document whose file is no longer its own fails before any printer is asked. */
    @Test
    fun aDocumentThatCannotBeReadIsUnavailable() =
        runTest {
            val surface = RecordingSurface()
            val before = npnPrintFiles()

            val outcome = printer(surface).print("invoice.pdf", PrintSource { false }, Dispatchers.Unconfined)

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.DOCUMENT_UNAVAILABLE
            surface.pages shouldBe null
            npnPrintFiles() shouldBe before
        }

    private fun npnPrintFiles(): List<Path> = Files.list(tempDir).use { files -> files.toList() }

    @Test
    fun printingHandsThePdfPagesToThePrinter() =
        runTest {
            val surface = RecordingSurface()
            val outcome = printer(surface).print("invoice.pdf", twoPagePdf())

            outcome shouldBe PrintOutcome.Started
            surface.fileName shouldBe "invoice.pdf"
            surface.pages?.numberOfPages shouldBe 2
        }

    @Test
    fun aDismissedPrintDialogCancels() =
        runTest {
            val surface = RecordingSurface(outcome = PrintOutcome.Cancelled)

            printer(surface).print("invoice.pdf", twoPagePdf()) shouldBe PrintOutcome.Cancelled
        }

    @Test
    fun anAbortedJobIsCancelled() =
        runTest {
            val surface =
                object : PrinterSurface {
                    override suspend fun print(
                        fileName: String,
                        pages: Pageable,
                        io: CoroutineDispatcher,
                    ): PrintOutcome = throw PrinterAbortException()
                }

            printer(surface).print("invoice.pdf", twoPagePdf()) shouldBe PrintOutcome.Cancelled
        }

    /**
     * A screen closed while its job spools cancels the coroutine: that is not a printer failure,
     * so nothing logs "printing failed" for a job that printed.
     */
    @Test
    fun aCancelledCallerIsNotAPrinterFailure() =
        runTest {
            val surface =
                object : PrinterSurface {
                    override suspend fun print(
                        fileName: String,
                        pages: Pageable,
                        io: CoroutineDispatcher,
                    ): PrintOutcome = throw CancellationException("screen closed")
                }

            shouldThrow<CancellationException> {
                printer(surface).print("invoice.pdf", twoPagePdf())
            }
        }

    @Test
    fun aPrinterFailureIsTyped() =
        runTest {
            val surface = RecordingSurface(outcome = PrintOutcome.Failed(PrintFailure.PRINT_FAILED))

            val outcome = printer(surface).print("invoice.pdf", twoPagePdf())

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.PRINT_FAILED
        }

    @Test
    fun bytesThatAreNotAPdfFailBeforeAnyPrinterIsAsked() =
        runTest {
            val surface = RecordingSurface()
            val outcome = printer(surface).print("notes.pdf", "not a PDF".encodeToByteArray())

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.UNREADABLE_PDF
            surface.pages shouldBe null
        }
}
