package dev.npn

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import dev.npn.app.platform.DocumentScanner
import dev.npn.app.platform.ScanResult
import dev.npn.app.platform.ScannedPdf
import dev.npn.core.fs.ExternalSource
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.warn

private const val SCANNED_PDF_NAME = "scan.pdf"

/**
 * [DocumentScanner] backed by the ML Kit / Google Play services document scanner (full mode, PDF
 * output, gallery import allowed).
 *
 * The client hands back an intent sender for the scanner activity, which is started through an
 * [IntentSenderRequest] on a launcher registered with [rememberLauncherForActivityResult]. Its
 * result is therefore delivered to the launcher a recreated activity registers again, rather than
 * to a coroutine that kept the old activity waiting.
 */
object MlKitDocumentScanner : DocumentScanner {
    private val options =
        GmsDocumentScannerOptions
            .Builder()
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setGalleryImportAllowed(true)
            .build()

    @Composable
    override fun rememberLauncher(onResult: (ScanResult) -> Unit): () -> Unit {
        val activity = LocalActivity.current
        val currentOnResult by rememberUpdatedState(onResult)
        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                currentOnResult(it.toScanResult())
            }
        return remember(activity, launcher) {
            {
                if (activity == null) {
                    currentOnResult(ScanResult.Failed)
                } else {
                    // Listeners scoped to the activity are dropped when it stops, so a scanner that
                    // is slow to hand back its intent cannot keep a finished activity alive. The
                    // plain listeners below see every handoff; one that no activity-scoped listener
                    // delivered is logged, since the tap would otherwise end with no result and no
                    // trace at all.
                    var delivered = false
                    GmsDocumentScanning
                        .getClient(options)
                        .getStartScanIntent(activity)
                        .also { handoff ->
                            handoff.addOnSuccessListener(activity) {
                                delivered = true
                                launcher.launch(IntentSenderRequest.Builder(it).build())
                            }
                            handoff.addOnFailureListener(activity) {
                                delivered = true
                                currentOnResult(ScanResult.Failed)
                            }
                            handoff.addOnSuccessListener {
                                if (!delivered) {
                                    AndroidLog.warn(
                                        "Scan handoff arrived after the activity went; nothing was scanned",
                                    )
                                }
                            }
                            handoff.addOnFailureListener {
                                if (!delivered) AndroidLog.warn("Scan handoff failed after the activity went", it)
                            }
                        }
                }
            }
        }
    }
}

private fun ActivityResult.toScanResult(): ScanResult {
    if (resultCode != Activity.RESULT_OK) return ScanResult.Cancelled
    val pdf = GmsDocumentScanningResult.fromActivityResultIntent(data)?.pdf ?: return ScanResult.Failed
    return ScanResult.Scanned(ScannedPdf(ExternalSource(pdf.uri), SCANNED_PDF_NAME))
}
