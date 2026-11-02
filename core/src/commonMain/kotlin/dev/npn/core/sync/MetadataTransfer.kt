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
 * written out to staging first so the transport only ever reads plain files.
 */
class MetadataTransfer(
    private val fs: LibraryFileSystem,
    private val staging: LocalStaging,
    private val store: RemoteStore,
    private val installationId: String,
    private val ids: IdSource,
) {
    /** Returns how many files were published locally. */
    suspend fun pull(
        actions: List<SyncAction.DownloadObject>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        var published = 0
        for ((index, action) in actions.withIndex()) {
            onProgress(index, actions.size)
            report.attempt(action.entry.path) {
                if (download(action)) {
                    published++
                    report.downloadedObjects++
                }
            }
        }
        return published
    }

    suspend fun push(
        actions: List<SyncAction.UploadObject>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ) {
        for ((index, action) in actions.withIndex()) {
            onProgress(index, actions.size)
            report.attempt(action.relPath) {
                upload(action.relPath)
                report.uploadedObjects++
            }
        }
    }

    /** True when the file was newly published; false when it turned out to be present already. */
    private suspend fun download(action: SyncAction.DownloadObject): Boolean {
        val path = action.entry.path
        if (fs.exists(path)) return false
        val scratch = staging.newPath(".part")
        try {
            store.download(path, scratch)
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
