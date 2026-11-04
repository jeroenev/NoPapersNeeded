package dev.npn.core.sync

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.publishStagedOnce
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPaths
import dev.npn.core.util.IdSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Moves the write-once metadata set between the library and the remote. Downloads are verified
 * before anything reaches a library path: a content-addressed object must hash to the name it
 * claims and is then published through [LibraryFileSystem.publishVerifiedObjectOnce]; a write-once
 * internal must match its listed size and is installed with a create-only import. Uploads are
 * written out to staging first so the transport only ever reads plain files. Transfers run several
 * at once ([transferEach]); verifying, publishing and the report take one file at a time.
 */
class MetadataTransfer(
    private val fs: LibraryFileSystem,
    private val staging: LocalStaging,
    private val store: RemoteStore,
    private val installationId: String,
    private val ids: IdSource,
) {
    /**
     * Returns how many files were published locally. Downloads run [TransferLimits.metadataDownloads] at
     * a time; each is verified and published on its own, one after another.
     */
    suspend fun pull(
        actions: List<SyncAction.DownloadObject>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        var published = 0
        var done = 0
        onProgress(0, actions.size)
        transferEach(actions, store.limits.metadataDownloads, ::fetch) { action, fetched ->
            report.attempt(action.entry.path) {
                if (publish(action, fetched.getOrThrow())) {
                    published++
                    report.downloadedObjects++
                }
            }
            onProgress(++done, actions.size)
        }
        return published
    }

    /** Uploads run [TransferLimits.metadataUploads] at a time. */
    suspend fun push(
        actions: List<SyncAction.UploadObject>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ) {
        var done = 0
        onProgress(0, actions.size)
        transferEach(actions, store.limits.metadataUploads, { upload(it.relPath) }) { action, uploaded ->
            report.attempt(action.relPath) {
                uploaded.getOrThrow()
                report.uploadedObjects++
            }
            onProgress(++done, actions.size)
        }
    }

    /** Download [action]'s file to a scratch file, or null when the library has it already. */
    private suspend fun fetch(action: SyncAction.DownloadObject): String? {
        if (fs.exists(action.entry.path)) return null
        val scratch = staging.newPath(".part")
        var fetched = false
        try {
            store.download(action.entry.path, scratch)
            fetched = true
            return scratch
        } finally {
            if (!fetched) withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    /** True when the file was newly published; false when it turned out to be present already. */
    private suspend fun publish(
        action: SyncAction.DownloadObject,
        scratch: String?,
    ): Boolean {
        if (scratch == null) return false
        val path = action.entry.path
        try {
            if (fs.exists(path)) return false
            val hash = action.hash
            return if (hash != null) publishObject(path, scratch, hash) else publishWriteOnce(action.entry, scratch)
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    private suspend fun publishObject(
        canonical: String,
        scratch: String,
        hash: String,
    ): Boolean {
        if (staging.sha256(scratch) != hash) {
            throw RemoteRaceException("Downloaded object does not hash to its name: $canonical")
        }
        val snapshot = (SyncPaths.classify(canonical) as? SyncPathClass.JournalObject)?.snapshot == true
        val stagingRel = JournalPaths.stagingPath(hash, installationId, ids.next(), snapshot)
        // The same object may arrive by another route meanwhile (a folder syncer beside the cloud):
        // a target that then holds its bytes is published all the same.
        fs.publishStagedOnce(stagingRel, canonical, hash, targetHeldCounts = true) {
            fs.importExternal(staging.externalSource(scratch), stagingRel)
        }
        return true
    }

    private suspend fun publishWriteOnce(
        entry: RemoteEntry,
        scratch: String,
    ): Boolean {
        val size = staging.size(scratch)
        if (size != entry.size) {
            throw RemoteRaceException("Downloaded file size ${size ?: "unknown"} differs from listing ${entry.size}")
        }
        // Two devices never write the same sidecar or segment name (the writer id is in the name),
        // so a target that appeared meanwhile is this same file arriving by another route.
        if (fs.exists(entry.path)) return false
        fs.importExternal(staging.externalSource(scratch), entry.path)
        return true
    }

    private suspend fun upload(relPath: String) {
        val bytes = fs.readBytes(relPath)
        val expectedHash = (SyncPaths.classify(relPath) as? SyncPathClass.JournalObject)?.hash
        if (expectedHash != null) {
            check(sha256Bytes(bytes) == expectedHash) { "Local object does not hash to its name: $relPath" }
        }
        val scratch = staging.newPath(".part")
        try {
            staging.write(scratch, bytes)
            store.upload(scratch, relPath)
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }
}
