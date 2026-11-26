package dev.npn.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.npn.core.pdf.pdfPrintJob
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.print.Pageable
import java.awt.print.PrinterAbortException
import java.awt.print.PrinterJob
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one way a print job reaches the operating system: AWT's [PrinterJob] in production, a
 * recording fake in tests. Kept behind this seam so a test can check the job a document makes
 * without a printer, and the print dialog never runs in a test.
 */
internal interface PrinterSurface {
    /**
     * Shows the platform's print dialog and prints [pages]; [PrintOutcome] carries cancel and failure.
     * With no printer at all, AWT's dialog says so itself and the outcome is [PrintOutcome.Cancelled].
     */
    suspend fun print(
        fileName: String,
        pages: Pageable,
        io: CoroutineDispatcher,
    ): PrintOutcome
}

/** AWT's print dialog and printer. */
internal object AwtPrinterSurface : PrinterSurface {
    override suspend fun print(
        fileName: String,
        pages: Pageable,
        io: CoroutineDispatcher,
    ): PrintOutcome {
        val job = PrinterJob.getPrinterJob()
        job.jobName = fileName
        job.setPageable(pages)
        // Only the dialog needs AWT's event thread; PDFBox renders and the job spools on IO.
        val accepted = withContext(Dispatchers.Swing) { job.printDialog() }
        if (!accepted) return PrintOutcome.Cancelled
        withContext(io) { job.print() }
        return PrintOutcome.Started
    }
}

/**
 * Streams the document into a private temporary file, outside the library, and hands its pages to
 * [surface]; PDFBox then reads the pages from that file as the job draws them, so the document is
 * never whole in memory. The file goes once the job is done. No printer is touched while the job is
 * built, which is what lets a test check its pages.
 */
internal class DesktopPdfPrinter(
    private val surface: PrinterSurface = AwtPrinterSurface,
    /** Where the temporary file goes; the system's temporary folder unless a test names another. */
    private val tempDir: Path? = null,
) {
    suspend fun print(
        fileName: String,
        source: PrintSource,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): PrintOutcome {
        // Owner-only permissions: the document is the user's, and the temporary folder is shared.
        val file =
            withContext(io) {
                if (tempDir == null) {
                    Files.createTempFile(TEMP_PREFIX, ".pdf")
                } else {
                    Files.createTempFile(tempDir, TEMP_PREFIX, ".pdf")
                }
            }
        try {
            val ours =
                withContext(io) {
                    runCatchingCancellable {
                        Files.newOutputStream(file).use { out ->
                            source.stream { bytes, length -> out.write(bytes, 0, length) }
                        }
                    }
                }.getOrElse { return PrintOutcome.Failed(PrintFailure.PRINT_FAILED, it) }
            if (!ours) return PrintOutcome.Failed(PrintFailure.DOCUMENT_UNAVAILABLE)
            return printFile(fileName, file, io)
        } finally {
            @Suppress("SuspendFunInFinallySection") // NonCancellable, which the rule misses beside the dispatcher.
            withContext(NonCancellable + io) { Files.deleteIfExists(file) }
        }
    }

    private suspend fun printFile(
        fileName: String,
        file: Path,
        io: CoroutineDispatcher,
    ): PrintOutcome {
        val job =
            withContext(io) { runCatchingCancellable { pdfPrintJob(file) } }
                .getOrElse { return PrintOutcome.Failed(PrintFailure.UNREADABLE_PDF, it) }
        return job.use {
            // A cancelled caller (the screen closed while the job spooled) propagates; only the
            // printer's own failures become an outcome.
            runCatchingCancellable { surface.print(fileName, it.pages, io) }
                .getOrElse { failure ->
                    if (failure is PrinterAbortException) {
                        PrintOutcome.Cancelled
                    } else {
                        PrintOutcome.Failed(PrintFailure.PRINT_FAILED, failure)
                    }
                }
        }
    }
}

private const val TEMP_PREFIX = "npn-print-"

@Composable
actual fun rememberPdfPrinter(): PdfPrinter =
    remember { PdfPrinter { fileName, source, _ -> DesktopPdfPrinter().print(fileName, source) } }
