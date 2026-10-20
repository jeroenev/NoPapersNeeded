package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.NpnPaths

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
)

/**
 * A document's trash/delete lifecycle as a fold projects it onto its row: the flags, where its row
 * points, and [resurrected] when a rescan found its own bytes back while the journal hides it.
 */
internal class ProjectedLifecycle(
    val trashed: Boolean,
    val deleted: Boolean,
    val missing: Boolean,
    val relPath: String,
    val fileName: String,
    val origPath: String?,
    val resurrected: Resurrected?,
)

/** How a fold projects a document's lifecycle, the file-over-journal preference of a rescan included. */
internal object FoldLifecycle {
    /**
     * Resolve [doc]'s lifecycle from what the [journal] says and its [row]. With [preserved], a
     * commit wrote the row's lifecycle optimistically and the fold keeps it (a superseded document
     * is deleted still). On a rescan, which knows the content of the files it listed ([contentAt],
     * null for any other fold), a document the journal hides whose own bytes are back ([returnedPath])
     * is shown, the file preferred over the journal, and reported as [ProjectedLifecycle.resurrected].
     * [held] says whether a row holds a path.
     */
    fun resolve(
        doc: String,
        row: DocumentEntity,
        journal: JournalLifecycle,
        preserved: Boolean,
        contentAt: Map<String, String>?,
        held: (String) -> Boolean,
    ): ProjectedLifecycle {
        val journalDeleted = journal.deleted || journal.superseded
        val hidden = journal.trashed || journalDeleted
        val backAt =
            contentAt
                ?.takeIf { hidden && !journal.superseded }
                ?.let { returnedPath(doc, row, journal.origPath, it, held) }
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
                resurrected = Resurrected(doc, backAt, hiddenAtMillis = journal.hiddenAtMillis),
            )
        }
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

    /**
     * The returns a rescan found ([resurrected]) that it records in the journal [nowMillis]: those
     * of documents hidden at least [settleMillis] ago. Sooner, a file still in place may be one a
     * sync tool has not taken away yet, as it delivers another device's trash or delete event before
     * the file move that went with it.
     */
    fun settledReturns(
        resurrected: List<Resurrected>,
        nowMillis: Long,
        settleMillis: Long,
    ): List<Resurrected> {
        val settled = nowMillis - settleMillis
        return resurrected.filter { it.hiddenAtMillis <= settled }
    }
}
