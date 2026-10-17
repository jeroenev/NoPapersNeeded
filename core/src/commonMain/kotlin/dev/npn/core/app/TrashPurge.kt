package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.TrashOps
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn

/**
 * The trash auto-purge, run after watch-triggered rescans; not part of any public facet.
 *
 * An entry goes once every document it may hold was trashed, or deleted for good, longer ago than
 * the retention ([TrashOps.purgeOlderThan]); an entry no such document owns stays. The journal and
 * the rows decide together ([keptLonger]): the journal covers documents with no row, which is every
 * trashed or deleted one on a device whose index was just built (their PDFs keep an old mtime too),
 * and a row can show a document the journal hides, its file found back in place by a rescan.
 *
 * The trash is listed before the writer lock; its entries are checked against the rows and the
 * journal and deleted under it. An entry's name carries the trash episode it belongs to
 * ([dev.npn.core.fs.NpnPaths.trashPath]), so even a restore and a trash again whose events arrive
 * after its entry cannot pass for the old episode's. A batch of [PURGE_BATCH] entries per hold: the
 * check reads every document's trash time, so once per batch rather than per entry, and no hold
 * lasts through a long run of deletes (binder calls under SAF) that every write waits on.
 */
internal class TrashPurge(
    private val coordinator: JournalCoordinator,
    private val db: NpnDatabase,
    private val trash: TrashOps,
    private val retention: JournalTrashRetention,
    private val clock: EpochClock,
    private val log: NpnLog,
) {
    /** [purge] by the library's retention as it stands now; one this build cannot read purges nothing. */
    suspend fun purgeExpired() {
        val days = retention.current()
        if (days == null) {
            log.warn("The library's trash retention is not one this build reads; the trash is kept")
            return
        }
        log.runLogged("Trash auto-purge") { purge(days) }
    }

    /** Delete the entries whose documents all left the library more than [olderThanDays] ago. */
    suspend fun purge(olderThanDays: Int) {
        val cutoff = clock.nowMillis() - olderThanDays.toLong() * MILLIS_PER_DAY
        for (batch in trash.listTrash().chunked(PURGE_BATCH)) {
            coordinator.serialized { journal ->
                val rows = db.documentDao().trashTimes().associate { it.docId to it.trashedAt }
                trash.purgeOlderThan(cutoff, keptLonger(journal.trashTimes(), rows), batch)
            }
        }
    }

    /**
     * The [journal]'s trash times and the [rows]' as one, for a document both know the one that keeps
     * its entries longer: null when either has it out of the trash, else the later time. A row that
     * lags the journal (a projection that failed) then keeps an entry rather than delete it.
     */
    private fun keptLonger(
        journal: Map<String, Long?>,
        rows: Map<String, Long?>,
    ): Map<String, Long?> =
        journal +
            rows.mapValues { (doc, row) ->
                if (doc !in journal) row else journal[doc]?.let { trashed -> row?.let { maxOf(trashed, it) } }
            }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /** How many trash entries the purge checks and deletes per hold of the writer lock. */
        const val PURGE_BATCH = 32
    }
}
