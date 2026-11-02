package dev.npn.core.sync

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.holds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Executes the remote half of a plan. Every move and delete is guarded twice: the entry must still
 * be exactly what the listing showed (size and mtime), and a move's destination must still be
 * free — checked with a fresh `stat` right before the call, which closes most of the window between
 * planning and acting. A guard that fails is a conflict, not an error: the remote changed under us
 * and the next pass will plan against what is there now.
 */
class RemotePusher(
    private val fs: LibraryFileSystem,
    private val staging: LocalStaging,
    private val store: RemoteStore,
    private val manifest: SyncManifest,
    private val nowMillis: Long,
) {
    suspend fun push(
        actions: List<SyncAction.Remote>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ) {
        for ((index, action) in actions.withIndex()) {
            onProgress(index, actions.size)
            report.attempt(pathOf(action)) { pushOne(action, report) }
        }
    }

    private fun pathOf(action: SyncAction.Remote): String =
        when (action) {
            is SyncAction.UploadDocument -> action.relPath
            is SyncAction.MoveRemote -> action.from
            is SyncAction.DeleteRemote -> action.path
            is SyncAction.UploadTrash -> action.relPath
        }

    private suspend fun pushOne(
        action: SyncAction.Remote,
        report: SyncReportBuilder,
    ) {
        when (action) {
            is SyncAction.UploadDocument -> uploadDocument(action, report)
            is SyncAction.MoveRemote -> move(action, report)
            is SyncAction.DeleteRemote -> delete(action, report)
            is SyncAction.UploadTrash -> uploadTrash(action, report)
        }
    }

    private suspend fun uploadDocument(
        action: SyncAction.UploadDocument,
        report: SyncReportBuilder,
    ) {
        if (store.stat(action.remotePath) != null) {
            report.conflicts += SyncConflict(action.docId, action.remotePath, ConflictKind.REMOTE_CHANGED)
            return
        }
        // Planned under a new name: the move may have found the name taken this pass, by a file that
        // is not this document. Read whichever name holds it (rare, so the hash is affordable).
        val source =
            action.formerRelPath
                ?.takeIf { !fs.holds(action.relPath, action.docId) && fs.holds(it, action.docId) }
                ?: action.relPath
        val entry = uploadVerified(source, action.remotePath, action.docId, report) ?: return
        manifest.record(
            ManifestEntry(
                action.remotePath,
                ManifestKind.DOCUMENT,
                entry.size,
                entry.mtimeMillis,
                action.docId,
                localPath = source,
            ),
            nowMillis,
        )
        report.uploadedDocuments++
    }

    private suspend fun uploadTrash(
        action: SyncAction.UploadTrash,
        report: SyncReportBuilder,
    ) {
        if (store.stat(action.relPath) != null) return
        val entry = uploadVerified(action.relPath, action.relPath, action.docId, report) ?: return
        manifest.record(
            ManifestEntry(action.relPath, ManifestKind.TRASH, entry.size, entry.mtimeMillis, action.docId),
            nowMillis,
        )
        report.uploadedObjects++
    }

    /**
     * Copy the library file out to staging chunk by chunk (a large scan never sits in memory whole),
     * confirm the copy still is [docId] when one is expected, and upload it. Returns the remote's own
     * view of the uploaded file (the size/mtime to guard by), or null — with a conflict reported —
     * when the local file no longer holds the document. A remote file of another size than the copy
     * is an upload cut short on a backend that writes in place: it is removed, never left for
     * another replica to take for a new document.
     */
    private suspend fun uploadVerified(
        relPath: String,
        remotePath: String,
        docId: String?,
        report: SyncReportBuilder,
    ): RemoteEntry? {
        val scratch = staging.newPath(".pdf")
        try {
            staging.writeStreaming(scratch) { sink -> fs.readChunked(relPath, sink) }
            if (docId != null && staging.sha256(scratch) != docId) {
                report.conflicts += SyncConflict(docId, remotePath, ConflictKind.LOCAL_CHANGED)
                return null
            }
            val size = staging.size(scratch)
            store.upload(scratch, remotePath)
            val uploaded =
                store.stat(remotePath) ?: throw RemoteRaceException("Uploaded file not found on remote: $remotePath")
            if (uploaded.size != size) {
                store.delete(remotePath)
                throw RemoteRaceException("Upload cut short: $remotePath has ${uploaded.size} of $size bytes")
            }
            return uploaded
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    private suspend fun move(
        action: SyncAction.MoveRemote,
        report: SyncReportBuilder,
    ) {
        if (!stillExpected(action.from, action.expected)) {
            report.conflicts += SyncConflict(action.docId, action.from, ConflictKind.REMOTE_CHANGED)
            return
        }
        // A case-only rename on a backend that folds case finds the file itself at the destination;
        // rclone renames it in place.
        if (!action.from.equals(action.to, ignoreCase = true) && store.stat(action.to) != null) {
            report.conflicts += SyncConflict(action.docId, action.to, ConflictKind.TARGET_OCCUPIED)
            return
        }
        store.move(action.from, action.to)
        val moved = store.stat(action.to) ?: throw RemoteRaceException("Moved file not found on remote: ${action.to}")
        val kind =
            if (SyncPaths.classify(action.to) ==
                SyncPathClass.Trash
            ) {
                ManifestKind.TRASH
            } else {
                ManifestKind.DOCUMENT
            }
        manifest.remove(action.from)
        manifest.record(
            ManifestEntry(action.to, kind, moved.size, moved.mtimeMillis, action.docId, action.localPath),
            nowMillis,
        )
        report.movedRemote++
    }

    private suspend fun delete(
        action: SyncAction.DeleteRemote,
        report: SyncReportBuilder,
    ) {
        if (!stillExpected(action.path, action.expected)) {
            report.conflicts += SyncConflict(action.docId, action.path, ConflictKind.REMOTE_CHANGED)
            return
        }
        store.delete(action.path)
        manifest.remove(action.path)
        report.deletedRemote++
    }

    private suspend fun stillExpected(
        path: String,
        expected: RemoteEntry,
    ): Boolean {
        val current = store.stat(path) ?: return false
        return current.size == expected.size && current.mtimeMillis == expected.mtimeMillis
    }
}
