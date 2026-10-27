package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.PathMeta
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TempFileSweep
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.PendingOp
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
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
 *  8. deletes the app's own temporary files a crash left behind ([TempFileSweep]), and a bake's
 *     original its relink superseded ([removeSupersededOriginals]),
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
    ids: IdSource = IdSource.Random,
) {
    private val externalMoves = ExternalDocumentMoves(fs, db.keyValueDao())
    private val documentPaths = DocumentPathReconciler(fs, db, externalMoves, ids)
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

    /** Write [row], a document's row as a lifecycle change leaves it ([JournalCoordinator.changeLifecycle]). */
    suspend fun writeRow(row: DocumentEntity) = documentDao.upsert(row)

    /** Move a document's file from [from] to [to], for a lifecycle change ([JournalCoordinator.changeLifecycle]). */
    suspend fun moveFile(
        from: String,
        to: String,
    ) = fs.move(from, to)

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
            val changed = indexable.filter { meta -> RescanDecision.needsHash(meta, byPath[meta.relPath]) }
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
            val contentAt = RescanDecision.knownHashes(present, changed, byPath, hashes)

            // The reconcile writes and the fold land in one transaction, so no reader sees a row
            // before its tags and fields; the files their intents move wait until it commits.
            var resurrected: List<Resurrected> = emptyList()
            val plan =
                documentPaths.deferring(known = contentAt) {
                    db.withWriteTransaction {
                        reconcile(toHash, hashes, dbProjection, diskPaths, metadataState).also {
                            _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
                            resurrected = folds.rescan(metadataState, FoldScope.FullRescan(contentAt))
                        }
                    }
                }
            // A copy at the path its document's intent names is the document's own file once the path
            // work moved the document onto it; its old file is then the copy, which the next rescan lists.
            val held =
                documentDao
                    .getByDocIds(plan.duplicates.map { it.docId }.distinct())
                    .associate { it.docId to it.relPath }
            val duplicates = plan.duplicates.filterNot { held[it.docId] == it.extraRelPath }
            // Every rescan lists the whole library, so its duplicates are all there are: a copy since
            // deleted, or overwritten with another document, loses its row.
            duplicateDao.replaceAll(duplicates.map { DuplicateEntity(it.docId, it.extraRelPath) })

            // The listing is at hand: clear away what a crash left of the app's own temporary files,
            // and of a bake's original once its relink stands.
            tempFiles.sweep(all, started)
            removeSupersededOriginals(present, hashes, contentAt, metadataState)

            // Last: recording a return is a journal write, which must not cost the scan its
            // duplicates or its sweep if it fails.
            if (resurrected.isNotEmpty()) onResurrected(resurrected)

            ScanReport(
                added = plan.added,
                moved = plan.moved,
                changed = plan.changed,
                missingCount = plan.missingCount,
                duplicates = duplicates,
                conflictFiles = conflictFiles,
                relinkCandidates = plan.relinkCandidates,
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
     * Delete each file in [present] under a recovery name ([NpnPaths.Temp.isRecovery]) whose bytes
     * ([hashes]) a relink in [state] superseded, while the document they became, its relink chain
     * followed, is in the library with its own bytes at its row's path ([contentAt]). Such a file is
     * a bake's original, which the bake deletes itself once its relink stands, left behind by a crash
     * or a delete that failed in between. It is no document of its own (no row; an import of it is a
     * duplicate of the document it became), and kept, it would be hashed at every rescan and carried
     * to every device by a sync tool. It goes only while it still holds those bytes, by its stat as
     * listed, else its hash; the file of the document it became is never touched, so this never
     * deletes the last copy of a document.
     */
    private suspend fun removeSupersededOriginals(
        present: List<FileMeta>,
        hashes: Map<String, String>,
        contentAt: Map<String, String>,
        state: FoldedState,
    ) {
        val originals =
            present.mapNotNull { meta ->
                val hash = hashes[meta.relPath]?.takeIf { NpnPaths.Temp.isRecovery(meta.relPath) }
                hash?.let { state.resolveDocId(it) }?.takeIf { it != hash }?.let { successor ->
                    Triple(meta, hash, successor)
                }
            }
        if (originals.isEmpty()) return
        val successors = documentDao.getByDocIds(originals.map { it.third }.distinct()).associateBy { it.docId }
        for ((meta, hash, successor) in originals) {
            successors[successor]?.takeIf { it.isLive && contentAt[it.relPath] == successor } ?: continue
            log.runLogged("Removing ${meta.relPath}, the original of a baked document") {
                val now = fs.stat(meta.relPath)?.takeIf { !it.isDirectory }
                if (now != null && (now.hasStoredStat(meta.size, meta.mtimeMillis) || fs.holds(meta.relPath, hash))) {
                    fs.delete(meta.relPath)
                }
            }
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

    /**
     * Reconcile hashed files against the DB: read the rows the pass decides by, let
     * [RescanDecision.plan] decide what each file is to them (an unchanged or refreshed canonical, a
     * move or rename, an external duplicate, an external edit, a brand-new doc, or nothing for bytes
     * a relink superseded) and which shown rows went missing, and write the rows it changed. Runs
     * inside the rescan's transaction, before its fold.
     */
    private suspend fun reconcile(
        toHash: List<FileMeta>,
        hashes: Map<String, String>,
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
        state: FoldedState,
    ): RescanPlan {
        // One bulk read (chunked under SQLite's bind limit), not a query per document: this runs
        // over the whole projection on every rescan.
        val rows = documentDao.getByDocIds(dbProjection.map { it.docId })
        val tombstones = documentDao.getByDocIds(RescanDecision.tombstoneIds(hashes, rows))
        val plan =
            RescanDecision.plan(
                hashed = toHash,
                hashes = hashes,
                projection = dbProjection,
                rows = rows,
                tombstones = tombstones,
                diskPaths = diskPaths,
                journal = state.rescanJournal(),
                now = clock.nowMillis(),
            )
        if (plan.writes.isNotEmpty()) documentDao.upsertAll(plan.writes)
        if (plan.nowMissing.isNotEmpty()) documentDao.upsertAll(plan.nowMissing)
        return plan
    }

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

    private companion object {
        const val HASH_CONCURRENCY = 4
    }
}
