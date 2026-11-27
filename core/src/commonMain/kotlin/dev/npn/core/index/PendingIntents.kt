package dev.npn.core.index

import dev.npn.core.db.PendingOpDao
import dev.npn.core.db.PendingOpEntity
import dev.npn.core.fs.DeleteAuthority
import dev.npn.core.fs.DeleteGate
import dev.npn.core.fs.DocumentFacts
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.KeepStore
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.OperationLog
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.DOCUMENT
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.sensitive
import dev.npn.core.util.warn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * This device's durable intents (`device.db`'s `pending_ops`): each multi-step operation on the
 * library folder is noted before its first effect and closed after its last, so one a crash cuts
 * short is resolved at the next open or rescan ([recover]) by what the journal says, not guessed
 * from file names:
 * - a trash into the keep store ([OperationLog.trashKept]) whose event stands, its file still at its
 *   path with the stat it proved, is finished: the file goes into the store and leaves its path. An
 *   event that never stood, or a restore since, leaves the file where it is;
 * - a bake ([OperationLog.bakeAside]) whose relink stands has its original, still aside, kept and
 *   deleted; one whose relink never stood gets its original back at its path when that is free, or
 *   deletes the aside copy when the path holds the original again. Otherwise the original stays
 *   under its recovery name, an ordinary PDF, for the rescan.
 *
 * An operation still under way is left alone ([OperationLog.release] ends that); one that failed
 * part way is settled as a crash's would be. An intent whose resolution fails stays, and the next
 * rescan tries again.
 */
internal class PendingIntents(
    private val dao: PendingOpDao,
    private val fs: LibraryFileSystem,
    private val keep: KeepStore,
    private val keepOn: () -> Boolean,
    private val clock: EpochClock,
    private val log: NpnLog,
    private val ids: IdSource = IdSource.Random,
) : OperationLog {
    private val gate = DeleteGate(fs)

    /** The intents of this session's operations still under way, which no recovery may touch. */
    private val mine = MutableStateFlow(emptySet<String>())

    override suspend fun trashKept(
        docId: String,
        path: String,
        stat: FileMeta,
        episodeMillis: Long,
        aside: String,
    ): String =
        open(
            PendingOpEntity(
                ids.next(),
                TRASH_KEPT,
                docId,
                path,
                other = aside,
                target = episodeMillis.toString(),
                size = stat.size,
                mtime = stat.mtimeMillis,
                createdAt = 0,
            ),
        )

    override suspend fun bakeAside(
        docId: String,
        norm: String,
        aside: String,
        baked: String,
    ): String = open(PendingOpEntity(ids.next(), BAKE, docId, norm, other = aside, target = baked, createdAt = 0))

    override suspend fun done(id: String) {
        dao.close(id)
        release(id)
    }

    override fun release(id: String) = mine.update { it - id }

    private suspend fun open(op: PendingOpEntity): String {
        mine.update { it + op.id }
        dao.open(op.copy(createdAt = clock.nowMillis()))
        return op.id
    }

    /** Every intent noted and not closed, those of operations under way included. */
    suspend fun all(): List<PendingOpEntity> = dao.all()

    /** The intents no operation under way holds: what a crash or a failure left open. */
    suspend fun leftOpen(): List<PendingOpEntity> = dao.all().filter { op -> op.id !in mine.value }

    /**
     * Resolve every intent a crash or a failure left open ([leftOpen]) by what [journal] says now;
     * run under the writer lock, before a rescan reads the folder.
     */
    suspend fun recover(journal: DocumentFacts) {
        for (op in leftOpen()) {
            runCatchingCancellable {
                when (op.kind) {
                    TRASH_KEPT -> finishTrash(op, journal)
                    BAKE -> settleBake(op, journal)
                    else -> log.warn("Dropping an operation of a kind this build does not know: ${op.kind}")
                }
                dao.close(op.id)
            }.onFailure { log.warn("Finishing the ${op.kind} of ${op.docId} a crash cut short failed", it) }
        }
    }

    private suspend fun finishTrash(
        op: PendingOpEntity,
        journal: DocumentFacts,
    ) {
        if (!journal.isTrashed(op.docId) && !journal.isDeleted(op.docId)) return
        val aside = op.other ?: return
        val episode = op.target?.toLongOrNull() ?: op.createdAt
        // Claimed already, the crash came before its delete: what it claimed is checked by its hash.
        if (fs.holds(aside, op.docId)) {
            keep.put(aside, op.docId, episode)?.let { gate.reap(aside, op.docId, DeleteAuthority.ExtraCopy(it)) }
            return
        }
        val meta = fs.stat(op.path)?.takeIf { !it.isDirectory } ?: return
        if (!meta.hasStoredStat(op.size ?: return, op.mtime ?: return) || !fs.holds(op.path, op.docId)) return
        val kept = keep.put(op.path, op.docId, episode) ?: return
        gate.reapClaimed(op.path, aside, op.docId, DeleteAuthority.ExtraCopy(kept))
        log.info("Finished the trash of ${op.docId} a crash cut short: its file went into the keep store")
    }

    private suspend fun settleBake(
        op: PendingOpEntity,
        journal: DocumentFacts,
    ) {
        val aside = op.other ?: return
        if (!fs.holds(aside, op.docId)) return
        val where = sensitive(aside, DOCUMENT)
        when {
            journal.successorOf(op.docId) != op.docId -> {
                // Noted before the relink: an episode no later than it.
                if (keepOn()) keep.put(aside, op.docId, op.createdAt)
                gate.reap(aside, op.docId, DeleteAuthority.Superseded(journal))
                log.info("Removed the original of a bake a crash cut short, its relink standing, from $where")
            }

            !fs.exists(op.path) -> {
                fs.move(aside, op.path)
                log.info("Put back the original of a bake a crash cut short, its relink never published, from $where")
            }

            fs.holds(op.path, op.docId) -> {
                gate.reap(aside, op.docId, DeleteAuthority.ExtraCopy(op.path))
            }

            else -> {
                log.warn("The original of a bake a crash cut short stays at $where: its path holds another file")
            }
        }
    }

    private companion object {
        const val TRASH_KEPT = "trash-kept"
        const val BAKE = "bake"
    }
}
