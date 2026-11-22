package dev.npn.app.platform

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.printing.PDFPageable
import java.awt.print.Pageable
import java.awt.print.PrinterJob

/**
 * The one way a print job reaches the operating system: AWT's [PrinterJob] in production, a
 * recording fake in tests. Kept behind this seam so a test can check the job a document makes
 * without a printer, and the print dialog never runs in a test.
 */
internal interface PrinterSurface {
    /** Shows the platform's print dialog and prints [pages]; false when the user cancelled. */
    fun print(
        fileName: String,
        pages: Pageable,
    ): Boolean
}

/** AWT's print dialog and printer. */
internal object AwtPrinterSurface : PrinterSurface {
    override fun print(
        fileName: String,
        pages: Pageable,
    ): Boolean {
        val job = PrinterJob.getPrinterJob()
        job.jobName = fileName
        job.setPageable(pages)
        if (!job.printDialog()) return false
        job.print()
        return true
    }
}

/**
 * A PDF's pages as an AWT [Pageable], rendered by PDFBox. The document is held for as long as the
 * job draws from it, so this is [AutoCloseable]; the bytes it was opened from are never written.
 */
internal class DesktopPrintJob private constructor(
    private val document: PDDocument,
) : AutoCloseable {
    val pages: Pageable = PDFPageable(document)

    val pageCount: Int get() = pages.numberOfPages

    override fun close() = document.close()

    companion object {
        /** Opens [bytes] as a print job; throws when they are not a readable PDF. */
        fun open(bytes: ByteArray): DesktopPrintJob = DesktopPrintJob(Loader.loadPDF(bytes))
    }
}

/**
 * Renders [bytes] with PDFBox and hands the pages to [surface]. No printer is touched while the
 * job is built, which is what lets a test check its pages.
 */
internal class DesktopPdfPrinter(
    private val surface: PrinterSurface = AwtPrinterSurface,
    /** Where the print dialog and spooling run: AWT requires its own event thread. */
    private val dialogs: CoroutineDispatcher = Dispatchers.Swing,
) {
    suspend fun print(
        fileName: String,
        bytes: ByteArray,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): PrintOutcome {
        val job =
            withContext(io) { runCatchingCancellable { DesktopPrintJob.open(bytes) } }
                .getOrElse { return PrintOutcome.Failed(PrintFailure.UNREADABLE_PDF, it) }
        return job.use {
            withContext(dialogs) {
                try {
                    if (surface.print(fileName, it.pages)) PrintOutcome.Started else PrintOutcome.Cancelled
                } catch (failure: Exception) {
                    PrintOutcome.Failed(PrintFailure.PRINT_FAILED, failure)
                }
            }
        }
    }
}

actual suspend fun printPdf(
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
): PrintOutcome = DesktopPdfPrinter().print(fileName, bytes, io)
