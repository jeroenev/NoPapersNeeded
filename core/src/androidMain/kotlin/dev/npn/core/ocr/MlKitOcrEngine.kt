package dev.npn.core.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android [OcrEngine] using MLKit text recognition v2 with the bundled offline Latin model. Always
 * available because the model ships with the app.
 *
 * The MLKit `Task` is awaited with [suspendCancellableCoroutine] to avoid adding
 * kotlinx-coroutines-play-services.
 *
 * Every failure throws rather than answering `""`: an empty string is a page with no text on it,
 * which the pipeline would publish to a write-once sidecar and never retry.
 */
class MlKitOcrEngine : OcrEngine {
    override val name: String = OcrEngines.MLKIT

    override suspend fun available(): Boolean = true

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        val bitmap =
            BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: throw IOException("page image could not be decoded (${png.size} bytes)")
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            recognizer
                .process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text)
                }.addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                }.addOnCanceledListener {
                    // Nothing here cancels the task, so this is MLKit giving up, not the caller:
                    // a failed page, not a cancelled coroutine.
                    if (cont.isActive) cont.resumeWithException(IOException("text recognition was cancelled"))
                }
        }
    }
}
