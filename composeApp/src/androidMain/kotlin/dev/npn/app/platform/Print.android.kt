package dev.npn.app.platform

import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands the document's bytes to Android's print spooler. The system owns the print dialog from the
 * moment [PrintManager.print] returns, so a dismissal afterwards is not reported back to us.
 */
actual suspend fun printPdf(
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
): PrintOutcome =
    withContext(Dispatchers.Main) {
        try {
            val context = FileKit.context
            val manager =
                context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                    ?: return@withContext PrintOutcome.Failed(PrintFailure.UNSUPPORTED)
            manager.print(
                fileName,
                PdfBytesPrintAdapter(fileName, bytes),
                PrintAttributes.Builder().build(),
            )
            PrintOutcome.Started
        } catch (failure: Exception) {
            PrintOutcome.Failed(PrintFailure.PRINT_FAILED, failure)
        }
    }

/**
 * A [PrintDocumentAdapter] over the bytes in hand: no temporary file and no second copy in the
 * library. The page count is unknown to the adapter, as only a PDF parser in the app could tell it
 * and the spooler already understands the document.
 */
private class PdfBytesPrintAdapter(
    private val fileName: String,
    private val bytes: ByteArray,
) : PrintDocumentAdapter() {
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
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
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
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(destination).use { it.write(bytes) }
            if (cancellationSignal?.isCanceled == true) {
                callback?.onWriteCancelled()
            } else {
                callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            }
        } catch (failure: Exception) {
            if (cancellationSignal?.isCanceled == true) {
                callback?.onWriteCancelled()
            } else {
                callback?.onWriteFailed(failure.message)
            }
        }
    }
}
