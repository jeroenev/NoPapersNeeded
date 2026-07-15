package dev.npn.app.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Camera document scanning, provided by the platform entry point via [LocalDocumentScanner].
 * Android: MLKit document scanner. Desktop: unavailable in v1 (import/drag-drop instead).
 */
interface DocumentScanner {
    val available: Boolean

    /**
     * Launch the scan flow and return the scanned document as PDF bytes,
     * or null if the user cancelled.
     */
    suspend fun scanToPdf(): ByteArray?
}

object NoDocumentScanner : DocumentScanner {
    override val available: Boolean = false
    override suspend fun scanToPdf(): ByteArray? = null
}

val LocalDocumentScanner = staticCompositionLocalOf<DocumentScanner> { NoDocumentScanner }
