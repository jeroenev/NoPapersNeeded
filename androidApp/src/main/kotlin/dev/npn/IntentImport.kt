package dev.npn

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.provider.OpenableColumns
import dev.npn.core.app.ImportTally
import dev.npn.core.app.LibrarySession
import dev.npn.core.fs.ExternalSource
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads content URIs delivered by ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW intents and
 * imports them through the opened [LibrarySession]. PDFs import as-is; images are combined into a
 * single PDF via the core PdfEngine (importImagesAsPdf). Finding the library is the caller's
 * ([dev.npn.app.platform.importIntoLibrary]).
 */
object IntentImport {
    private const val MIME_PDF = "application/pdf"
    private const val MIME_IMAGE_PREFIX = "image/"
    private const val MIME_PNG = "image/png"
    private const val MIME_JPEG = "image/jpeg"
    private const val MIME_WEBP = "image/webp"

    private const val EXT_PDF = ".pdf"
    private const val EXT_PNG = ".png"
    private const val EXT_JPG = ".jpg"
    private const val EXT_JPEG = ".jpeg"
    private const val EXT_WEBP = ".webp"

    private const val FALLBACK_SHARED_PDF = "shared.pdf"
    private const val FALLBACK_SCAN_PDF = "scan.pdf"

    /** Extract shareable content URIs from a SEND / SEND_MULTIPLE / VIEW intent. */
    fun extractUris(intent: Intent): List<Uri> =
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
                listOfNotNull(uri)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
            }

            Intent.ACTION_VIEW -> {
                listOfNotNull(intent.data)
            }

            else -> {
                emptyList()
            }
        }

    fun isImportIntent(intent: Intent?): Boolean =
        when (intent?.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW -> true
            else -> false
        }

    /**
     * Import all content URIs in [intent] into [session] and tally what became of them; an empty
     * tally means the intent carried nothing importable. Images across the intent are merged into a
     * single PDF; PDFs are imported one per file.
     */
    suspend fun import(
        context: Context,
        session: LibrarySession,
        intent: Intent,
    ): ImportTally {
        val uris = extractUris(intent)
        if (uris.isEmpty()) return ImportTally()

        val resolver = context.contentResolver
        val (pdfUris, imageBytes, unreadableImages) = partitionUris(resolver, uris)

        // An image that cannot be read is left out of the PDF the others make: it failed to import.
        var tally = ImportTally(failed = unreadableImages)

        // An import reports what went wrong with the file as an outcome; it throws only when
        // the session closed under it, which still counts against this file.
        for (uri in pdfUris) {
            tally +=
                runCatchingCancellable {
                    session.ingest.importPdf(ExternalSource(uri), displayName(resolver, uri) ?: FALLBACK_SHARED_PDF)
                }.getOrNull()
        }

        if (imageBytes.isNotEmpty()) {
            tally +=
                runCatchingCancellable { session.ingest.importImagesAsPdf(imageBytes, FALLBACK_SCAN_PDF) }.getOrNull()
        }

        return tally
    }

    /** What [partitionUris] made of the shared URIs. */
    private data class SharedItems(
        val pdfUris: List<Uri>,
        val imageBytes: List<ByteArray>,
        val unreadableImages: Int,
    )

    /**
     * Classify [uris] into PDF URIs (imported one per file) and image byte payloads (merged into a
     * single PDF), counting the images that could not be read. Classification is by MIME type,
     * falling back to the display-name extension.
     */
    private suspend fun partitionUris(
        resolver: ContentResolver,
        uris: List<Uri>,
    ): SharedItems {
        val pdfUris = mutableListOf<Uri>()
        val imageBytes = mutableListOf<ByteArray>()
        var unreadableImages = 0

        for (uri in uris) {
            val type = resolver.getType(uri) ?: guessTypeFromName(resolver, uri)
            when {
                type == MIME_PDF || type?.endsWith("/pdf") == true -> {
                    pdfUris.add(uri)
                }

                type?.startsWith(MIME_IMAGE_PREFIX) == true -> {
                    val bytes = readBytes(resolver, uri)
                    if (bytes != null) imageBytes.add(bytes) else unreadableImages++
                }

                // Unknown type: try by extension.
                displayName(resolver, uri)?.endsWith(EXT_PDF, ignoreCase = true) == true -> {
                    pdfUris.add(uri)
                }

                else -> { /* unsupported, skip */ }
            }
        }

        return SharedItems(pdfUris, imageBytes, unreadableImages)
    }

    private suspend fun readBytes(
        resolver: ContentResolver,
        uri: Uri,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }

    private fun displayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else null
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment

    private fun guessTypeFromName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? {
        val name = displayName(resolver, uri) ?: return null
        return when {
            name.endsWith(EXT_PDF, ignoreCase = true) -> MIME_PDF

            name.endsWith(EXT_PNG, ignoreCase = true) -> MIME_PNG

            name.endsWith(EXT_JPG, ignoreCase = true) ||
                name.endsWith(EXT_JPEG, ignoreCase = true) -> MIME_JPEG

            name.endsWith(EXT_WEBP, ignoreCase = true) -> MIME_WEBP

            else -> null
        }
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

private inline fun <reified T : Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
