package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.FoldedState
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

/**
 * Brings back to the library the trash copy of a document the library shows, whose file is gone,
 * when no trash the journal stands by holds it there: a copy left in the trash by a restore that
 * never moved it, or by a trash whose event was never published.
 *
 * A restore moves the trash's copy back only when that copy is in this device's trash. On a device
 * a sync tool has brought another device's trash event but not yet its file move, the document's
 * file is still at its path and no copy is in the trash: a restore there (the user's, an Undo, or an
 * import of the same bytes) takes the file in place. The move then lands, taking that file away and
 * putting the copy in the trash, and nothing moves it out: the document shows as missing, its bytes
 * only in the trash, which a device that has not seen the restore yet purges by the retention,
 * taking the library's last copy. A trash moves its file before it publishes its event, so a trash
 * whose commit failed and whose file could not be put back, or that a crash cut short, leaves the
 * same: a copy in the trash and no event for it.
 *
 * A trash entry's name carries its trash episode ([NpnPaths.trashEpisodeMillis]). Each copy is proved
 * by its hash before it moves; an entry without an episode (an older build's name) says nothing of
 * when it was made, and stays.
 * - An entry whose episode is no later than the restore that took the document out of the trash
 *   last ([FoldedState.restoredAtMillis]) belongs to a trash that restore undid, and comes back.
 * - One of a later episode (or of a document never restored) belongs to a trash the journal does not
 *   have: one whose event has not reached this device yet, or one that never published it. Moving
 *   it back at once would undo the first kind, so it comes back only once the episode is
 *   [JournalCoordinator.RESTORE_SETTLE_MS] old and this session's rescans have kept finding it so,
 *   with no trash in the journal, for as long: a trash's event travels with its file move, as
 *   [JournalCoordinator.recordRestores] counts a return.
 *
 * It goes to the document's intended path, or beside it when that is taken, as a restore puts it; a
 * rescan then finds the document there, at a new path through a move it records.
 *
 * Not safe for concurrent calls: the rescan runs it under [JournalCoordinator]'s writer lock.
 */
internal class StrandedTrashCopies(
    private val fs: LibraryFileSystem,
    db: NpnDatabase,
    private val clock: EpochClock,
    private val log: NpnLog,
) {
    private val documentDao = db.documentDao()
    private val trash = TrashOps(fs)

    /**
     * The trash entries of a later episode than the journal's last restore of a document shown as
     * missing, each with when this session's rescans first found it so. Only entries the last pass
     * found are kept: one that left the trash, or whose document came back, starts again.
     */
    private val unexplainedSince = HashMap<String, Long>()

    /**
     * Move back the stranded trash copy of every document [state] shows that the index has as
     * missing. Returns whether any came back, for the rescan to find. A failure is logged and leaves
     * the copy where it was for the next rescan: the scan has projected what it found.
     */
    suspend fun reclaim(state: FoldedState): Boolean {
        val stranded = documentDao.liveMissing().filter { state.isCurrent(it.docId) }
        if (stranded.isEmpty()) {
            unexplainedSince.clear()
            return false
        }
        val now = clock.nowMillis()
        val listing = trash.listTrash()
        val seen = HashMap<String, Long>()
        var reclaimed = false
        for (row in stranded) {
            val restoredAt = state.restoredAtMillis(row.docId) ?: Long.MIN_VALUE
            val prefix = row.docId.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
            val strays =
                listing.filter { entry ->
                    val episode = NpnPaths.trashEpisodeMillis(entry.name)
                    if (episode == null || NpnPaths.trashHashPrefix(entry.name) != prefix) return@filter false
                    if (episode <= restoredAt) return@filter true
                    val since = unexplainedSince[entry.relPath] ?: now
                    seen[entry.relPath] = since
                    episode <= now - SETTLE_MS && since <= now - SETTLE_MS
                }
            runCatchingCancellable { reclaimed = reclaim(row, state, strays) || reclaimed }
                .onFailure { log.warn("Bringing ${row.docId} back from the trash failed", it) }
        }
        unexplainedSince.clear()
        unexplainedSince.putAll(seen)
        return reclaimed
    }

    /** Move the first of [strays] that holds [row]'s bytes to where [state] wants its document. */
    private suspend fun reclaim(
        row: DocumentEntity,
        state: FoldedState,
        strays: List<FileMeta>,
    ): Boolean {
        if (strays.isEmpty()) return false
        val copy =
            trash
                .hashCandidates(row.docId, strays)
                .entries
                .firstOrNull { it.value }
                ?.key
                ?.relPath ?: return false
        val wanted =
            listOfNotNull(state.intendedPath(row.docId), row.relPath).firstOrNull(NpnPaths::isDocumentPath)
                ?: "${NpnPaths.DEFAULT_INBOX}/${row.fileName}"
        val target =
            trash.restoreTarget(wanted, row.docId) { path ->
                fs.exists(path) ||
                    documentDao.getByRelPath(path)?.isLive == true
            }
        fs.move(copy, target)
        unexplainedSince.remove(copy)
        log.info("Brought ${row.docId} back from $copy to $target: no trash in the journal holds it there")
        return true
    }

    private companion object {
        const val SETTLE_MS = JournalCoordinator.RESTORE_SETTLE_MS
    }
}
