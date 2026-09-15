package dev.npn.core.sync

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.uniquifyPath
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Executes the local half of a plan: installs downloaded documents and mirrors the journal's
 * trash/delete decisions onto this replica's files, as a folder syncer would deliver another
 * device's moves; the rescan that follows projects them. Every step re-verifies the file's SHA-256
 * against the document it is supposed to be before moving or removing it, so a file that took the
 * same name meanwhile is left alone and reported rather than acted on, and a delete is decided
 * again under the journal's lock ([SyncHooks.deleteDeletedCopy]).
 */
class LocalApplier(
    private val fs: LibraryFileSystem,
    private val staging: LocalStaging,
    private val store: RemoteStore,
    private val downloads: MutableMap<String, String>,
    private val hooks: SyncHooks,
) {
    private val trashOps = TrashOps(fs)

    /** Returns how many actions changed the library folder. */
    suspend fun apply(
        actions: List<SyncAction.Local>,
        report: SyncReportBuilder,
        onProgress: (Int, Int) -> Unit,
    ): Int {
        var changed = 0
        for ((index, action) in actions.withIndex()) {
            onProgress(index, actions.size)
            val path = pathOf(action)
            report.attempt(path) {
                if (applyOne(action, report)) {
                    changed++
                    report.appliedLocally++
                }
            }
        }
        return changed
    }

    private fun pathOf(action: SyncAction.Local): String =
        when (action) {
            is SyncAction.PublishDocument -> action.remotePath
            is SyncAction.RestoreLocalTrash -> action.trashEntry
            is SyncAction.MoveLocalToTrash -> action.relPath
            is SyncAction.DeleteLocalFile -> action.relPath
            is SyncAction.DownloadTrash -> action.entry.path
        }

    private suspend fun applyOne(
        action: SyncAction.Local,
        report: SyncReportBuilder,
    ): Boolean =
        when (action) {
            is SyncAction.PublishDocument -> publish(action, report)
            is SyncAction.RestoreLocalTrash -> restore(action, report)
            is SyncAction.MoveLocalToTrash -> moveToTrash(action, report)
            is SyncAction.DeleteLocalFile -> deleteFile(action, report)
            is SyncAction.DownloadTrash -> downloadTrash(action)
        }

    private suspend fun publish(
        action: SyncAction.PublishDocument,
        report: SyncReportBuilder,
    ): Boolean {
        if (fs.exists(action.targetRelPath) && fs.sha256(action.targetRelPath) == action.docId) return false
        val scratch = downloads.remove(action.remotePath) ?: staging.newPath(".pdf")
        try {
            if (!staging.exists(scratch)) store.download(action.remotePath, scratch)
            check(
                staging.sha256(scratch) == action.docId,
            ) { "Remote document changed while syncing: ${action.remotePath}" }
            val target = uniquifyPath(action.targetRelPath, startIndex = 2, suffix = { " ($it)" }, exists = fs::exists)
            if (target != action.targetRelPath) {
                report.conflicts +=
                    SyncConflict(action.docId, action.remotePath, "Local path was taken; installed as $target")
            }
            fs.importExternal(staging.externalSource(scratch), target)
            check(fs.sha256(target) == action.docId) { "Installed document hash mismatch: $target" }
            report.downloadedDocuments++
            return true
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    private suspend fun restore(
        action: SyncAction.RestoreLocalTrash,
        report: SyncReportBuilder,
    ): Boolean {
        if (!verified(action.trashEntry, action.docId, report)) return false
        fs.move(action.trashEntry, trashOps.restoreTarget(action.targetRelPath, fs::exists))
        return true
    }

    private suspend fun moveToTrash(
        action: SyncAction.MoveLocalToTrash,
        report: SyncReportBuilder,
    ): Boolean {
        if (!verified(action.relPath, action.docId, report)) return false
        fs.move(action.relPath, trashOps.trashTarget(action.relPath, action.docId, action.episodeMillis))
        return true
    }

    private suspend fun deleteFile(
        action: SyncAction.DeleteLocalFile,
        report: SyncReportBuilder,
    ): Boolean {
        if (!verified(action.relPath, action.docId, report)) return false
        return hooks.deleteDeletedCopy(action.docId, action.relPath)
    }

    private suspend fun downloadTrash(action: SyncAction.DownloadTrash): Boolean {
        val path = action.entry.path
        if (fs.exists(path)) return false
        val scratch = staging.newPath(".pdf")
        try {
            store.download(path, scratch)
            val size = staging.size(scratch)
            check(
                size == action.entry.size,
            ) { "Downloaded ${size ?: "unknown"} bytes, listing said ${action.entry.size}" }
            if (fs.exists(path)) return false
            fs.importExternal(staging.externalSource(scratch), path)
            return true
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    /** The file at [relPath] still is [docId]; anything else is reported and left untouched. */
    private suspend fun verified(
        relPath: String,
        docId: String,
        report: SyncReportBuilder,
    ): Boolean {
        if (!fs.exists(relPath)) return false
        if (fs.sha256(relPath) == docId) return true
        report.conflicts += SyncConflict(docId, relPath, "Local file no longer holds this document; left in place")
        return false
    }
}
