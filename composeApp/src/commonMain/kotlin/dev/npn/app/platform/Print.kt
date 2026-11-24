package dev.npn.app.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * What a [printPdf] call came to.
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

    /** The bytes are not a PDF the platform's PDF library can read. */
    UNREADABLE_PDF,

    /** The print service refused or failed the job. */
    PRINT_FAILED,
}

/** How a document's bytes reach a printer. A viewmodel takes one; tests inject a recording fake. */
fun interface PdfPrinter {
    suspend fun print(
        fileName: String,
        bytes: ByteArray,
        /** The document's page count when the index knows it; the platform may need it to offer page ranges. */
        pageCount: Int?,
    ): PrintOutcome
}

/**
 * Print [bytes] as [fileName] through the platform's print service. Only called for a PDF the
 * library still holds, and never mutates or replaces the document's file.
 *
 * [pageCount] is the document's known page count, if any: Android uses it to let the system's
 * print dialog offer a page range, and desktop reads the count from the PDF itself.
 *
 * Expected failures are the [PrintOutcome] it returns; reading [bytes] from the library is the
 * caller's, through [dev.npn.core.app.DocContentFacade].
 */
expect suspend fun printPdf(
    fileName: String,
    bytes: ByteArray,
    pageCount: Int? = null,
    io: CoroutineDispatcher = Dispatchers.IO,
): PrintOutcome
