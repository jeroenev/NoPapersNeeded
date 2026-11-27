package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.FoldedState
import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

/**
 * Brings back to the library the trash copy of a document the library shows, whose file is gone,
 * when that copy belongs to a trash the journal has restored since: the copy is the document's, left
 * in the trash by a restore that never moved it.
 *
 * A restore moves the trash's copy back only when that copy is in this device's trash. On a device
 * a sync tool has brought another device's trash event but not yet its file move, the document's
 * file is still at its path and no copy is in the trash: a restore there (the user's, an Undo, or an
 * import of the same bytes) takes the file in place. The move then lands, taking that file away and
 * putting the copy in the trash, and nothing moves it out: the document shows as missing, its bytes
 * only in the trash, which a device that has not seen the restore yet purges by the retention,
 * taking the library's last copy.
 *
 * A trash entry's name carries its trash episode ([NpnPaths.trashEpisodeMillis]). An entry whose
 * episode is no later than the restore that took the document out of the trash last
 * ([FoldedState.restoredAtMillis]) belongs to a trash that restore undid, and comes back. One of a
 * later episode belongs to a trash whose event has not reached this device yet, and stays: moving it
 * back would undo that trash. So does an entry without an episode (an older build's name), which
 * says nothing of when it was made. Each copy is proved by its hash before it moves.
 *
 * It goes to the document's intended path, or beside it when that is taken, as a restore puts it; a
 * rescan then finds the document there, at a new path through a move it records.
 *
 * Not safe for concurrent calls: the rescan runs it under [JournalCoordinator]'s writer lock.
 */
internal class StrandedTrashCopies(
    private val fs: LibraryFileSystem,
    db: NpnDatabase,
    private val log: NpnLog,
) {
    private val documentDao = db.documentDao()
    private val trash = TrashOps(fs)

    /**
     * Move back the stranded trash copy of every document [state] shows that the index has as
     * missing. Returns whether any came back, for the rescan to find. A failure is logged and leaves
     * the copy where it was for the next rescan: the scan has projected what it found.
     */
    suspend fun reclaim(state: FoldedState): Boolean {
        val stranded =
            documentDao.liveMissing().mapNotNull { row ->
                state.restoredAtMillis(row.docId)?.let { restoredAt -> row to restoredAt }
            }
        if (stranded.isEmpty()) return false
        val listing = trash.listTrash()
        var reclaimed = false
        for ((row, restoredAt) in stranded) {
            val undone =
                listing.filter { entry ->
                    NpnPaths.trashEpisodeMillis(entry.name)?.let { episode -> episode <= restoredAt } == true
                }
            runCatchingCancellable { reclaimed = reclaim(row, state, undone) || reclaimed }
                .onFailure { log.warn("Bringing ${row.docId} back from the trash failed", it) }
        }
        return reclaimed
    }

    /** Move the first of [undone] that holds [row]'s bytes to where [state] wants its document. */
    private suspend fun reclaim(
        row: DocumentEntity,
        state: FoldedState,
        undone: List<FileMeta>,
    ): Boolean {
        if (undone.isEmpty()) return false
        val copy =
            trash
                .hashCandidates(row.docId, undone)
                .entries
                .firstOrNull { it.value }
                ?.key
                ?.relPath ?: return false
        val wanted =
            listOfNotNull(state.intendedPath(row.docId), row.relPath).firstOrNull(NpnPaths::isDocumentPath)
                ?: "${NpnPaths.DEFAULT_INBOX}/${row.fileName}"
        val target =
            trash.restoreTarget(wanted) { path ->
                fs.exists(path) ||
                    documentDao.getByRelPath(path)?.isLive == true
            }
        fs.move(copy, target)
        log.info("Brought ${row.docId} back from $copy to $target: a restore had left its bytes in the trash")
        return true
    }
}
