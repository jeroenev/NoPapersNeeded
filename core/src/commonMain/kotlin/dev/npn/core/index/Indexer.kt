package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.PathMeta
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TempFileSweep
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.PendingOp
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the library folder and metadata journals into the Room index cache.
 *
 * Files are the source of truth: the cache is fully rebuildable from a rescan. A rescan:
 *  1. lists the tree and partitions it (indexable PDFs, internal .npn, sync-conflict, temps, other),
 *  2. detects unchanged files by the path+size+mtime fast path and hashes only the rest,
 *  3. reconciles hashed files against the DB projection (move / external duplicate / external edit /
 *     brand-new),
 *  4. flags disappeared files as missing and builds relink candidates,
 *  5. handles trash resurrection (reported through `onResurrected` at the end of the scan),
 *  6. folds the journals and applies effective views (tags, props, trash, deletes, relinks) into the
 *     DB, then updates FTS,
 *  7. moves documents to the paths their intents name, and records where each was seen,
 *  8. deletes the app's own temporary files a crash left behind ([TempFileSweep]),
 *  9. returns a [ScanReport].
 *
 * Steps 3 to 6 write in one database transaction, as every fold does; step 7 is filesystem work
 * and runs after it commits (see [projection]). The fold itself is [FoldProjection]'s.
 *
 * Not safe for concurrent calls: [JournalCoordinator] makes every call under its
 * single-writer lock, and nothing else calls in.
 */
