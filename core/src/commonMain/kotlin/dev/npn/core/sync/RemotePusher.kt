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
 * and the next pass will plan against what is there now. Uploads run several at once
 * ([transferEach]); the manifest and the report take each one's outcome in turn.
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
        var done = 0
        onProgress(0, actions.size)
        // Uploads take no name another action frees or takes, so a run of them goes
        // [TransferLimits.documents] at a time; moves and deletes keep the plan's order around them.
        for (run in runs(actions)) {
            if (run.first().isUpload) {
                transferEach(run, store.limits.documents, ::upload) { action, outcome ->
                    report.attempt(pathOf(action)) { settle(action, outcome.getOrThrow(), report) }
                    onProgress(++done, actions.size)
                }
            } else {
                val action = run.single()
                report.attempt(pathOf(action)) { pushOne(action, report) }
                onProgress(++done, actions.size)
            }
        }
    }

    private val SyncAction.Remote.isUpload: Boolean
        get() = this is SyncAction.UploadDocument || this is SyncAction.UploadTrash

    /** [actions] in order, each run of consecutive uploads together and every other action alone. */
    private fun runs(actions: List<SyncAction.Remote>): List<List<SyncAction.Remote>> {
        val runs = ArrayList<MutableList<SyncAction.Remote>>()
        for (action in actions) {
            val last = runs.lastOrNull()
            if (action.isUpload && last != null &&
                last.first().isUpload
            ) {
                last += action
            } else {
                runs += mutableListOf(action)
            }
        }
        return runs
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
            is SyncAction.MoveRemote -> move(action, report)
            is SyncAction.DeleteRemote -> delete(action, report)
            is SyncAction.UploadDocument, is SyncAction.UploadTrash -> error("uploads go through transferEach")
        }
    }

    /** What an upload came to: the transfer touches nothing shared, its [settle] records it. */
    private sealed interface Uploaded {
        /** Something is at the remote name already: the remote changed since the listing. */
        data object Occupied : Uploaded

        /** The library file no longer holds the document. */
        data object LocalChanged : Uploaded

        /** Up, read from [source]; [entry] is the remote's own view of it, to guard by. */
        data class Done(
            val entry: RemoteEntry,
            val source: String,
        ) : Uploaded
    }

    private suspend fun upload(action: SyncAction.Remote): Uploaded =
        when (action) {
            is SyncAction.UploadDocument -> uploadDocument(action)
            is SyncAction.UploadTrash -> uploadTrash(action)
            is SyncAction.MoveRemote, is SyncAction.DeleteRemote -> error("only uploads are transferred")
        }

    private suspend fun uploadDocument(action: SyncAction.UploadDocument): Uploaded {
        if (store.stat(action.remotePath) != null) return Uploaded.Occupied
        // Planned under a new name: the move may have found the name taken this pass, by a file that
        // is not this document. Read whichever name holds it (rare, so the hash is affordable).
        val source =
            action.formerRelPath
                ?.takeIf { !fs.holds(action.relPath, action.docId) && fs.holds(it, action.docId) }
                ?: action.relPath
        val entry = uploadVerified(source, action.remotePath, action.docId) ?: return Uploaded.LocalChanged
        return Uploaded.Done(entry, source)
    }

    private suspend fun uploadTrash(action: SyncAction.UploadTrash): Uploaded {
        if (store.stat(action.relPath) != null) return Uploaded.Occupied
        val entry = uploadVerified(action.relPath, action.relPath, action.docId) ?: return Uploaded.LocalChanged
        return Uploaded.Done(entry, action.relPath)
    }

    private suspend fun settle(
        action: SyncAction.Remote,
        uploaded: Uploaded,
        report: SyncReportBuilder,
    ) {
        when (action) {
            is SyncAction.UploadDocument -> {
                when (uploaded) {
                    Uploaded.Occupied -> {
                        report.conflicts += SyncConflict(action.docId, action.remotePath, ConflictKind.REMOTE_CHANGED)
                    }

                    Uploaded.LocalChanged -> {
                        report.conflicts += SyncConflict(action.docId, action.remotePath, ConflictKind.LOCAL_CHANGED)
                    }

                    is Uploaded.Done -> {
                        manifest.record(
                            ManifestEntry(
                                action.remotePath,
                                ManifestKind.DOCUMENT,
                                uploaded.entry.size,
                                uploaded.entry.mtimeMillis,
                                action.docId,
                                localPath = uploaded.source,
                            ),
                            nowMillis,
                        )
                        report.uploadedDocuments++
                        report.settledUploads++
                    }
                }
            }

            is SyncAction.UploadTrash -> {
                when (uploaded) {
                    // Another device put the entry up first: it is there, which is all the trash needs.
                    Uploaded.Occupied -> {
                        report.settledUploads++
                    }

                    Uploaded.LocalChanged -> {
                        action.docId?.let {
                            report.conflicts += SyncConflict(it, action.relPath, ConflictKind.LOCAL_CHANGED)
                        }
                    }

                    is Uploaded.Done -> {
                        manifest.record(
                            ManifestEntry(
                                action.relPath,
                                ManifestKind.TRASH,
                                uploaded.entry.size,
                                uploaded.entry.mtimeMillis,
                                action.docId,
                            ),
                            nowMillis,
                        )
                        report.uploadedObjects++
                        report.settledUploads++
                    }
                }
            }

            is SyncAction.MoveRemote, is SyncAction.DeleteRemote -> {
                error("only uploads are settled")
            }
        }
    }

    /**
     * Copy the library file out to staging chunk by chunk (a large scan never sits in memory whole),
     * confirm the copy still is [docId] when one is expected, and upload it. Returns the remote's own
     * view of the uploaded file (the size/mtime to guard by), or null when the local file no longer
     * holds the document. A remote file of another size than the copy is an upload cut short on a
     * backend that writes in place: it is removed, never left for another replica to take for a new
     * document.
     */
    private suspend fun uploadVerified(
        relPath: String,
        remotePath: String,
        docId: String?,
    ): RemoteEntry? {
        val scratch = staging.newPath(".pdf")
        try {
            staging.writeStreaming(scratch) { sink -> fs.readChunked(relPath, sink) }
            if (docId != null && staging.sha256(scratch) != docId) return null
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
