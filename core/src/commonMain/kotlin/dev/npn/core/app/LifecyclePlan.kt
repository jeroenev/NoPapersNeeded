package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.FileMove
import dev.npn.core.index.LifecycleChange
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty

/**
 * What a trash, restore or delete for good of one document does, as pure functions of its row and
 * of what [DocLifecycleImpl] found on disk under the writer lock: the events to publish, the row as
 * the change leaves it, and the file to move. [dev.npn.core.index.JournalCoordinator.changeLifecycle]
 * carries the [LifecycleChange] out.
 */
internal object LifecyclePlan {
    /** Whether [doc] can go into the trash: it is neither there already nor deleted for good. */
    fun trashes(doc: DocumentEntity): Boolean = !doc.trashed && !doc.deleted

    /**
     * Trash [doc], moving its file to [trashPath] when that is where its own bytes go (null: the file
     * at its path is not the document's, or is gone, and stays where it is for the next rescan).
     */
    fun trash(
        doc: DocumentEntity,
        trashPath: String?,
    ): LifecycleChange {
        val origPath = doc.relPath
        return LifecycleChange(
            // The event carries origPath for a restore on another device.
            ops = listOf(PendingOp.docTrash(doc.docId, origPath)),
            row = doc.copy(trashed = true, trashedOrigPath = origPath, missing = false),
            move = trashPath?.let { FileMove(origPath, it) },
            // The time the fold gives this trash, which a later one replaces: the Undo's check.
            readBack = true,
        )
    }

    /** Whether [doc] is in the trash, to be restored from it. */
    fun restores(doc: DocumentEntity): Boolean = doc.trashed

    /**
     * Where [doc] goes back to: the path it was trashed from, else its row's, while that is a
     * document path. One that is not (under `.npn/`, as a missing row's placeholder is, or one a
     * peer's trash event named) is no place for a document, and it comes back in the inbox instead.
     */
    fun restorePath(doc: DocumentEntity): String =
        (doc.trashedOrigPath ?: doc.relPath).takeIf { NpnPaths.isDocumentPath(it) }
            ?: "${NpnPaths.DEFAULT_INBOX}/${doc.fileName}"

    /**
     * Restore [doc] to [finalPath], moving there the trash [entry] that holds its bytes, of stat
     * [entryStat], which the row records as its proof from then on; with no entry, the row keeps the
     * stat it had (none, for a row the fold made for a trash whose file it never saw).
     */
    fun restore(
        doc: DocumentEntity,
        finalPath: String,
        entry: String?,
        entryStat: FileMeta?,
    ): LifecycleChange =
        LifecycleChange(
            ops =
                listOf(
                    PendingOp.docRestore(doc.docId),
                    PendingOp.propSet(doc.docId, SystemProperty.STORAGE_PATH, finalPath),
                ),
            row =
                doc.copy(
                    trashed = false,
                    trashedOrigPath = null,
                    trashedAt = null,
                    relPath = finalPath,
                    fileName = NpnPaths.fileName(finalPath),
                    size = entryStat?.size ?: doc.size,
                    mtime = entryStat?.mtimeMillis ?: doc.mtime,
                    missing = false,
                ),
            move = entry?.let { FileMove(it, finalPath) },
        )

    /** Whether [doc] can be deleted for good: only from the trash. */
    fun deletes(doc: DocumentEntity): Boolean = doc.trashed

    /**
     * Delete [doc] for good: its tombstone, and nothing moved. Its bytes go after the tombstone is
     * published ([DocLifecycleImpl]), so a refused commit leaves the file where it was; the row keeps
     * it hidden (deleted) after any rescan.
     */
    fun delete(doc: DocumentEntity): LifecycleChange =
        LifecycleChange(
            ops = listOf(PendingOp.docDelete(doc.docId)),
            row = doc.copy(deleted = true, trashed = false, trashedAt = null, missing = false),
        )
}
