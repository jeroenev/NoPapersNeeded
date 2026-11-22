package dev.npn.app.platform

import dev.npn.core.pdf.pdfPrintJob
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.print.Pageable
import java.awt.print.PrinterAbortException
import java.awt.print.PrinterException
import java.awt.print.PrinterJob

/**
 * The one way a print job reaches the operating system: AWT's [PrinterJob] in production, a
 * recording fake in tests. Kept behind this seam so a test can check the job a document makes
 * without a printer, and the print dialog never runs in a test.
 */
internal interface PrinterSurface {
    /** Whether the platform has a print service at all; a sandbox may have none. */
    fun hasPrinters(): Boolean = true

    /** Shows the platform's print dialog and prints [pages]; [PrintOutcome] carries cancel and failure. */
    suspend fun print(
        fileName: String,
        pages: Pageable,
    ): PrintOutcome
}

/** AWT's print dialog and printer. */
internal object AwtPrinterSurface : PrinterSurface {
    override fun hasPrinters(): Boolean =
        try {
            PrinterJob.lookupPrintServices().isNotEmpty()
        } catch (_: PrinterException) {
            false
        }

    override suspend fun print(
        fileName: String,
        pages: Pageable,
    ): PrintOutcome {
        val job = PrinterJob.getPrinterJob()
        job.jobName = fileName
        job.setPageable(pages)
        // Only the dialog needs AWT's event thread; PDFBox renders and the job spools on IO.
        val accepted = withContext(Dispatchers.Swing) { job.printDialog() }
        if (!accepted) return PrintOutcome.Cancelled
        withContext(Dispatchers.IO) { job.print() }
        return PrintOutcome.Started
    }
}

/**
 * Opens [bytes] as a PDF and hands its pages to [surface]. No printer is touched while the job is
 * built, which is what lets a test check its pages.
 */
internal class DesktopPdfPrinter(
    private val surface: PrinterSurface = AwtPrinterSurface,
) {
    suspend fun print(
        fileName: String,
        bytes: ByteArray,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): PrintOutcome {
        if (!withContext(io) { surface.hasPrinters() }) return PrintOutcome.Failed(PrintFailure.UNSUPPORTED)
        val job =
            withContext(io) { runCatchingCancellable { pdfPrintJob(bytes) } }
                .getOrElse { return PrintOutcome.Failed(PrintFailure.UNREADABLE_PDF, it) }
        return job.use {
            try {
                surface.print(fileName, it.pages)
            } catch (_: PrinterAbortException) {
                PrintOutcome.Cancelled
            } catch (failure: Exception) {
                PrintOutcome.Failed(PrintFailure.PRINT_FAILED, failure)
            }
        }
    }
}

actual suspend fun printPdf(
    fileName: String,
    bytes: ByteArray,
    pageCount: Int?,
    io: CoroutineDispatcher,
): PrintOutcome = DesktopPdfPrinter().print(fileName, bytes, io)
