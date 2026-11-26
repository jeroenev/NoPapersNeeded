package dev.npn.app.platform

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream

/** Android's print service, opened from the activity this composition is in. */
@Composable
actual fun rememberPdfPrinter(): PdfPrinter {
    val activity = LocalActivity.current
    return remember(activity) {
        PdfPrinter { fileName, source, pageCount -> printPdf(activity, fileName, source, pageCount) }
    }
}

/**
 * Hands the document to Android's print spooler, which asks for its bytes ([StreamingPrintAdapter])
 * once the user picks a printer. The system owns the print dialog from the moment
 * [PrintManager.print] returns, so a dismissal afterwards is not reported back to us.
 */
private suspend fun printPdf(
    activity: Activity?,
    fileName: String,
    source: PrintSource,
    pageCount: Int?,
    io: CoroutineDispatcher = Dispatchers.IO,
): PrintOutcome =
    withContext(Dispatchers.Main) {
        try {
            val manager =
                activity?.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: return@withContext PrintOutcome.Failed(PrintFailure.UNSUPPORTED)
            LockActivityHandoff.started()
            manager.print(
                fileName,
                StreamingPrintAdapter(fileName, source, pageCount, io),
                PrintAttributes.Builder().build(),
            )
            PrintOutcome.Started
        } catch (failure: Exception) {
            LockActivityHandoff.clear()
            PrintOutcome.Failed(PrintFailure.PRINT_FAILED, failure)
        }
    }

/**
 * A [PrintDocumentAdapter] that streams the document from the library into the spooler's file as it
 * reads it: no copy in memory, no temporary file and no second copy in the library. The page count
 * comes from the document's index row when it has been read, so the system dialog can offer a page
 * range; otherwise it is unknown. Its writes run in its own scope, which ends with the print job
 * ([onFinish]).
 */
private class StreamingPrintAdapter(
    private val fileName: String,
    private val source: PrintSource,
    private val pageCount: Int?,
    private val io: CoroutineDispatcher,
) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback?,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback?.onLayoutCancelled()
            return
        }
        val info =
            PrintDocumentInfo
                .Builder(fileName)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pageCount ?: PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
        callback?.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor?,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback?,
    ) {
        if (destination == null) {
            callback?.onWriteFailed(null)
            return
        }
        scope.launch {
            val written =
                withContext(io) {
                    runCatchingCancellable {
                        ParcelFileDescriptor.AutoCloseOutputStream(destination).use { out ->
                            writePrintStream(source, out) { cancellationSignal?.isCanceled == true }
                        }
                    }
                }
            written.fold(
                onSuccess = { result ->
                    when (result) {
                        PrintWrite.WRITTEN -> callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                        PrintWrite.NOT_THE_DOCUMENT -> callback?.onWriteFailed(null)
                        PrintWrite.CANCELLED -> callback?.onWriteCancelled()
                    }
                },
                onFailure = { callback?.onWriteFailed(it.message) },
            )
        }
    }

    /** The print job is over, printed or not: nothing of it is left to write. */
    override fun onFinish() {
        scope.cancel()
    }
}

/** How [writePrintStream] ended. */
internal enum class PrintWrite {
    /** The whole document reached the spooler. */
    WRITTEN,

    /** The bytes read were not the document's, or could not be read: what was written is not it. */
    NOT_THE_DOCUMENT,

    /** The print was cancelled; the copy stopped at the chunk it was on. */
    CANCELLED,
}

/** Stops a copy the print system cancelled; a cancellation, so the library's read lets it through. */
private class PrintCancelled : CancellationException("The print was cancelled")

/**
 * Copy the document [source] reads into [out], each chunk as the library reads it, never the whole
 * file at once. [cancelled] is asked before each chunk, so a cancelled print stops copying there. A
 * failure to write throws.
 */
internal suspend fun writePrintStream(
    source: PrintSource,
    out: OutputStream,
    cancelled: () -> Boolean,
): PrintWrite =
    try {
        val ours =
            source.stream { bytes, length ->
                if (cancelled()) throw PrintCancelled()
                out.write(bytes, 0, length)
            }
        if (ours) PrintWrite.WRITTEN else PrintWrite.NOT_THE_DOCUMENT
    } catch (_: PrintCancelled) {
        PrintWrite.CANCELLED
    }
