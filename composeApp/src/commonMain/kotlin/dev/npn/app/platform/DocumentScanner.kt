package dev.npn.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import dev.npn.core.fs.ExternalSource

/** A scanner-produced PDF that can be streamed from its platform-owned external location. */
data class ScannedPdf(
    val source: ExternalSource,
    val suggestedName: String,
)

/** What a scan came to. */
sealed interface ScanResult {
    data class Scanned(
        val pdf: ScannedPdf,
    ) : ScanResult

    /** The user backed out of the scanner; nothing to say. */
    data object Cancelled : ScanResult

    /** The scanner could not start, or came back without a PDF. */
    data object Failed : ScanResult
}

/**
 * Camera document scanning, provided by the platform entry point via [LocalDocumentScanner]:
 * Android's ML Kit document scanner, none on desktop (import and drag-and-drop instead).
 *
 * The scanner is an activity of its own, which this one may be recreated behind (rotation, process
 * death), so a scan is not a suspend call holding the activity: [rememberLauncher] registers with
 * the composition, and the result goes to the `onResult` of whichever composition registered it
 * last.
 */
interface DocumentScanner {
    /** Register for scan results, which go to [onResult]; returns what starts a scan. */
    @Composable
    fun rememberLauncher(onResult: (ScanResult) -> Unit): () -> Unit
}

/** The platform's [DocumentScanner], or null where there is none. */
val LocalDocumentScanner = staticCompositionLocalOf<DocumentScanner?> { null }
