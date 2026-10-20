package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.PathMeta
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.journal.FoldedState

// What a rescan decides about the files it listed and the rows the index holds, as pure functions
// of what the pass gathered. [Indexer.rescan] gathers the facts (the listing, the hashes, the rows,
// the fold) and writes what [RescanDecision.plan] returns; nothing here reads a file or the database.

/** What the journal says that a rescan's reconcile decides by: a view of the fold the pass projects. */
internal interface RescanJournal {
    /** The document [docId]'s bytes became, its relink chain followed; [docId] when none superseded it. */
    fun successorOf(docId: String): String

    /** Whether the journal has [docId] out of the library: trashed or deleted by its latest such event. */
    fun hides(docId: String): Boolean

    /** Whether any `storagePath` event, winning or not, named [path] for [docId]. */
    fun named(
        docId: String,
        path: String,
    ): Boolean
}

/** This fold as a rescan's reconcile reads it. */
internal fun FoldedState.rescanJournal(): RescanJournal =
    object : RescanJournal {
        override fun successorOf(docId: String): String = resolveDocId(docId)

        override fun hides(docId: String): Boolean = hiddenBy(docId) != null

        override fun named(
            docId: String,
            path: String,
        ): Boolean = namedStoragePath(docId, path)
    }

/**
 * One hashed file as the rows a rescan holds see it: [meta] listed with content [hash], [holder] the
 * row holding its path when that is another document's, [known] the row of [hash] itself.
 */
internal class FileFacts(
    val meta: FileMeta,
    val hash: String,
    val holder: DocumentEntity?,
    /** Whether [holder]'s document is out of the library, by its row or the journal ([RescanDecision.hidden]). */
    val holderHidden: Boolean,
    /** Whether a relink superseded [hash]: an earlier version of another document. */
    val superseded: Boolean,
    val known: DocumentEntity?,
    /**
     * Whether [known]'s own path, another than this one, is listed and still holds its bytes: not
     * hashed by this pass, or hashed to them.
     */
    val knownStillOnDisk: Boolean,
)

/** What a rescan does with one hashed file ([RescanDecision.file]). */
internal class FileDecision(
    /**
     * The row that held the file's path, moved to its sentinel for the document there now: missing
     * when its document was in the library, its flags kept when it was hidden (a trashed document's
     * file went to the trash; it was not replaced).
     */
    val displaced: DocumentEntity?,
    val outcome: FileOutcome,
)

internal sealed interface FileOutcome {
    /** Bytes a relink superseded: no row of their own, which a fold would hide again at once. */
    data object Superseded : FileOutcome

    /** A copy of a document whose own path still holds it. */
    data class Duplicate(
        val report: DuplicateReport,
    ) : FileOutcome

    /** The document's file moved here: its row follows, with the file's stat. */
    data class Moved(
        val row: DocumentEntity,
    ) : FileOutcome

    /** The document's file, where its row has it, with another stat or flagged missing: the row is refreshed. */
    data class Refreshed(
        val row: DocumentEntity,
    ) : FileOutcome

    /** The document's file, where its row has it, as its row has it. */
    data object Unchanged : FileOutcome

    /**
     * Bytes no row holds: a new document. [replacedInPlace] proposes it as an edit of the document
     * whose file it took the place of, when that document was in the library.
     */
    data class Added(
        val row: DocumentEntity,
        val replacedInPlace: RelinkCandidate?,
    ) : FileOutcome
}

/**
 * What one rescan's reconcile writes and reports: the rows it changed ([writes], in the order it
 * decided them, since a row vacating a path must be written before the one taking it), the rows it
 * finds missing now ([nowMissing], written after), its counters, the copies it found and the relink
 * candidates for the missing documents.
 */
internal class RescanPlan(
    val writes: List<DocumentEntity>,
    val nowMissing: List<DocumentEntity>,
    val added: Int,
    val moved: Int,
    val changed: Int,
    val missingCount: Int,
    val duplicates: List<DuplicateReport>,
    val relinkCandidates: List<RelinkCandidate>,
)

