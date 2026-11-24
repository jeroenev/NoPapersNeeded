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
 * same name meanwhile is left alone and reported rather than acted on. Each trash, restore and
 * delete is decided again under the journal's writer lock ([SyncHooks.serialized]), so a restore or
 * an import the user makes after the pass planned is not undone by it.
 */
class LocalApplier(
    private val fs: LibraryFileSystem,
    private val staging: LocalStaging,
    private val store: RemoteStore,
    private val downloads: MutableMap<String, String>,
    private val hooks: SyncHooks,
    private val manifest: SyncManifest,
    private val nowMillis: Long,
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
            is SyncAction.MoveLocal -> action.from
            is SyncAction.RecordLocalPath -> action.localPath
            is SyncAction.DeleteLocalFile -> action.relPath
            is SyncAction.DownloadTrash -> action.entry.path
        }

    private suspend fun applyOne(
        action: SyncAction.Local,
        report: SyncReportBuilder,
    ): Boolean =
        when (action) {
            is SyncAction.PublishDocument -> {
                publish(action, report)
            }

            is SyncAction.RestoreLocalTrash -> {
                restore(action, report)
            }

            is SyncAction.MoveLocalToTrash -> {
                moveToTrash(action, report)
            }

            is SyncAction.MoveLocal -> {
                moveLocal(action, report)
            }

            is SyncAction.RecordLocalPath -> {
                manifest.recordLocalPath(action.remotePath, action.localPath, nowMillis)
                false
            }

            is SyncAction.DeleteLocalFile -> {
                deleteFile(action, report)
            }

            is SyncAction.DownloadTrash -> {
                val downloaded = downloadTrash(action)
                if (downloaded) report.downloadedTrash++
                downloaded
            }
        }

    private suspend fun publish(
        action: SyncAction.PublishDocument,
        report: SyncReportBuilder,
    ): Boolean {
        if (fs.exists(action.targetRelPath) && fs.sha256(action.targetRelPath) == action.docId) return false
        val scratch = downloads.remove(action.remotePath) ?: staging.newPath(".pdf")
        try {
            if (!staging.exists(scratch)) store.download(action.remotePath, scratch)
            if (staging.sha256(scratch) != action.docId) {
                throw RemoteRaceException("Remote document changed while syncing: ${action.remotePath}")
            }
            if (fs.exists(action.targetRelPath)) removeSupersededAt(action.targetRelPath, action.docId)
            val target = uniquifyPath(action.targetRelPath, startIndex = 2, suffix = { " ($it)" }, exists = fs::exists)
            if (target != action.targetRelPath) {
                report.conflicts +=
                    SyncConflict(action.docId, action.remotePath, ConflictKind.INSTALLED_AS_COPY, otherPath = target)
            }
            fs.importExternal(staging.externalSource(scratch), target)
            if (fs.sha256(target) != action.docId) throw RemoteRaceException("Installed file changed at once: $target")
            manifest.recordLocalPath(action.remotePath, target, nowMillis)
            report.downloadedDocuments++
            return true
        } finally {
            withContext(NonCancellable) { staging.delete(scratch) }
        }
    }

    /**
     * The file at [relPath], where a document's successor is to be installed, may hold bytes a relink
     * superseded (the original of a bake another device made): those are the successor's old
     * version, which the bake removed there, so they make way here too rather than push the
     * successor to a uniquified name. Anything else at [relPath] stays.
     */
    private suspend fun removeSupersededAt(
        relPath: String,
        successor: String,
    ) {
        hooks.serialized { journal ->
            val held = fs.sha256(relPath)
            if (held != successor && journal.successorOf(held) == successor) fs.delete(relPath)
        }
    }

    private suspend fun restore(
        action: SyncAction.RestoreLocalTrash,
        report: SyncReportBuilder,
    ): Boolean =
        hooks.serialized { journal ->
            if (!journal.isCurrent(action.docId) || !verified(action.trashEntry, action.docId, report)) {
                return@serialized false
            }
            fs.move(action.trashEntry, trashOps.restoreTarget(action.targetRelPath, fs::exists))
            true
        }

    /**
     * Give the document's library file the remote's name: only while the library shows it, the file
     * still holds it, and the new name is free here; anything else waits for the next pass.
     */
    private suspend fun moveLocal(
        action: SyncAction.MoveLocal,
        report: SyncReportBuilder,
    ): Boolean {
        val moved =
            hooks.serialized { journal ->
                if (!journal.isCurrent(action.docId) || !verified(action.from, action.docId, report)) {
                    return@serialized false
                }
                if (fs.exists(action.to)) {
                    report.conflicts += SyncConflict(action.docId, action.to, ConflictKind.TARGET_OCCUPIED)
                    return@serialized false
                }
                fs.move(action.from, action.to)
                true
            }
        if (moved) manifest.recordLocalPath(action.remotePath, action.to, nowMillis)
        return moved
    }

    private suspend fun moveToTrash(
        action: SyncAction.MoveLocalToTrash,
        report: SyncReportBuilder,
    ): Boolean =
        hooks.serialized { journal ->
            if (!journal.isTrashed(action.docId) || !verified(action.relPath, action.docId, report)) {
                return@serialized false
            }
            fs.move(action.relPath, trashOps.trashTarget(action.relPath, action.docId, action.episodeMillis))
            true
        }

    /**
     * Delete a copy of a deleted document, only while the journal still has it deleted and the file
     * still holds its bytes: an import that brought the bytes back since the pass planned keeps them.
     */
    private suspend fun deleteFile(
        action: SyncAction.DeleteLocalFile,
        report: SyncReportBuilder,
    ): Boolean =
        hooks.serialized { journal ->
            if (!journal.isDeleted(action.docId) || !verified(action.relPath, action.docId, report)) {
                return@serialized false
            }
            fs.delete(action.relPath)
            true
        }

    private suspend fun downloadTrash(action: SyncAction.DownloadTrash): Boolean {
        val path = action.entry.path
        if (fs.exists(path)) return false
        val scratch = staging.newPath(".pdf")
        try {
            store.download(path, scratch)
            val size = staging.size(scratch)
            if (size != action.entry.size) {
                throw RemoteRaceException("Downloaded ${size ?: "unknown"} bytes, listing said ${action.entry.size}")
            }
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
        report.conflicts += SyncConflict(docId, relPath, ConflictKind.LOCAL_CHANGED)
        return false
    }
}
