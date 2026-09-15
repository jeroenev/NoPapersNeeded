package dev.npn.core.sync

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Names the document behind every remote PDF before anything is planned around it.
 *
 * Identity is SHA-256, so a remote entry is identified by, in order of cost: the manifest (same
 * path, same size and mtime as when it was last identified), the backend's own SHA-256 when the
 * listing carries one, and finally a download. A downloaded file is kept in staging for the
 * planner's [SyncAction.PublishDocument] to install, so a new document is transferred once.
 *
 * Trash entries are identified without downloading: by manifest, by backend hash, or by the hash
 * prefix in their name ([NpnPaths.trashHashPrefix]) when exactly one local row of that size has it.
 */
class DocumentResolver(
    private val store: RemoteStore,
    private val staging: LocalStaging,
    private val manifest: SyncManifest,
) {
    /** Downloaded-but-not-yet-published PDFs, by remote path, for reuse when the plan publishes them. */
    val downloads = LinkedHashMap<String, String>()

    suspend fun resolve(
        remote: RemoteIndex,
        localRows: List<DocumentEntity>,
        nowMillis: Long,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ) {
        val documents = remote.documents.values.toList()
        for ((index, entry) in documents.withIndex()) {
            onProgress(index, documents.size)
            if (manifest[entry.path].identifies(entry, ManifestKind.DOCUMENT)) continue
            report.attempt(entry.path) {
                val docId = entry.sha256 ?: downloadAndHash(entry)
                manifest.record(
                    ManifestEntry(entry.path, ManifestKind.DOCUMENT, entry.size, entry.mtimeMillis, docId),
                    nowMillis,
                )
            }
        }
        for (entry in remote.trash.values) {
            val known = manifest[entry.path]
            if (known != null && known.kind == ManifestKind.TRASH && known.matches(entry)) continue
            // A row whose size or mtime moved says nothing about the bytes there now: the old
            // identity is dropped, never inherited.
            val docId = entry.sha256 ?: trashIdentity(entry, localRows)
            manifest.record(
                ManifestEntry(entry.path, ManifestKind.TRASH, entry.size, entry.mtimeMillis, docId),
                nowMillis,
            )
        }
    }

    /** A manifest row still names the document behind [entry]: same kind, an identity, unchanged size/mtime. */
    private fun ManifestEntry?.identifies(
        entry: RemoteEntry,
        kind: ManifestKind,
    ): Boolean {
        if (this == null || this.kind != kind) return false
        return docId != null && matches(entry)
    }

    private suspend fun downloadAndHash(entry: RemoteEntry): String {
        val scratch = staging.newPath(".pdf")
        var kept = false
        try {
            store.download(entry.path, scratch)
            val size = staging.size(scratch)
            check(size == entry.size) { "Downloaded ${size ?: "unknown"} bytes, listing said ${entry.size}" }
            val hash = staging.sha256(scratch)
            downloads[entry.path] = scratch
            kept = true
            return hash
        } finally {
            if (!kept) withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    private fun trashIdentity(
        entry: RemoteEntry,
        localRows: List<DocumentEntity>,
    ): String? {
        val marker = NpnPaths.trashHashPrefix(NpnPaths.fileName(entry.path)) ?: return null
        return localRows
            .singleOrNull { it.docId.startsWith(marker) && it.size == entry.size }
            ?.docId
            ?.takeIf { it.length == NpnPaths.SHA256_HEX_LENGTH }
    }

    /** Drop every download the plan did not consume. */
    suspend fun cleanup() {
        for (scratch in downloads.values) runCatchingCancellable { staging.delete(scratch) }
        downloads.clear()
    }
}