internal class Indexer(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val clock: EpochClock = SystemEpochClock,
    private val log: NpnLog = NpnLog.None,
    /** Where files are hashed, [HASH_CONCURRENCY] at a time. */
    private val hashing: CoroutineDispatcher = Dispatchers.Default,
) {
    private val externalMoves = ExternalDocumentMoves(fs, db.keyValueDao())
    private val documentPaths = DocumentPathReconciler(fs, db, externalMoves)
    private val folds = FoldProjection(db, documentPaths)
    private val tempFiles = TempFileSweep(fs, log)

    private val _scanState = MutableStateFlow<IndexProgress?>(null)

    /** Lightweight progress surface; null when idle. */
    val scanState: StateFlow<IndexProgress?> get() = _scanState.asStateFlow()

    private val documentDao get() = db.documentDao()
    private val duplicateDao get() = db.duplicateDao()

    /**
     * Run [block], the journal bookkeeping of a write plus the folds that project it, as one
     * database transaction. Moving the files the folds' path intents ask for, and recording where
     * documents were seen, is filesystem work: it waits until the transaction has committed rather
     * than holding SQLite's write lock through it. A fold or projection inside [block] joins it.
     */
    suspend fun <T> projection(block: suspend () -> T): T =
        documentPaths.deferring { db.withWriteTransaction { block() } }

    /** The rows of [docIds] as they stand, for [putBack] to restore. */
    suspend fun rowsOf(docIds: Set<String>): List<DocumentEntity> = documentDao.getByDocIds(docIds.toList())

    /** Whether a rescan ever projected a document into the index. */
    suspend fun hasDocuments(): Boolean = documentDao.any()

    /**
     * Write back [rows], which [rowsOf] read for [docIds], undoing what was written to those rows
     * since; a document that had no row then loses the one it has now.
     */
    suspend fun putBack(
        docIds: Set<String>,
        rows: List<DocumentEntity>,
    ) = db.withWriteTransaction {
        val had = rows.mapTo(HashSet()) { it.docId }
        for (docId in docIds - had) documentDao.delete(docId)
        documentDao.upsertAll(rows)
    }

    suspend fun rescan(
        metadataState: FoldedState,
        onExternalMoves: suspend (List<PendingOp>) -> Unit = {},
        onResurrected: suspend (List<Resurrected>) -> Unit = {},
    ): ScanReport {
        check(!documentPaths.collecting) { "A rescan does filesystem work, so it cannot run inside a projection" }
        val started = clock.nowMillis()
        _scanState.value = IndexProgress(IndexProgress.Phase.LISTING)
        return try {
            // Indexable walk: `.npn/` (journal, OCR sidecars, trash) is never indexed, so skip
            // it instead of traversing the whole metadata tree on every rescan.
            val all = fs.listAllIndexable()

            val (indexable, conflictFiles) = partition(all)

            val dbProjection = documentDao.allPathsAndMeta()
            val byPath = dbProjection.associateBy { it.relPath }
            // A hidden row's stat is no evidence: a file at its old path is its own only if it hashes so.
            val changed =
                indexable.filter { meta ->
                    val known = byPath[meta.relPath]
                    known == null || known.hidden || !meta.hasStoredStat(known.size, known.mtime)
                }
            // A file still being written is left for a later scan rather than given the
            // identity of a partial file. A known path keeps its old row meanwhile.
            val settle = WriteSettle(fs, started)
            val deferred = DeferredFiles()
            val toHash =
                changed.filter { meta ->
                    when (val verdict = settle.verdict(meta)) {
                        WriteSettle.Verdict.Ready -> {
                            true
                        }

                        WriteSettle.Verdict.Skip -> {
                            false
                        }

                        is WriteSettle.Verdict.Wait -> {
                            deferred.add(meta.relPath, verdict.millis)
                            false
                        }
                    }
                }

            val gone = HashSet<String>()
            val hashes = hashAll(toHash, settle, deferred, gone)
            // A file gone by the time it was read is gone, as it would be to a listing a moment later.
            val present = if (gone.isEmpty()) indexable else indexable.filterNot { it.relPath in gone }

            _scanState.value = IndexProgress(IndexProgress.Phase.RECONCILING)
            val moves = externalMoves.detect(metadataState, dbProjection, present, hashes)
            if (moves.isNotEmpty()) onExternalMoves(moves)
            val diskPaths = present.map { it.relPath }.toHashSet()
            val contentAt = knownHashes(present, changed, byPath, hashes)

            // The reconcile writes and the fold land in one transaction, so no reader sees a row
            // before its tags and fields; the files their intents move wait until it commits.
            val recon =
                documentPaths.deferring(known = contentAt) {
                    db.withWriteTransaction {
                        reconcile(toHash, hashes, dbProjection, diskPaths, metadataState).also { recon ->
                            val missing = detectMissing(dbProjection, diskPaths, recon.existingByDocId, metadataState)
                            recon.missingCount = missing.size
                            recon.relinkCandidates.addAll(
                                RelinkPairing.pair(missing, recon.newDocs, recon.relinkCandidates),
                            )

                            _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
                            recon.resurrected = folds.rescan(metadataState, FoldScope.FullRescan(contentAt))
                        }
                    }
                }
            if (recon.duplicates.isNotEmpty()) documentPaths.reconcileDuplicates(metadataState, recon.duplicates)
            // Every rescan lists the whole library, so its duplicates are all there are: a copy since
            // deleted, or overwritten with another document, loses its row.
            duplicateDao.replaceAll(recon.duplicates.map { DuplicateEntity(it.docId, it.extraRelPath) })

            // The listing is at hand: clear away what a crash left of the app's own temporary files.
            tempFiles.sweep(all, started)

            // Last: recording a return is a journal write, which must not cost the scan its
            // duplicates or its sweep if it fails.
            if (recon.resurrected.isNotEmpty()) onResurrected(recon.resurrected)

            ScanReport(
                added = recon.added,
                moved = recon.moved,
                changed = recon.changed,
                missingCount = recon.missingCount,
                duplicates = recon.duplicates,
                conflictFiles = conflictFiles,
                relinkCandidates = recon.relinkCandidates,
                // Every file handed to the pool counts, settled, unreadable or gone.
                hashedCount = toHash.size,
                durationMillis = clock.nowMillis() - started,
                pathConflicts = documentPaths.conflicts(metadataState),
                deferred = deferred.paths,
                retryAfterMillis = deferred.retryAfterMillis,
            )
        } finally {
            _scanState.value = null
        }
    }

    /**
     * Hash [toHash] and return relPath -> sha256 for every file that stayed unchanged while it was
     * hashed; one written to meanwhile goes to [deferred] instead, as its hash is of no settled
     * content. A fixed pool of [HASH_CONCURRENCY] workers pulls from a shared queue: that caps read
     * concurrency against the storage provider, and balances the work, since file sizes (and so
     * hash costs) vary by orders of magnitude.
     *
     * A file that cannot be read fails nothing: a failed scan would block every write. One gone
     * since the listing goes to [gone]; any other is logged and left out of this scan, so a row it
     * has stays as it is. Not deferred: a file that stays unreadable would be retried every few
     * seconds, where the next rescan is soon enough.
     */
    private suspend fun hashAll(
        toHash: List<FileMeta>,
        settle: WriteSettle,
        deferred: DeferredFiles,
        gone: MutableSet<String>,
    ): Map<String, String> {
        _scanState.value = IndexProgress(IndexProgress.Phase.HASHING, hashed = 0, total = toHash.size)
        val hashes = HashMap<String, String>()
        val progressMutex = Mutex()
        var hashedCount = 0
        coroutineScope {
            val pending = Channel<FileMeta>(Channel.UNLIMITED)
            // Unlimited, so no send waits for the workers.
            for (meta in toHash) pending.send(meta)
            pending.close()
            repeat(minOf(HASH_CONCURRENCY, toHash.size)) {
                launch(hashing) {
                    for (meta in pending) {
                        val hash = runCatchingCancellable { fs.sha256(meta.relPath) }
                        val stable = hash.isSuccess && settle.unchangedSince(meta)
                        val vanished = hash.exceptionOrNull()?.let { goneAfter(meta.relPath, it) } == true
                        progressMutex.withLock {
                            hash.onSuccess { value ->
                                if (stable) {
                                    hashes[meta.relPath] = value
                                } else {
                                    deferred.add(meta.relPath, WriteSettle.QUIET_MILLIS)
                                }
                            }
                            if (vanished) gone += meta.relPath
                            hashedCount++
                            _scanState.value =
                                IndexProgress(IndexProgress.Phase.HASHING, hashed = hashedCount, total = toHash.size)
                        }
                    }
                }
            }
        }
        return hashes
    }

    /** Whether [relPath], whose read failed with [failure], is gone; one still there is logged. */
    private suspend fun goneAfter(
        relPath: String,
        failure: Throwable,
    ): Boolean {
        val gone = runCatchingCancellable { fs.stat(relPath) == null }.getOrDefault(false)
        if (!gone) log.warn("Hashing $relPath failed; it is left as it was until a rescan can read it", failure)
        return gone
    }

    /**
     * Split all on-disk entries into indexable documents and sync-conflict files. Conflict files
     * are reported separately (the library shows their count) and never indexed; everything else
     * runs through the shared [NpnPaths.indexable] predicate.
     */
    private fun partition(all: List<FileMeta>): Pair<List<FileMeta>, List<String>> {
        val indexable = ArrayList<FileMeta>()
        val conflictFiles = ArrayList<String>()
        for (meta in all) {
            // A name the library cannot address is neither, whatever it looks like.
            if (!NpnPaths.isSafe(meta.relPath)) continue
            // The walk leaves `.npn/` out, so nothing here is the app's own.
            if (!meta.isDirectory && NpnPaths.isSyncConflict(meta.name)) {
                conflictFiles.add(meta.relPath)
            } else if (NpnPaths.indexable(meta)) {
                indexable.add(meta)
            }
        }
        return indexable to conflictFiles
    }

    /** Mutable accumulator for one rescan's reconcile and fold, plus the doc rows it touched. */
    private class Reconciliation {
        var added = 0
        var moved = 0
        var changed = 0
        var missingCount = 0
        var resurrected: List<Resurrected> = emptyList()
        val duplicates = ArrayList<DuplicateReport>()
        val relinkCandidates = ArrayList<RelinkCandidate>()

        // New docs created this scan: docId -> meta, for relink pairing.
        val newDocs = HashMap<String, FileMeta>()

        // Canonical rows we loaded/updated, kept current so later steps re-read consistently.
        val existingByDocId = HashMap<String, DocumentEntity>()

        // The same rows by the path each holds now.
        val existingByPath = HashMap<String, DocumentEntity>()

        // Rows to write, in the order the pass decided them: relPath is unique, so a row that
        // vacates a path must be written before the one that takes it.
        val writes = ArrayList<DocumentEntity>()

        /** Take [row] as the pass found it. */
        fun load(row: DocumentEntity) {
            existingByDocId[row.docId] = row
            existingByPath[row.relPath] = row
        }

        /**
         * Know [row] by id alone. A tombstone (`deleted`) row is read this way, for the document's
         * own bytes coming back: they return the document, not start one, while its path stays out
         * of [existingByPath] — another document landing on it still takes it (upsertAll moves the
         * hidden holder aside).
         */
        fun loadById(row: DocumentEntity) {
            existingByDocId[row.docId] = row
        }

        /** Queue [row] to be written, after every row queued so far, and keep both lookups current. */
        fun write(row: DocumentEntity) {
            val old = existingByDocId[row.docId]
            if (old != null && existingByPath[old.relPath]?.docId == row.docId) existingByPath.remove(old.relPath)
            load(row)
            writes += row
        }
    }

    /**
     * Reconcile hashed files against the DB. For each hashed file, one of: an unchanged/refreshed
     * canonical, a move/rename, an external duplicate, an external edit (old -> missing sentinel +
     * new doc + path-match relink candidate), a brand-new doc, or nothing for bytes a relink
     * superseded, which get no row of their own. A row whose path now holds another
     * document's bytes gives the path up: missing when its file was there, only trashed when it was
     * in the trash ([hidden], judged with the journal [state] this pass folds). Writes the rows it
     * changed in one batch and returns the counters + new docs + duplicates + relink candidates
     * collected.
     */
    private suspend fun reconcile(
        toHash: List<FileMeta>,
        hashes: Map<String, String>,
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
        state: FoldedState,
    ): Reconciliation {
        val recon = Reconciliation()
        // One bulk read (chunked under SQLite's bind limit), not a query per document: this runs
        // over the whole projection on every rescan.
        for (row in documentDao.getByDocIds(dbProjection.map { it.docId })) recon.load(row)
        // Rows the projection does not name are tombstones (`deleted` rows). The hashed bytes may
        // be their document's own, back at its path: read them by id, so the return refreshes that
        // row with its date added, page count and OCR bookkeeping instead of starting a new one.
        val tombstoned = hashes.values.filterNot(recon.existingByDocId::containsKey).distinct()
        for (row in documentDao.getByDocIds(tombstoned)) recon.loadById(row)
        val now = clock.nowMillis()

        for (meta in toHash) {
            val hash = hashes[meta.relPath] ?: continue
            reconcileFile(meta, hash, diskPaths, hashes, state, recon, now)
        }
        if (recon.writes.isNotEmpty()) documentDao.upsertAll(recon.writes)
        return recon
    }

    /** Reconcile one hashed file, [meta] with content [hash], into [recon]; see [reconcile]. */
    private fun reconcileFile(
        meta: FileMeta,
        hash: String,
        diskPaths: Set<String>,
        hashes: Map<String, String>,
        state: FoldedState,
        recon: Reconciliation,
        now: Long,
    ) {
        // The row holding this path, when the bytes here are no longer its own. It must vacate
        // the path (relPath is unique) for the document that is here now: it moves to a per-doc
        // sentinel path under .npn (never indexed) that preserves its identity and metadata. A
        // hidden row keeps its flags, as DocumentDao.upsertAll keeps them: a trashed document's
        // file went to the trash, it was not replaced, so it is neither missing nor a relink
        // candidate for the bytes here.
        val replaced = recon.existingByPath[meta.relPath]?.takeIf { it.docId != hash }
        val overwritten = replaced != null && !hidden(replaced, state)
        replaced?.let {
            recon.write(
                it.copy(relPath = missingSentinel(it.docId), missing = it.missing || overwritten),
            )
        }

        // Bytes a relink superseded (a bake's original) are an earlier version of another
        // document, which no event makes live again: a row rewrite would give the superseded
        // document the copy's path, and a new one would be counted and offered for a relink,
        // then hidden by this very fold, on every rescan.
        if (state.resolveDocId(hash) != hash) return

        val existingDoc = recon.existingByDocId[hash]
        if (existingDoc != null) {
            reconcileKnownHash(existingDoc, meta, diskPaths, hashes, recon)
            return
        }

        recon.write(newDocument(hash, meta, now))
        recon.newDocs[hash] = meta
        recon.added++
        if (replaced != null && overwritten) {
            // External edit: the new bytes may be an edit of the document that was here.
            recon.changed++
            recon.relinkCandidates.add(
                RelinkCandidate(
                    replaced.docId,
                    hash,
                    signals = setOf(RelinkSignal.REPLACED_IN_PLACE),
                    score = RelinkPairing.SCORE_PATH_MATCH,
                ),
            )
        }
    }

    /**
     * Handle a hashed file whose content hash already maps to a known canonical doc. Its old path
     * still holds it unless the file there is gone or this pass hashed it to other bytes.
     */
    private fun reconcileKnownHash(
        existingDoc: DocumentEntity,
        meta: FileMeta,
        diskPaths: Set<String>,
        hashes: Map<String, String>,
        recon: Reconciliation,
    ) {
        val hash = existingDoc.docId
        val oldPath = existingDoc.relPath
        val canonicalStillOnDisk =
            oldPath in diskPaths && oldPath != meta.relPath && hashes[oldPath].let { it == null || it == hash }
        if (canonicalStillOnDisk) {
            // External duplicate: canonical untouched, record the extra path.
            recon.duplicates.add(DuplicateReport(hash, meta.relPath))
        } else if (oldPath != meta.relPath) {
            // The file moved/renamed: canonical path no longer on disk (or is this path).
            val movedDoc =
                existingDoc.copy(
                    relPath = meta.relPath,
                    fileName = meta.name,
                    size = meta.size,
                    mtime = meta.mtimeMillis,
                    missing = false,
                )
            recon.write(movedDoc)
            recon.moved++
        } else if (existingDoc.missing || existingDoc.size != meta.size || existingDoc.mtime != meta.mtimeMillis) {
            // Same path, bytes unchanged but was flagged (e.g. mtime touched): refresh.
            recon.write(existingDoc.copy(size = meta.size, mtime = meta.mtimeMillis, missing = false))
        }
    }

    /**
     * Mark docs whose canonical path is no longer on disk as missing (skipping [hidden] ones), and
     * return every doc that is missing now.
     */
    private suspend fun detectMissing(
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
        existingByDocId: Map<String, DocumentEntity>,
        state: FoldedState,
    ): List<DocumentEntity> {
        // [Reconciliation.existingByDocId] is kept current for every row the reconcile pass
        // upserted, so it already holds the post-reconcile row; no need to re-read it.
        val missingDocs =
            dbProjection
                .mapNotNull { existingByDocId[it.docId] }
                .filter { !hidden(it, state) && it.relPath !in diskPaths }
        val nowMissing = missingDocs.filterNot { it.missing }.map { it.copy(missing = true) }
        if (nowMissing.isNotEmpty()) documentDao.upsertAll(nowMissing)
        return missingDocs
    }

    /**
     * Whether [row]'s document is out of the library: trashed or deleted by its row, or by the
     * journal [state] a rescan folds after its reconcile, which another device's trash reaches
     * before the row does. Its file is not expected at its path, so it never goes missing there and
     * is never a relink candidate.
     */
    private fun hidden(
        row: DocumentEntity,
        state: FoldedState,
    ): Boolean = row.trashed || row.deleted || state.hiddenBy(row.docId) != null

    /** Apply already-reduced events without reading journal files or walking the PDF library. */
    suspend fun refreshEvents(
        state: FoldedState,
        events: List<JournalEvent>,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ) {
        if (events.isEmpty()) return
        folding {
            projection { folds.apply(state, FoldScope.Events.of(state, events, preserveLifecycleDocs)) }
        }
    }

    /** Reproject a reconciled mixed-version accumulator without rereading journal sources. */
    suspend fun refreshState(state: FoldedState) {
        folding { projection { folds.apply(state, FoldScope.WholeDb) } }
    }

    /** Run [fold], showing the folding phase while it runs. */
    private inline fun <T> folding(fold: () -> T): T {
        _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
        try {
            return fold()
        } finally {
            _scanState.value = null
        }
    }

    private fun newDocument(
        hash: String,
        meta: FileMeta,
        now: Long,
    ): DocumentEntity =
        DocumentEntity(
            docId = hash,
            relPath = meta.relPath,
            fileName = meta.name,
            size = meta.size,
            mtime = meta.mtimeMillis,
            addedAt = now,
            // reviewed defaults to false (new doc = needs review).
        )

    private companion object {
        const val HASH_CONCURRENCY = 4
    }
}

/**
 * Every listed path whose content hash this pass knows without reading the file again: the ones
 * it just hashed, and the unchanged ones whose size and mtime matched their stored row, which is
 * the same evidence the pass itself trusts to skip hashing them.
 */
private fun knownHashes(
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