internal object RescanDecision {
    /**
     * Whether a listed file must be hashed: no row holds its path, the row there is hidden (its
     * stored stat is no evidence of the file there now), or the file's stat is not the one the row
     * stored ([hasStoredStat], which a missing mtime never proves).
     */
    fun needsHash(
        meta: FileMeta,
        row: PathMeta?,
    ): Boolean = row == null || row.hidden || !meta.hasStoredStat(row.size, row.mtime)

    /**
     * Every listed path whose content hash this pass knows without reading the file again: the ones
     * it just hashed ([hashes]), and the [indexable] ones it did not have to ([changed] lists those
     * it did), whose size and mtime matched their stored row in [byPath], which is the same evidence
     * the pass itself trusts to skip hashing them.
     */
    fun knownHashes(
        indexable: List<FileMeta>,
        changed: List<FileMeta>,
        byPath: Map<String, PathMeta>,
        hashes: Map<String, String>,
    ): Map<String, String> {
        val changedPaths = changed.mapTo(HashSet()) { it.relPath }
        val known = HashMap(hashes)
        for (meta in indexable) {
            if (meta.relPath in changedPaths) continue
            byPath[meta.relPath]?.let { known[meta.relPath] = it.docId }
        }
        return known
    }

    /**
     * The moves a user may have made outside the app, as the listing and the [journal] show them:
     * each row of [before] whose path is not listed and whose bytes are listed at exactly one other
     * path (by [hashes], else by the row that path had), paired with that path.
     *
     * A file the app's own crash recovery named is no move. Nor is a file at a path a `storagePath`
     * event already names for the document, current or overruled ([RescanJournal.named]): some
     * device was told to put it there, and a sync tool mirrors that device's move, often before the
     * event arrives or after a newer one overruled it. Taking such a mirror for a user's move would
     * journal an older place as the newest intent, and two devices would move the document back and
     * forth for good. The cost: a user who moves a file back to a place the journal named for it
     * before sees the app move it to its intended path again. A file at a path no event names is
     * taken for the user's move, whether or not the journal names any path for the document yet:
     * nothing explains it otherwise, and a rename whose event is still on its way names the same
     * path, so recording it again changes no device's place for the document.
     * [ExternalDocumentMoves] confirms each candidate against what it observed and the files as they
     * are now.
     */
    fun externalMoveCandidates(
        before: List<PathMeta>,
        files: List<FileMeta>,
        hashes: Map<String, String>,
        journal: RescanJournal,
    ): List<Pair<PathMeta, String>> {
        val known = before.associateBy { it.relPath }
        val paths = files.mapTo(HashSet()) { it.relPath }
        val byHash = files.groupBy { hashes[it.relPath] ?: known[it.relPath]?.docId }
        return before.mapNotNull { old ->
            val target = byHash[old.docId]?.singleOrNull()?.relPath ?: return@mapNotNull null
            val moved =
                old.relPath !in paths && old.relPath != target &&
                    !NpnPaths.fileName(target).startsWith(NpnPaths.Temp.RECOVERY_PREFIX) &&
                    !journal.named(old.docId, target)
            if (moved) old to target else null
        }
    }

    /**
     * The hashed contents the projection names no row of: deleted rows (tombstones), which the pass
     * reads by id, so a document's own bytes back at its path refresh its row instead of starting one.
     */
    fun tombstoneIds(
        hashes: Map<String, String>,
        rows: List<DocumentEntity>,
    ): List<String> {
        val known = rows.mapTo(HashSet()) { it.docId }
        return hashes.values.filterNot(known::contains).distinct()
    }

    /**
     * Whether [row]'s document is out of the library: trashed or deleted by its row, or by the
     * [journal] a rescan folds after its reconcile, which another device's trash reaches before the
     * row does. Its file is not expected at its path, so it never goes missing there and is never a
     * relink candidate.
     */
    fun hidden(
        row: DocumentEntity,
        journal: RescanJournal,
    ): Boolean = row.trashed || row.deleted || journal.hides(row.docId)

