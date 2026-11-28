package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FileStat
import kotlin.math.abs

/** What the journal says of one document's lifecycle, as a fold reads it ([FoldLifecycle.resolve]). */
internal class JournalLifecycle(
    /** Relinked onto another id: deleted for good, on every fold, whatever its own events say. */
    val superseded: Boolean,
    val trashed: Boolean,
    val deleted: Boolean,
    /** The path its trash event names, if any. */
    val origPath: String?,
    /** When the event that hides it happened, 0 when none does. */
    val hiddenAtMillis: Long,
    /** The stat of the file its trash moved, while it is trashed and the event records one. */
    val trashStat: FileStat? = null,
)

/**
 * A document's trash/delete lifecycle as a fold projects it onto its row: the flags, where its row
 * points, and [resurrected] when a rescan found its own bytes back while the journal hides it.
 * [fileBack] is where a rescan found them back with the very stat its trash took them with, which
 * leaves it in the trash ([FoldLifecycle.resolve]).
 */
internal class ProjectedLifecycle(
    val trashed: Boolean,
    val deleted: Boolean,
    val missing: Boolean,
    val relPath: String,
    val fileName: String,
    val origPath: String?,
    val resurrected: Resurrected?,
    val fileBack: String? = null,
)

/** How a fold projects a document's lifecycle, the file-over-journal preference of a rescan included. */
internal object FoldLifecycle {
    /**
     * Resolve [doc]'s lifecycle from what the [journal] says and its [row]. With [preserved], a
     * commit wrote the row's lifecycle optimistically and the fold keeps it (a superseded document
     * is deleted still). On a rescan, which knows the content of the files it listed ([contentAt],
     * null for any other fold), a document the journal hides whose own bytes are back ([returnedPath])
     * is shown, the file preferred over the journal, and reported as [ProjectedLifecycle.resurrected].
     * [held] says whether a row holds a path; [statAt] gives a listed file's stat.
     *
     * A trash that recorded the stat of the file it moved ([JournalLifecycle.trashStat]) says what
     * its bytes back are, by the stats of every file holding them ([statsOfCopies], [FileStat.sameAs]):
     * - all the same stat: the copy a sync tool has not taken away yet, as it carries the mtime with the
     *   bytes, or a put-back that kept it (a backup restored, a file manager's copy). Either way the
     *   document stays in the trash, with no window after which it comes back by itself: its file is
     *   reported as [ProjectedLifecycle.fileBack], for the user to restore in place;
     * - any other stat: no stale copy, a deliberate return, recorded at once
     *   ([Resurrected.immediate]).
     *
     * Without one (a trash that moved nothing, one an older build wrote, a delete for good) the
     * return waits out the coordinator's window, as before.
     */
    fun resolve(
        doc: String,
        row: DocumentEntity,
        journal: JournalLifecycle,
        preserved: Boolean,
        contentAt: Map<String, String>?,
        statAt: (String) -> FileStat? = { null },
        /** The stats of every listed file at a document path that holds [doc]'s bytes. */
        statsOfCopies: List<FileStat> = emptyList(),
        held: (String) -> Boolean,
    ): ProjectedLifecycle {
        val journalDeleted = journal.deleted || journal.superseded
        val hidden = journal.trashed || journalDeleted
        val backAt =
            contentAt
                ?.takeIf { hidden && !journal.superseded }
                ?.let { returnedPath(doc, row, journal.origPath, it, held) }
        val recorded = journal.trashStat?.takeIf { journal.trashed && !journalDeleted }
        // Found only where the bytes are back: a stat says nothing of a path they are not at.
        val found = backAt?.let(statAt)
        // Every copy decides, not only the one the row points at: a rebuilt index may point at another.
        if (recorded != null && found != null && (statsOfCopies + found).all { it.sameAs(recorded) }) {
            return hiddenAs(doc, row, journal, preserved, fileBack = backAt)
        }
        if (backAt != null) {
            // Resurrection: prefer the file. Clear trashed/deleted for the working view.
            val fileName = if (backAt != row.relPath) NpnPaths.fileName(backAt) else row.fileName
            return ProjectedLifecycle(
                trashed = false,
                deleted = false,
                missing = false,
                relPath = backAt,
                fileName = fileName,
                origPath = journal.origPath,
                resurrected =
                    Resurrected(
                        doc,
                        backAt,
                        hiddenAtMillis = journal.hiddenAtMillis,
                        immediate = recorded != null && found != null,
                    ),
            )
        }
        return hiddenAs(doc, row, journal, preserved)
    }

    /**
     * [doc]'s lifecycle as the [journal], or with [preserved] its [row], has it, its row where it is,
     * reporting its file back at [fileBack] with its trash's stat.
     */
    private fun hiddenAs(
        doc: String,
        row: DocumentEntity,
        journal: JournalLifecycle,
        preserved: Boolean,
        fileBack: String? = null,
    ): ProjectedLifecycle {
        val journalDeleted = journal.deleted || journal.superseded
        val trashed = if (preserved) row.trashed else journal.trashed
        val deleted = if (preserved) row.deleted || journal.superseded else journalDeleted
        return ProjectedLifecycle(
            trashed = trashed,
            deleted = deleted,
            // A row waiting at its sentinel has no file. Shown again (another device restored the
            // document and its file has not arrived), it is missing until a rescan finds the file.
            missing = row.missing || (!trashed && !deleted && row.relPath == missingSentinel(doc)),
            relPath = row.relPath,
            fileName = row.fileName,
            origPath = journal.origPath,
            resurrected = null,
            fileBack = fileBack,
        )
    }

    /**
     * Where a hidden [doc]'s own bytes are back in the library: at its row's path, else at
     * [origPath], the path it was trashed from, when no row holds that ([held]). Only a file whose
     * hash the rescan knows ([contentAt]) is evidence: another document's file at the path, or one
     * still being written, says nothing about this one.
     */
    private fun returnedPath(
        doc: String,
        row: DocumentEntity,
        origPath: String?,
        contentAt: Map<String, String>,
        held: (String) -> Boolean,
    ): String? =
        row.relPath.takeIf { contentAt[it] == doc }
            ?: origPath?.takeIf { contentAt[it] == doc && !held(it) }
}

/** The slack between two mtimes that still count as one: FAT and some cloud remotes keep whole seconds. */
internal const val MTIME_TOLERANCE_MILLIS = 2_000L

/**
 * Whether this stat is [other]'s: the same size, and mtimes within [MTIME_TOLERANCE_MILLIS] of each
 * other.
 */
internal fun FileStat.sameAs(other: FileStat): Boolean =
    size == other.size && abs(mtimeMillis - other.mtimeMillis) <= MTIME_TOLERANCE_MILLIS
