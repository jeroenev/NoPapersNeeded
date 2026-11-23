package dev.npn.app.platform

import dev.npn.core.pdf.pdfPrintJob
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.print.Pageable
import java.awt.print.PrinterAbortException
import java.io.ByteArrayOutputStream
import kotlin.test.Test

class DesktopPdfPrinterTest {
    /** Stands in for AWT's print dialog: records what it was asked to print, touches no printer. */
    private class RecordingSurface(
        private val outcome: PrintOutcome = PrintOutcome.Started,
        private val printers: Boolean = true,
    ) : PrinterSurface {
        var fileName: String? = null
        var pages: Pageable? = null

        override fun hasPrinters(): Boolean = printers

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

    private fun printer(surface: PrinterSurface) = DesktopPdfPrinter(surface)

    @Test
    fun aPdfBecomesAPrintableWithItsPageCount() {
        pdfPrintJob(twoPagePdf()).use { job ->
            job.pageCount shouldBe 2
            job.pages.numberOfPages shouldBe 2
        }
    }

    @Test
    fun printingHandsThePdfPagesToThePrinter() =
        runTest {
            val surface = RecordingSurface()
            val outcome = printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined)

            outcome shouldBe PrintOutcome.Started
            surface.fileName shouldBe "invoice.pdf"
            surface.pages?.numberOfPages shouldBe 2
        }

    @Test
    fun aDismissedPrintDialogCancels() =
        runTest {
            val surface = RecordingSurface(outcome = PrintOutcome.Cancelled)

            printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined) shouldBe
                PrintOutcome.Cancelled
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

            printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined) shouldBe
                PrintOutcome.Cancelled
        }

    @Test
    fun aPrinterFailureIsTyped() =
        runTest {
            val surface = RecordingSurface(outcome = PrintOutcome.Failed(PrintFailure.PRINT_FAILED))

            val outcome = printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined)

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.PRINT_FAILED
        }

    @Test
    fun noPrinterAtAllIsUnsupported() =
        runTest {
            val surface = RecordingSurface(printers = false)

            val outcome = printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined)

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.UNSUPPORTED
            surface.pages shouldBe null
        }

    @Test
    fun bytesThatAreNotAPdfFailBeforeAnyPrinterIsAsked() =
        runTest {
            val surface = RecordingSurface()
            val outcome = printer(surface).print("notes.pdf", "not a PDF".encodeToByteArray(), Dispatchers.Unconfined)

            (outcome as PrintOutcome.Failed).reason shouldBe PrintFailure.UNREADABLE_PDF
            surface.pages shouldBe null
        }
}
