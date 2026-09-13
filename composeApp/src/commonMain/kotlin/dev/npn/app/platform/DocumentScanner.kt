package dev.npn.app.platform

import androidx.compose.runtime.staticCompositionLocalOf
import dev.npn.core.fs.ExternalSource

/** A scanner-produced PDF that can be streamed from its platform-owned external location. */
data class ScannedPdf(
    val source: ExternalSource,
    val suggestedName: String,
)

/**
 * Camera document scanning, provided by the platform entry point via [LocalDocumentScanner].
 * Android: MLKit document scanner. Desktop: unavailable in v1 (import/drag-drop instead).
 */
interface DocumentScanner {
    val available: Boolean

    /**
     * Launch the scan flow and return a handle to the scanned PDF,
     * or null if the user cancelled.
     */
    suspend fun scanToPdf(): ScannedPdf?
}

object NoDocumentScanner : DocumentScanner {
    override val available: Boolean = false

    override suspend fun scanToPdf(): ScannedPdf? = null
}

val LocalDocumentScanner = staticCompositionLocalOf<DocumentScanner> { NoDocumentScanner }
