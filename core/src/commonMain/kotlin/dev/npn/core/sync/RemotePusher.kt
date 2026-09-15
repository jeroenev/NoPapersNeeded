package dev.npn.core.sync

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.readDocument
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
            report.conflicts += SyncConflict(action.docId, action.remotePath, "Remote path appeared before upload")
            return
        }
        val entry = uploadVerified(action.relPath, action.remotePath, action.docId, report) ?: return
        manifest.record(
            ManifestEntry(action.remotePath, ManifestKind.DOCUMENT, entry.size, entry.mtimeMillis, action.docId),
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
     * Read the library file, confirm it still is [docId] when one is expected, and upload it through
     * staging. Returns the remote's own view of the uploaded file (the size/mtime to guard by), or
     * null — with a conflict reported — when the local file no longer holds the document.
     */
    private suspend fun uploadVerified(
        relPath: String,
        remotePath: String,
        docId: String?,
        report: SyncReportBuilder,
    ): RemoteEntry? {
        val bytes = if (docId != null) fs.readDocument(relPath, docId) else fs.readBytes(relPath)
        if (bytes == null) {
            report.conflicts +=
                SyncConflict(docId, remotePath, "Local file no longer holds this document; not uploaded")
            return null
        }
        val scratch = staging.newPath(".pdf")
        try {
            staging.write(scratch, bytes)
            store.upload(scratch, remotePath)
            return store.stat(remotePath) ?: error("Uploaded file not found on remote: $remotePath")
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    private suspend fun move(
        action: SyncAction.MoveRemote,
        report: SyncReportBuilder,
    ) {
        if (!stillExpected(action.from, action.expected)) {
            report.conflicts += SyncConflict(action.docId, action.from, "Remote file changed since listing; not moved")
            return
        }
        if (store.stat(action.to) != null) {
            report.conflicts += SyncConflict(action.docId, action.to, "Remote destination is occupied; not moved")
            return
        }
        store.move(action.from, action.to)
        val moved = store.stat(action.to) ?: error("Moved file not found on remote: ${action.to}")
        val kind =
            if (SyncPaths.classify(action.to) ==
                SyncPathClass.Trash
            ) {
                ManifestKind.TRASH
            } else {
                ManifestKind.DOCUMENT
            }
        manifest.remove(action.from)
        manifest.record(ManifestEntry(action.to, kind, moved.size, moved.mtimeMillis, action.docId), nowMillis)
        report.movedRemote++
    }

    private suspend fun delete(
        action: SyncAction.DeleteRemote,
        report: SyncReportBuilder,
    ) {
        if (!stillExpected(action.path, action.expected)) {
            report.conflicts +=
                SyncConflict(action.docId, action.path, "Remote file changed since listing; not deleted")
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