    /**
     * Decide one hashed file ([facts]): whether the row at its path gives the path up, and what the
     * file is to the document of its bytes: superseded, a duplicate, moved, refreshed, unchanged or
     * a new document added [now].
     */
    fun file(
        facts: FileFacts,
        now: Long,
    ): FileDecision {
        val meta = facts.meta
        val displaced =
            facts.holder?.let {
                it.copy(relPath = missingSentinel(it.docId), missing = it.missing || !facts.holderHidden)
            }
        val known = facts.known
        val outcome =
            when {
                facts.superseded -> FileOutcome.Superseded
                known == null -> added(facts, now)
                facts.knownStillOnDisk -> FileOutcome.Duplicate(DuplicateReport(known.docId, meta.relPath))
                known.relPath != meta.relPath -> FileOutcome.Moved(known.at(meta))
                known.missing || !known.statOf(meta) -> FileOutcome.Refreshed(known.at(meta))
                else -> FileOutcome.Unchanged
            }
        return FileDecision(displaced, outcome)
    }

    /** Whether this row stores [meta]'s size and mtime, whatever they prove. */
    private fun DocumentEntity.statOf(meta: FileMeta): Boolean = size == meta.size && mtime == meta.mtimeMillis

    private fun added(
        facts: FileFacts,
        now: Long,
    ): FileOutcome.Added {
        val meta = facts.meta
        val row =
            DocumentEntity(
                docId = facts.hash,
                relPath = meta.relPath,
                fileName = meta.name,
                size = meta.size,
                mtime = meta.mtimeMillis,
                addedAt = now,
                // reviewed defaults to false (new doc = needs review).
            )
        // External edit: the new bytes may be an edit of the document that was here.
        val replaced =
            facts.holder?.takeUnless { facts.holderHidden }?.let {
                RelinkCandidate(
                    it.docId,
                    facts.hash,
                    signals = setOf(RelinkSignal.REPLACED_IN_PLACE),
                    score = RelinkPairing.SCORE_PATH_MATCH,
                )
            }
        return FileOutcome.Added(row, replaced)
    }

    /** This row, its file found at [meta]: its path, name and stat, and not missing. */
    private fun DocumentEntity.at(meta: FileMeta): DocumentEntity =
        if (relPath == meta.relPath) {
            copy(size = meta.size, mtime = meta.mtimeMillis, missing = false)
        } else {
            copy(
                relPath = meta.relPath,
                fileName = meta.name,
                size = meta.size,
                mtime = meta.mtimeMillis,
                missing = false,
            )
        }

    /**
     * Reconcile the files a rescan hashed against the rows it holds. [hashed] are the files it handed
     * to hashing, in listing order, and [hashes] the content of each that stayed unchanged while it
     * was read; [projection] is every row the index names a path for, [rows] those rows, and
     * [tombstones] the deleted rows of hashed contents ([tombstoneIds]). [diskPaths] is every
     * indexable path listed and still there.
     *
     * Each hashed file is decided by [file], in order, against the rows as the files before it left
     * them. Then every shown row whose path was not listed is missing, and the missing documents are
     * paired with the new ones as relink candidates ([RelinkPairing]).
     */
    fun plan(
        hashed: List<FileMeta>,
        hashes: Map<String, String>,
        projection: List<PathMeta>,
        rows: List<DocumentEntity>,
        tombstones: List<DocumentEntity>,
        diskPaths: Set<String>,
        journal: RescanJournal,
        now: Long,
    ): RescanPlan {
        val pass = PlanPass(rows, tombstones)
        for (meta in hashed) {
            val hash = hashes[meta.relPath] ?: continue
            pass.take(meta, file(pass.facts(meta, hash, diskPaths, hashes, journal), now))
        }
        // Every row the pass changed is current in byId, so this reads the rows as the pass left them.
        val missing =
            projection
                .mapNotNull { pass.byId[it.docId] }
                .filter { !hidden(it, journal) && it.relPath !in diskPaths }
        val candidates = pass.relinkCandidates + RelinkPairing.pair(missing, pass.newDocs, pass.relinkCandidates)
        return RescanPlan(
            writes = pass.writes,
            nowMissing = missing.filterNot { it.missing }.map { it.copy(missing = true) },
            added = pass.added,
            moved = pass.moved,
            changed = pass.changed,
            missingCount = missing.size,
            duplicates = pass.duplicates,
            relinkCandidates = candidates,
        )
    }

