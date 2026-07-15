package dev.npn

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.npn.app.di.AppEnvironment
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibrarySession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Reads content URIs delivered by ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW intents and
 * imports them through the opened [LibrarySession]. PDFs import as-is; images are combined into a
 * single PDF via the core PdfEngine (importImagesAsPdf). Import only happens when a library is
 * configured; otherwise the caller shows onboarding and the intent is ignored.
 */
object IntentImport {

    data class Result(val imported: Int, val duplicates: Int, val failed: Int, val total: Int)

    /** Extract shareable content URIs from a SEND / SEND_MULTIPLE / VIEW intent. */
    fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> {
            val uri = intent.getParcelableExtraCompat<Uri>(Intent.EXTRA_STREAM)
            listOfNotNull(uri)
        }

        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtraCompat<Uri>(Intent.EXTRA_STREAM) ?: emptyList()

        Intent.ACTION_VIEW -> listOfNotNull(intent.data)

        else -> emptyList()
    }

    /** True if [intent] is one we import from. */
    fun isImportIntent(intent: Intent?): Boolean = when (intent?.action) {
        Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_VIEW -> true
        else -> false
    }

    /**
     * Import all content URIs in [intent]. Returns null if no library is configured (caller shows a
     * toast / onboarding), otherwise a [Result] summary. Images across the intent are merged into a
     * single PDF; PDFs are imported one per file.
     */
    suspend fun import(context: Context, env: AppEnvironment, intent: Intent): Result? {
        val session = env.session.first() ?: return null
        val uris = extractUris(intent)
        if (uris.isEmpty()) return Result(0, 0, 0, 0)

        val resolver = context.contentResolver
        val pdfUris = mutableListOf<Uri>()
        val imageBytes = mutableListOf<ByteArray>()

        for (uri in uris) {
            val type = resolver.getType(uri) ?: guessTypeFromName(resolver, uri)
            when {
                type == "application/pdf" || type?.endsWith("/pdf") == true ->
                    pdfUris.add(uri)

                type?.startsWith("image/") == true ->
                    readBytes(resolver, uri)?.let { imageBytes.add(it) }

                // Unknown type: try by extension.
                displayName(resolver, uri)?.endsWith(".pdf", ignoreCase = true) == true ->
                    pdfUris.add(uri)

                else -> { /* unsupported, skip */ }
            }
        }

        var imported = 0
        var duplicates = 0
        var failed = 0
        var total = 0

        for (uri in pdfUris) {
            total++
            val bytes = readBytes(resolver, uri)
            if (bytes == null) {
                failed++
                continue
            }
            when (session.importPdfBytes(bytes, displayName(resolver, uri) ?: "shared.pdf")) {
                is ImportOutcome.Imported -> imported++
                is ImportOutcome.Duplicate -> duplicates++
                is ImportOutcome.Failed -> failed++
            }
        }

        if (imageBytes.isNotEmpty()) {
            total++
            when (session.importImagesAsPdf(imageBytes, "scan.pdf")) {
                is ImportOutcome.Imported -> imported++
                is ImportOutcome.Duplicate -> duplicates++
                is ImportOutcome.Failed -> failed++
            }
        }

        return Result(imported, duplicates, failed, total)
    }

    private suspend fun readBytes(resolver: ContentResolver, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else {
                null
            }
        }
    }.getOrNull() ?: uri.lastPathSegment

    private fun guessTypeFromName(resolver: ContentResolver, uri: Uri): String? {
        val name = displayName(resolver, uri) ?: return null
        return when {
            name.endsWith(".pdf", ignoreCase = true) -> "application/pdf"
            name.endsWith(".png", ignoreCase = true) -> "image/png"
            name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            name.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> null
        }
    }
}

private inline fun <reified T> Intent.getParcelableExtraCompat(key: String): T? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }

private inline fun <reified T : android.os.Parcelable> Intent.getParcelableArrayListExtraCompat(key: String): ArrayList<T>? =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
