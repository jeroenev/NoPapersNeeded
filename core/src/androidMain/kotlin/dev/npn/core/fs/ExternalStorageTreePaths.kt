package dev.npn.core.fs

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Map a Storage Access Framework tree uri back to the plain filesystem path it stands for, when
 * there is one. The system document picker is the only folder picker Android offers, but the
 * direct-filesystem library needs a path, not a tree uri; for the built-in external-storage and
 * downloads providers the document id encodes that path in a stable, well-known shape:
 *
 * - `com.android.externalstorage.documents`: `primary:Documents/NPN`, `home:NPN` (the Documents
 *   root on some vendors) and `1234-ABCD:NPN` (a removable volume by its FAT uuid).
 * - `com.android.providers.downloads.documents`: `downloads` (the Download root) and
 *   `raw:/storage/emulated/0/Download/NPN`.
 *
 * Every other provider (Drive, Dropbox, Nextcloud, ...) has no path and resolves to null: those
 * folders are only reachable through the SAF library implementation.
 */
object ExternalStorageTreePaths {
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

    private const val PRIMARY_VOLUME = "primary"
    private const val HOME_VOLUME = "home"
    private const val DOWNLOADS_ROOT_ID = "downloads"
    private const val RAW_PREFIX = "raw:"

    /**
     * Pure resolution, so it can be verified on a host JVM: [primaryRoot] is the shared storage
     * root (`/storage/emulated/0`) and [volumeRoot] maps a removable volume uuid to its mount.
     * Returns an absolute path with no trailing slash, or null when the id has no path meaning.
     */
    fun resolve(
        authority: String,
        treeDocumentId: String,
        primaryRoot: String,
        volumeRoot: (String) -> String = { "/storage/$it" },
    ): String? =
        when (authority) {
            EXTERNAL_STORAGE_AUTHORITY -> resolveExternalStorage(treeDocumentId, primaryRoot, volumeRoot)
            DOWNLOADS_AUTHORITY -> resolveDownloads(treeDocumentId, primaryRoot)
            else -> null
        }

    private fun resolveExternalStorage(
        docId: String,
        primaryRoot: String,
        volumeRoot: (String) -> String,
    ): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val rel = docId.substring(colon + 1)
        val root =
            when (volume) {
                PRIMARY_VOLUME -> primaryRoot
                HOME_VOLUME -> "$primaryRoot/Documents"
                else -> if (volume.all { it.isLetterOrDigit() || it == '-' || it == '_' }) volumeRoot(volume) else null
            } ?: return null
        return join(root, rel)
    }

    private fun resolveDownloads(
        docId: String,
        primaryRoot: String,
    ): String? =
        when {
            docId == DOWNLOADS_ROOT_ID -> {
                "$primaryRoot/Download"
            }

            docId.startsWith(RAW_PREFIX) -> {
                val raw = docId.removePrefix(RAW_PREFIX)
                if (raw.startsWith("/") && NpnPaths.isSafe(raw)) raw.trimEnd('/').ifEmpty { "/" } else null
            }

            else -> {
                null
            }
        }

    private fun join(
        root: String,
        rel: String,
    ): String? {
        val trimmed = rel.trim('/')
        // A picked tree must stay where the provider says it is.
        if (!NpnPaths.isSafe(trimmed)) return null
        val base = root.trimEnd('/')
        return if (trimmed.isEmpty()) base else "$base/$trimmed"
    }

    /**
     * Resolve a tree uri from `ACTION_OPEN_DOCUMENT_TREE` to an existing directory path, or null
     * when the provider is not path-backed or the mapped directory is not there.
     */
    fun localDirectoryForTreeUri(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
        return pathOf(uri.authority.orEmpty(), docId)?.takeIf { File(it).isDirectory }
    }

    /** [resolve] against this device's primary external storage. */
    fun pathOf(
        authority: String,
        docId: String,
    ): String? {
        @Suppress("DEPRECATION")
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return resolve(authority, docId, primary)
    }
}