    /** One [plan]'s running state: the rows as the files decided so far left them, and what it found. */
    private class PlanPass(
        rows: List<DocumentEntity>,
        tombstones: List<DocumentEntity>,
    ) {
        var added = 0
        var moved = 0
        var changed = 0
        val duplicates = ArrayList<DuplicateReport>()
        val relinkCandidates = ArrayList<RelinkCandidate>()

        /** New documents this pass: docId -> meta, for relink pairing. */
        val newDocs = HashMap<String, FileMeta>()

        /** Every row the pass holds, kept current with each write, by document. */
        val byId = HashMap<String, DocumentEntity>()

        /** The same rows by the path each holds now; a tombstone is known by id alone ([loadById]). */
        private val byPath = HashMap<String, DocumentEntity>()

        /** Rows to write, in the order the pass decided them. */
        val writes = ArrayList<DocumentEntity>()

        init {
            rows.forEach(::load)
            tombstones.forEach(::loadById)
        }

        private fun load(row: DocumentEntity) {
            byId[row.docId] = row
            byPath[row.relPath] = row
        }

        /**
         * Know [row] by id alone. A tombstone (`deleted`) row is read this way, for the document's own
         * bytes coming back: they return the document, not start one, while its path stays out of
         * [byPath]: another document landing on it still takes it (upsertAll moves the hidden holder
         * aside).
         */
        private fun loadById(row: DocumentEntity) {
            byId[row.docId] = row
        }

        /** The facts [file] decides [meta], with content [hash], by, as the rows stand now. */
        fun facts(
            meta: FileMeta,
            hash: String,
            diskPaths: Set<String>,
            hashes: Map<String, String>,
            journal: RescanJournal,
        ): FileFacts {
            val holder = byPath[meta.relPath]?.takeIf { it.docId != hash }
            val known = byId[hash]
            val oldPath = known?.relPath
            return FileFacts(
                meta = meta,
                hash = hash,
                holder = holder,
                holderHidden = holder != null && hidden(holder, journal),
                superseded = journal.successorOf(hash) != hash,
                known = known,
                knownStillOnDisk =
                    oldPath != null && oldPath in diskPaths && oldPath != meta.relPath &&
                        hashes[oldPath].let { it == null || it == hash },
            )
        }

        /** Take [decision] about [meta] into the rows and the counters. */
        fun take(
            meta: FileMeta,
            decision: FileDecision,
        ) {
            decision.displaced?.let(::write)
            when (val outcome = decision.outcome) {
                FileOutcome.Superseded, FileOutcome.Unchanged -> {}

                is FileOutcome.Duplicate -> {
                    duplicates += outcome.report
                }

                is FileOutcome.Moved -> {
                    write(outcome.row)
                    moved++
                }

                is FileOutcome.Refreshed -> {
                    write(outcome.row)
                }

                is FileOutcome.Added -> {
                    write(outcome.row)
                    newDocs[outcome.row.docId] = meta
                    added++
                    outcome.replacedInPlace?.let {
                        changed++
                        relinkCandidates += it
                    }
                }
            }
        }

        /** Queue [row] to be written, after every row queued so far, and keep both lookups current. */
        private fun write(row: DocumentEntity) {
            val old = byId[row.docId]
            if (old != null && byPath[old.relPath]?.docId == row.docId) byPath.remove(old.relPath)
            load(row)
            writes += row
        }
    }
}
