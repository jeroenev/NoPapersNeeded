package dev.npn

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.npn.app.platform.DocumentScanner
import dev.npn.app.platform.ScannedPdf
import dev.npn.core.fs.ExternalSource
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CompletableDeferred

/**
 * [DocumentScanner] backed by the MLKit / Google Play services document scanner
 * (SCANNER_MODE_FULL, PDF output). The activity owns the [ActivityResultLauncher]; this class
 * bridges the callback-based ActivityResult API to a suspend function via a [CompletableDeferred].
 *
 * Only one scan can be in flight at a time (a new scan while another is pending cancels the
 * previous one with null). Cancellation by the user resolves [scanToPdf] to null.
 */
class MlKitDocumentScanner(
    private val activity: ComponentActivity,
) : DocumentScanner {
    override val available: Boolean = true

    private var pending: CompletableDeferred<GmsDocumentScanningResult?>? = null

    private val launcher: ActivityResultLauncher<IntentSenderRequest> =
        activity.registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result: ActivityResult ->
            val deferred = pending
            pending = null
            if (deferred == null) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK) {
                val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
                deferred.complete(scan)
            } else {
                // Cancelled or failed.
                deferred.complete(null)
            }
        }

    override suspend fun scanToPdf(): ScannedPdf? {
        // Cancel any previous pending scan.
        pending?.complete(null)

        val options =
            GmsDocumentScannerOptions
                .Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .setGalleryImportAllowed(true)
                .build()

        val deferred = CompletableDeferred<GmsDocumentScanningResult?>()
        pending = deferred

        val started =
            runCatchingCancellable {
                val intentSender =
                    GmsDocumentScanning
                        .getClient(options)
                        .getStartScanIntent(activity)
                        .await()
                launcher.launch(IntentSenderRequest.Builder(intentSender).build())
            }
        if (started.isFailure) {
            pending = null
            return null
        }

        val result = deferred.await() ?: return null
        val pdfUri = result.pdf?.uri ?: return null
        return ScannedPdf(ExternalSource(pdfUri), "scan.pdf")
    }
}

/** Await a Google Play services [com.google.android.gms.tasks.Task] from a coroutine. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T {
    val deferred = CompletableDeferred<T>()
    addOnSuccessListener { deferred.complete(it) }
    addOnFailureListener { deferred.completeExceptionally(it) }
    addOnCanceledListener { deferred.cancel() }
    return deferred.await()
}
