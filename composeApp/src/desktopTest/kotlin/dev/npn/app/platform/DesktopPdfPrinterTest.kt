package dev.npn.app.platform

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import java.awt.print.Pageable
import java.io.ByteArrayOutputStream
import kotlin.test.Test

class DesktopPdfPrinterTest {
    /** Stands in for AWT's print dialog: records what it was asked to print, touches no printer. */
    private class RecordingSurface(
        private val result: Boolean = true,
    ) : PrinterSurface {
        var fileName: String? = null
        var pages: Pageable? = null

        override fun print(
            fileName: String,
            pages: Pageable,
        ): Boolean {
            this.fileName = fileName
            this.pages = pages
            return result
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

    private fun printer(surface: PrinterSurface) = DesktopPdfPrinter(surface, dialogs = Dispatchers.Unconfined)

    @Test
    fun aPdfBecomesAPrintableWithItsPageCount() {
        DesktopPrintJob.open(twoPagePdf()).use { job ->
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
            val surface = RecordingSurface(result = false)

            printer(surface).print("invoice.pdf", twoPagePdf(), Dispatchers.Unconfined) shouldBe
                PrintOutcome.Cancelled
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
