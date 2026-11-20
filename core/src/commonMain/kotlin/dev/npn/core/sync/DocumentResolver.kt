package dev.npn.core.sync

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Names the document behind every remote PDF before anything is planned around it.
 *
 * Identity is SHA-256, so a remote entry is identified by, in order of cost: the manifest (same
 * path, same size and mtime as when it was last identified), the backend's own SHA-256 when the
 * listing carries one, Dropbox's content hash matching a library file of the same size (whose
 * SHA-256, read in the same pass over it, is then the remote file's: [DropboxContentHash]), and
 * finally a download, [TransferLimits.documents] at a time. A downloaded file the library has no row for is kept
 * in staging for the planner's [SyncAction.PublishDocument] to install, so a new document is
 * transferred once; one the library already holds is dropped at once, so identifying a remote full
 * of known documents (a second device's first pass) never fills the disk with copies of them.
 *
 * Trash entries are identified without downloading: by manifest, by backend hash, or by the hash
 * prefix in their name ([NpnPaths.trashHashPrefix]) when exactly one local row of that size has it.
 */
class DocumentResolver(
    private val fs: LibraryFileSystem,
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
        val known = localRows.mapTo(HashSet()) { it.docId }
        val pending = documents.filter { !manifest[it.path].identifies(it, ManifestKind.DOCUMENT) }
        var done = documents.size - pending.size
        onProgress(done, documents.size)
        val bySize = localRows.groupBy { it.size }
        val hashed = HashMap<String, Pair<String, String>?>()
        val toDownload = ArrayList<RemoteEntry>()
        for (entry in pending) {
            val docId = entry.sha256 ?: entry.dropboxHash?.let { matchLocal(it, bySize[entry.size].orEmpty(), hashed) }
            if (docId == null) {
                toDownload += entry
                continue
            }
            record(entry, docId, nowMillis)
            onProgress(++done, documents.size)
        }
        transferEach(toDownload, store.limits.documents, ::downloadAndHash) { entry, outcome ->
            report.attempt(entry.path) {
                val (scratch, hash) = outcome.getOrThrow()
                if (hash in known) {
                    withContext(NonCancellable) { staging.delete(scratch) }
                } else {
                    downloads[entry.path] = scratch
                }
                record(entry, hash, nowMillis)
            }
            onProgress(++done, documents.size)
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

    private suspend fun record(
        entry: RemoteEntry,
        docId: String,
        nowMillis: Long,
    ) = manifest.record(
        ManifestEntry(entry.path, ManifestKind.DOCUMENT, entry.size, entry.mtimeMillis, docId),
        nowMillis,
    )

    /**
     * The SHA-256 of the library file among [candidates] (rows of the remote file's size) whose
     * Dropbox content hash is [dropboxHash], or null when none has it. Each file is read once a
     * pass ([hashed]); one that cannot be read is no match, and the remote file is downloaded.
     */
    private suspend fun matchLocal(
        dropboxHash: String,
        candidates: List<DocumentEntity>,
        hashed: MutableMap<String, Pair<String, String>?>,
    ): String? {
        for (row in candidates) {
            val hashes =
                hashed.getOrPut(row.relPath) {
                    runCatchingCancellable { fs.sha256AndDropboxHash(row.relPath) }.getOrNull()
                }
            if (hashes?.second == dropboxHash) return hashes.first
        }
        return null
    }

    /** Download [entry] to a scratch file and hash it: the scratch path and the file's SHA-256. */
    private suspend fun downloadAndHash(entry: RemoteEntry): Pair<String, String> {
        val scratch = staging.newPath(".pdf")
        var fetched = false
        try {
            store.download(entry.path, scratch)
            val size = staging.size(scratch)
            if (size != entry.size) {
                throw RemoteRaceException("Downloaded ${size ?: "unknown"} bytes, listing said ${entry.size}")
            }
            val hash = staging.sha256(scratch)
            fetched = true
            return scratch to hash
        } finally {
            if (!fetched) withContext(NonCancellable) { staging.delete(scratch) }
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
