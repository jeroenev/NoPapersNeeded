package dev.npn.app.platform

import androidx.compose.runtime.Composable

/**
 * What a [PdfPrinter] call came to.
 *
 * Cancellation is the print dialog's own, not a coroutine's: a cancelled coroutine stops the call,
 * while [Cancelled] means the user dismissed the platform's dialog without printing.
 */
sealed interface PrintOutcome {
    /**
     * The platform took the job: desktop handed it to the printer, Android opened the system print
     * dialog. Android owns the dialog from there, so a dismissal after this point is not reported.
     */
    data object Started : PrintOutcome

    /** The user dismissed the platform's print dialog; nothing was printed. */
    data object Cancelled : PrintOutcome

    /** The job could not start. [cause] is technical and never shown to the user. */
    data class Failed(
        val reason: PrintFailure,
        val cause: Throwable? = null,
    ) : PrintOutcome
}

/** Why a print job could not start. Mapped to a sentence in `composeApp`, never shown as itself. */
enum class PrintFailure {
    /** The platform offers no print service. */
    UNSUPPORTED,

    /** The document's file could not be read, or no longer holds the document. */
    DOCUMENT_UNAVAILABLE,

    /** The bytes are not a PDF the platform's PDF library can read. */
    UNREADABLE_PDF,

    /** The print service refused or failed the job. */
    PRINT_FAILED,
}

/**
 * A document's bytes, read when the platform needs them and chunk by chunk, never whole in memory:
 * Android reads them when the spooler asks for the document, desktop before its print dialog.
 */
fun interface PrintSource {
    /**
     * Stream the bytes into [sink]; false when they turned out not to be the document's (or could not
     * be read), and what [sink] got must be thrown away. A [sink] that throws stops the read.
     */
    suspend fun stream(sink: (ByteArray, Int) -> Unit): Boolean
}

/**
 * How a document reaches a printer: the platform's print service, bound to the window that asked
 * ([rememberPdfPrinter]); tests pass a recording fake.
 */
fun interface PdfPrinter {
    /**
     * Print the document [source] reads as [fileName]. Only called for a PDF the library still holds,
     * and never mutates or replaces the document's file. [pageCount] is its page count when the index
     * knows it: Android uses it to let the system's print dialog offer a page range, and desktop
     * reads the count from the PDF itself. Expected failures are the [PrintOutcome] it returns.
     */
    suspend fun print(
        fileName: String,
        source: PrintSource,
        pageCount: Int?,
    ): PrintOutcome
}

/** The platform's print service for the window this composition is in. */
@Composable
expect fun rememberPdfPrinter(): PdfPrinter
