package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.PathMeta
import dev.npn.core.db.TagEntity
import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TempFileSweep
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty
import dev.npn.core.util.NpnLog
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
 * and runs after it commits (see [projection]).
 *
 * Not safe for concurrent calls: [dev.npn.core.app.JournalCoordinator] makes every call under its
 * single-writer lock, and nothing else calls in.
 */
@Suppress("LargeClass")
class Indexer(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val clock: EpochClock = SystemEpochClock,
    log: NpnLog = NpnLog.None,
) {
    private val externalMoves = ExternalDocumentMoves(fs, db.keyValueDao())
    private val documentPaths = DocumentPathReconciler(fs, db, externalMoves)
    private val tempFiles = TempFileSweep(fs, log)

    private val _scanState = MutableStateFlow<IndexProgress?>(null)

    /** Lightweight progress surface; null when idle. */
    val scanState: StateFlow<IndexProgress?> get() = _scanState.asStateFlow()

    private val documentDao get() = db.documentDao()
    private val tagDao get() = db.tagDao()
    private val ftsDao get() = db.ftsDao()
    private val fieldDao get() = db.documentFieldDao()
    private val fieldDefDao get() = db.fieldDefDao()
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

    @Suppress("LongMethod")
    suspend fun rescan(
        metadataState: FoldedState,
        onExternalMoves: (suspend (List<PendingOp>) -> Unit)? = null,
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

            _scanState.value = IndexProgress(IndexProgress.Phase.HASHING, hashed = 0, total = toHash.size)
            val hashes = HashMap<String, String>() // relPath -> sha256
            val progressMutex = Mutex()
            var hashedCount = 0
            // A fixed pool of HASH_CONCURRENCY workers pulling from a shared queue: that caps
            // read concurrency against the storage provider, and balances the work, since file
            // sizes (and so hash costs) vary by orders of magnitude.
            coroutineScope {
                val pending = Channel<FileMeta>(Channel.UNLIMITED)
                // Unlimited and not yet closed, so every offer succeeds. Checked rather than
                // discarded because a capacity change here would otherwise silently drop
                // files from the scan instead of failing.
                for (meta in toHash) {
                    check(
                        pending.trySend(meta).isSuccess,
                    ) { "hash queue rejected ${meta.relPath}" }
                }
                pending.close()
                repeat(minOf(HASH_CONCURRENCY, toHash.size)) {
                    launch(Dispatchers.Default) {
                        for (meta in pending) {
                            val hash = fs.sha256(meta.relPath)
                            // Written to while it was hashed: the hash is of no settled content.
                            val stable = settle.unchangedSince(meta)
                            progressMutex.withLock {
                                if (stable) {
                                    hashes[meta.relPath] = hash
                                } else {
                                    deferred.add(meta.relPath, WriteSettle.QUIET_MILLIS)
                                }
                                hashedCount++
                                _scanState.value =
                                    IndexProgress(
                                        IndexProgress.Phase.HASHING,
                                        hashed = hashedCount,
                                        total = toHash.size,
                                    )
                            }
                        }
                    }
                }
            }

            _scanState.value = IndexProgress(IndexProgress.Phase.RECONCILING)
            if (onExternalMoves != null) {
                val moves = externalMoves.detect(metadataState, dbProjection, indexable, hashes)
                if (moves.isNotEmpty()) onExternalMoves(moves)
            }
            val diskPaths = indexable.map { it.relPath }.toHashSet()
            val contentAt = knownHashes(indexable, changed, byPath, hashes)

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
                            recon.resurrected = applyFold(metadataState, FoldScope.FullRescan(contentAt))
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

            _scanState.value = IndexProgress(IndexProgress.Phase.DONE)

            ScanReport(
                added = recon.added,
                moved = recon.moved,
                changed = recon.changed,
                missingCount = recon.missingCount,
                duplicates = recon.duplicates,
                conflictFiles = conflictFiles,
                relinkCandidates = recon.relinkCandidates,
                hashedCount = hashedCount,
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
     * Split all on-disk entries into indexable documents and sync-conflict files. Conflict files
     * are surfaced separately (the UI offers keep/discard/import-as-new); everything else runs
     * through the shared [NpnPaths.indexable] predicate.
     */
    private fun partition(all: List<FileMeta>): Pair<List<FileMeta>, List<String>> {
        val indexable = ArrayList<FileMeta>()
        val conflictFiles = ArrayList<String>()
        for (meta in all) {
            if (!meta.isDirectory && NpnPaths.isSafe(meta.relPath) && !NpnPaths.isInternal(meta.relPath) &&
                NpnPaths.isSyncConflict(meta.name)
            ) {
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
        val now = clock.nowMillis()

        for (meta in toHash) {
            val hash = hashes[meta.relPath] ?: continue
            val existingDoc = recon.existingByDocId[hash]
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

            if (existingDoc != null) {
                reconcileKnownHash(existingDoc, meta, hash, diskPaths, hashes, recon)
                continue
            }
            // Bytes a relink superseded (a bake's original) are an earlier version of another
            // document, which no event makes live again: written as a new document, they would be
            // counted and offered for a relink, then hidden by this very fold, on every rescan.
            if (state.resolveDocId(hash) != hash) continue

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
                        reason = RelinkPairing.PATH_MATCH,
                        score = RelinkPairing.SCORE_PATH_MATCH,
                    ),
                )
            }
        }
        if (recon.writes.isNotEmpty()) documentDao.upsertAll(recon.writes)
        return recon
    }

    /**
     * Handle a hashed file whose content hash already maps to a known canonical doc. Its old path
     * still holds it unless the file there is gone or this pass hashed it to other bytes.
     */
    private fun reconcileKnownHash(
        existingDoc: DocumentEntity,
        meta: FileMeta,
        hash: String,
        diskPaths: Set<String>,
        hashes: Map<String, String>,
        recon: Reconciliation,
    ) {
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
        val missingDocs = ArrayList<DocumentEntity>()
        val nowMissing = ArrayList<DocumentEntity>()
        for (pm in dbProjection) {
            // [Reconciliation.existingByDocId] is kept current for every row the reconcile pass
            // upserted, so it already holds the post-reconcile row; no need to re-read it.
            val current = existingByDocId[pm.docId] ?: continue
            if (hidden(current, state)) continue
            if (current.relPath !in diskPaths) {
                if (!current.missing) nowMissing.add(current.copy(missing = true))
                missingDocs.add(current)
            }
        }
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

    /** Which documents one [applyFold] projects, and what else it takes into account. */
    private sealed interface FoldScope {
        /**
         * A rescan's fold: every document, preferring a document's own bytes, found back in the
         * library, over a journal that hides it (see [FoldPass.lifecycle]). [contentAt] is the
         * content hash of every listed file the rescan knows, by path ([knownHashes]).
         */
        class FullRescan(
            val contentAt: Map<String, String>,
        ) : FoldScope

        /**
         * The documents [docs] and the tags [styledTags] some events touched. [preserveLifecycleDocs]
         * keep the lifecycle their row already has, which a commit wrote optimistically.
         */
        class Events(
            val docs: Set<String>,
            val styledTags: Set<String>,
            val preserveLifecycleDocs: Set<String>,
        ) : FoldScope

        /** Every document the journal or the index knows. */
        data object WholeDb : FoldScope
    }

    /** Apply already-reduced events without reading journal files or walking the PDF library. */
    suspend fun refreshEvents(
        state: FoldedState,
        events: List<JournalEvent>,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ): List<Resurrected> {
        if (events.isEmpty()) return emptyList()
        _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
        try {
            val documents = LinkedHashSet<String>()
            val styledTags = LinkedHashSet<String>()
            for (event in events) {
                event.doc?.let(documents::add)
                // TagRemove needs no extra scope: its affected document is already added via
                // event.doc above. Rename/merge/relink/style fan out to more docs/tags.
                when (val op = event.op) {
                    is JournalOp.TagRename, is JournalOp.TagMerge -> {
                        documents.addAll(state.taggedDocs())
                    }

                    is JournalOp.TagStyle -> {
                        styledTags += op.tag
                    }

                    is JournalOp.Relink -> {
                        documents.addAll(state.docsWithMetadata())
                        documents += op.newDoc
                    }

                    // A definition change is library-wide: every document's projection of that
                    // field depends on its type (the numeric shadow), so all docs with metadata
                    // are reprojected rather than only the event's own document (there is none).
                    is JournalOp.FieldDefSet -> {
                        documents.addAll(state.docsWithMetadata())
                    }

                    else -> {}
                }
            }
            documents.toList().forEach { documents += state.resolveDocId(it) }
            return projection { applyFold(state, FoldScope.Events(documents, styledTags, preserveLifecycleDocs)) }
        } finally {
            _scanState.value = null
        }
    }

    /** Reproject a reconciled mixed-version accumulator without rereading journal sources. */
    suspend fun refreshState(state: FoldedState): List<Resurrected> {
        _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
        try {
            return projection { applyFold(state, FoldScope.WholeDb) }
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

    /**
     * Apply the folded effective views into Room in one pass and return resurrected docs.
     *
     * Design notes (step 6):
     * - Tags: every tag with a known style gets a [TagEntity] upsert; every doc's document_tags is
     *   replaced with its effective tag set. Tags appearing only in adds (no explicit style) are
     *   upserted with null style so tag-management counts see them.
     * - Fields: every doc's document_fields is replaced with its effective field map (own values
     *   over inherited predecessor values); the well-known five are additionally cached on the
     *   doc row. reviewed/ocrEngineOverride come from the system props.
     * - Trash and delete: the trashed and deleted flags, trashedOrigPath and trashedAt come from the
     *   fold's lifecycle state. Resurrection, on a full rescan only: when the journal says trashed
     *   or deleted but the document's own bytes are back at its current or original path, the file
     *   wins (flags and missing cleared) and the document is reported, so the caller can record a
     *   restore; until one is recorded, any other fold hides it again (see [FoldPass.lifecycle]).
     * - Relinks: fold resolves old->new; the effective views are already keyed on ids. For a folded
     *   relink where both ids are known, the OLD doc is marked deleted/superseded and the NEW doc
     *   carries the metadata (fold exposes effective views on the resolved id).
     * - FTS: after tags/props, each affected doc's metadata-derived fts columns (fileName, tags,
     *   fields) are written. The extracted-text content column belongs to the OCR pipeline, so
     *   the fold never touches it. A brand-new doc gets a row with no content yet, which the OCR
     *   pipeline fills through its own write.
     */
    private suspend fun applyFold(
        state: FoldedState,
        scope: FoldScope,
    ): List<Resurrected> {
        // Every doc the fold must project: those the events touched, or for a full fold, those the
        // journal has data about plus every doc in the DB, so stale trash/delete flags get cleared.
        val journalDocs = LinkedHashSet<String>()
        if (scope is FoldScope.Events) {
            journalDocs.addAll(scope.docs)
        } else {
            journalDocs.addAll(state.docsWithMetadata())
            for (pm in documentDao.allPathsAndMeta()) journalDocs.add(pm.docId)
        }

        // Bulk preload the current projection (rows, tags, fields, FTS content) so the per-doc
        // pass below issues no serial reads; diffed writes are collected and flushed once.
        val preloaded = preloadRows(journalDocs)
        upsertReferencedTags(state, journalDocs, (scope as? FoldScope.Events)?.styledTags.orEmpty())
        // The registry is library-wide and small; a diffed replace per fold keeps it in step
        // with the fold at negligible cost and needs no per-op scoping.
        fieldDefDao.replaceAll(state.effectiveFieldDefs())
        val changes = ProjectionChanges()
        val predecessors = predecessors(state, journalDocs)
        val work = documentPaths.work()
        work.state = state

        val pass = FoldPass(state, scope, predecessors, preloaded, changes, work)
        for (doc in journalDocs) {
            val row = preloaded.rows[doc] ?: continue
            pass.project(doc, row)
        }
        flushChanges(changes)
        // Tags live only through use: once no live document references a name, drop its row so
        // stale tags never linger in tag lists (see TagDao.pruneUnreferenced).
        tagDao.pruneUnreferenced()
        return pass.resurrected
    }

    /**
     * The current DB projection for one [applyFold] pass, read in a handful of bulk queries so
     * the per-doc projection performs no serial reads.
     */
    private class PreloadedRows(
        val rows: Map<String, DocumentEntity>,
        val tags: Map<String, List<String>>,
        val fields: Map<String, List<DocumentFieldEntity>>,
        /**
         * Docs that already have an FTS row. Presence only, never the text: the projection just
         * needs to know whether a doc has been indexed yet.
         */
        val ftsIndexed: Set<String>,
    )

    /**
     * Diffed writes accumulated by one [applyFold] projection pass and flushed once at the end.
     * Each table is written with one bulk call; unchanged tables are left untouched so Room
     * observers only fire on real changes.
     */
    private class ProjectionChanges {
        val docRows = ArrayList<DocumentEntity>()
        val tags = LinkedHashMap<String, List<String>>()
        val fields = LinkedHashMap<String, List<DocumentFieldEntity>>()
        val ftsRows = ArrayList<FtsDocumentEntity>()
    }

    private suspend fun preloadRows(journalDocs: Set<String>): PreloadedRows {
        val docIds = journalDocs.toList()
        val rows = HashMap<String, DocumentEntity>(docIds.size)
        for (row in documentDao.getByDocIds(docIds)) rows[row.docId] = row

        val tags = HashMap<String, List<String>>(docIds.size)
        for ((docId, tagRows) in tagDao.tagsForDocs(docIds).groupBy { it.docId }) {
            tags[docId] = tagRows.map { it.tag }.sorted()
        }

        val fields = fieldDao.fieldsForDocs(docIds).groupBy { it.docId }

        val ftsIndexed = ftsDao.indexedDocIds(docIds).toHashSet()
        return PreloadedRows(rows, tags, fields, ftsIndexed)
    }

    /** Flush one fold's diffed changes, one bulk call per table. */
    private suspend fun flushChanges(changes: ProjectionChanges) {
        if (changes.docRows.isNotEmpty()) documentDao.upsertAll(changes.docRows)
        if (changes.tags.isNotEmpty()) tagDao.setForDocuments(changes.tags)
        if (changes.fields.isNotEmpty()) fieldDao.replaceForDocs(changes.fields)
        if (changes.ftsRows.isNotEmpty()) ftsDao.upsertMetadata(changes.ftsRows)
    }

    /**
     * Upsert all tags referenced anywhere (with styles when set). Diffed against the current rows
     * so an unchanged fold does not touch the table: every write here invalidates the `tags` Room
     * flows (tag management screen, tag colors) even when nothing changed.
     */
    private suspend fun upsertReferencedTags(
        state: FoldedState,
        journalDocs: Set<String>,
        styledTags: Set<String>,
    ) {
        val allTags = LinkedHashSet<String>()
        for (doc in journalDocs) allTags.addAll(state.effectiveTags(doc))
        allTags.addAll(styledTags)
        val existingTagRows = tagDao.all().associateBy { it.name }
        val changed =
            allTags.mapNotNull { tag ->
                val style = state.tagStyle(tag)
                TagEntity(name = tag, color = style?.color, icon = style?.icon).takeIf { existingTagRows[tag] != it }
            }
        if (changed.isNotEmpty()) tagDao.upsertAll(changed)
    }

    /**
     * Per resolved relink target, the older ids among [journalDocs] that relinked onto it, so their
     * metadata carries over. Those older documents are superseded: [FoldPass.lifecycle] keeps them
     * deleted.
     */
    private fun predecessors(
        state: FoldedState,
        journalDocs: Set<String>,
    ): Map<String, List<String>> {
        val predecessors = HashMap<String, MutableList<String>>() // target -> old docs resolving here
        for (doc in journalDocs) {
            val resolved = state.resolveDocId(doc)
            if (resolved != doc) predecessors.getOrPut(resolved) { ArrayList() }.add(doc)
        }
        return predecessors
    }

    /** The trash/delete lifecycle for a doc, after applying the file-resurrection preference. */
    private class Lifecycle(
        val trashed: Boolean,
        val deleted: Boolean,
        val missing: Boolean,
        val relPath: String,
        val fileName: String,
        val origPath: String?,
    )

    /**
     * One [applyFold] pass over its documents: what it projects them from (the folded [state], the
     * [preloaded] rows, the relink [predecessors]) and where it leaves what it projected: [changes]
     * for the database, [work] for the path work after the transaction, [resurrected] for the caller.
     */
    private inner class FoldPass(
        private val state: FoldedState,
        private val scope: FoldScope,
        private val predecessors: Map<String, List<String>>,
        private val preloaded: PreloadedRows,
        private val changes: ProjectionChanges,
        private val work: DocumentPathReconciler.PathWork,
    ) {
        val resurrected = ArrayList<Resurrected>()

        /** Which document's row holds each path, as the pass found them; a resurrection checks it. */
        private val holders by lazy { preloaded.rows.values.associate { it.relPath to it.docId } }

        /**
         * Project one doc's tags, fields, props, lifecycle, row and FTS from the folded state. The
         * row goes to [work] as written, for the path work after the transaction: the fold itself
         * never moves a file.
         */
        fun project(
            doc: String,
            row: DocumentEntity,
        ) {
            // Predecessors (older ids that relinked onto this doc): their metadata carries over.
            val preds = predecessors[doc].orEmpty()

            val effTags = tags(doc, preds)
            val fields = fields(doc, preds)
            val lifecycle = lifecycle(doc, row)

            // Diffed: skip the upsert when the effective row equals what is stored, so a fold
            // triggered by one edit does not rewrite (and invalidate) every other doc's row.
            val projectedRow =
                row.copy(
                    relPath = lifecycle.relPath,
                    fileName = lifecycle.fileName,
                    reviewed = inheritedProp(doc, preds, SystemProperty.REVIEWED) == "true",
                    // Read cache of the well-known field rows; see DocumentEntity.correspondent.
                    correspondent = fields[FieldKeys.CORRESPONDENT],
                    docType = fields[FieldKeys.DOC_TYPE],
                    docDate = fields[FieldKeys.DOC_DATE],
                    title = fields[FieldKeys.TITLE],
                    notes = fields[FieldKeys.NOTES],
                    ocrEngineOverride = inheritedProp(doc, preds, SystemProperty.OCR_ENGINE),
                    trashed = lifecycle.trashed,
                    trashedOrigPath = if (lifecycle.trashed) lifecycle.origPath else null,
                    trashedAt = if (lifecycle.trashed) state.trashedAtMillis(doc) ?: row.trashedAt else null,
                    deleted = lifecycle.deleted,
                    missing = lifecycle.missing,
                )
            val rowChanged = projectedRow != row
            if (rowChanged) changes.docRows.add(projectedRow)
            work.rows[doc] = projectedRow

            // FTS: the fold writes only the metadata-derived columns; the extracted text is the OCR
            // pipeline's (FtsDao.upsertMetadata leaves it alone). A brand-new doc has no fts row
            // yet, so it must be written even when nothing else changed; the metadata columns derive
            // from the doc row, tags and fields, so when none changed and a row exists there is
            // nothing to write.
            val ftsRow = FtsRows.row(doc, projectedRow.fileName, effTags, fields, content = null)
            val searchableChanged = rowChanged || doc in changes.tags || doc in changes.fields
            if (searchableChanged || doc !in preloaded.ftsIndexed) changes.ftsRows.add(ftsRow)
            if (documentPaths.pending(state, projectedRow)) work.searchable[doc] = ftsRow
        }

        /** A system property's effective value for a doc: its own, else a predecessor's. */
        private fun inheritedProp(
            doc: String,
            preds: List<String>,
            key: SystemProperty,
        ): String? = state.effectiveProp(doc, key) ?: preds.firstNotNullOfOrNull { state.effectiveProp(it, key) }

        /**
         * Effective tags for a doc plus every predecessor's effective tags, sorted. Diffed against
         * the preloaded rows: a rewrite goes to [changes] only when the set actually changed, so an
         * unrelated edit does not invalidate `document_tags` observers for every doc.
         */
        private fun tags(
            doc: String,
            preds: List<String>,
        ): List<String> {
            val tagSet = LinkedHashSet<String>()
            tagSet.addAll(state.effectiveTags(doc))
            for (p in preds) tagSet.addAll(state.effectiveTags(p))
            val effTags = tagSet.toList().sorted()
            if (preloaded.tags[doc].orEmpty() != effTags) changes.tags[doc] = effTags
            return effTags
        }

        /**
         * Every field value for a doc: own value wins; inherit predecessor keys this doc doesn't set.
         * Rows carry the numeric shadow for the registry's NUMBER/DECIMAL fields. Diffed like tags so
         * an unrelated edit does not rewrite this doc's field rows. Returns the effective map, which
         * also feeds the cached well-known columns and the FTS row.
         */
        private fun fields(
            doc: String,
            preds: List<String>,
        ): Map<String, String> {
            val effFields = LinkedHashMap<String, String>()
            for (p in preds) effFields.putAll(state.effectiveFields(p))
            effFields.putAll(state.effectiveFields(doc)) // own values override inherited ones
            val rows =
                effFields.map { (key, value) ->
                    val numValue = FieldValues.numeric(state.effectiveFieldType(key), value)
                    DocumentFieldEntity(doc, key, value, numValue = numValue)
                }
            if (preloaded.fields[doc].orEmpty().sortedBy { it.fieldKey } != rows.sortedBy { it.fieldKey }) {
                changes.fields[doc] = rows
            }
            return effFields
        }

        /**
         * Resolve the doc's trash/delete state. On a [FoldScope.FullRescan] the file is preferred:
         * if the journal says trashed/deleted but the document's own bytes are back ([returnedPath]),
         * the flags are cleared and a [Resurrected] recorded. Docs an [FoldScope.Events] fold
         * preserves keep their stored lifecycle.
         */
        private fun lifecycle(
            doc: String,
            row: DocumentEntity,
        ): Lifecycle {
            // Relinked onto another id: deleted for good, on every fold, whatever its own events say.
            val superseded = state.resolveDocId(doc) != doc
            val journalDeleted = state.isDeleted(doc) || superseded
            val journalTrashed = state.isTrashed(doc)
            val origPath = state.trashOrigPath(doc)
            val preserved = scope is FoldScope.Events && doc in scope.preserveLifecycleDocs

            var trashed = if (preserved) row.trashed else journalTrashed
            var deleted = if (preserved) row.deleted || superseded else journalDeleted
            var missing = row.missing
            var relPath = row.relPath
            var fileName = row.fileName

            val hidden = journalTrashed || journalDeleted
            val backAt =
                (scope as? FoldScope.FullRescan)
                    ?.takeIf { hidden && !superseded }
                    ?.let { returnedPath(doc, row, origPath, it.contentAt) }
            if (backAt != null) {
                // Resurrection: prefer the file. Clear trashed/deleted for the working view.
                trashed = false
                deleted = false
                missing = false
                if (backAt != relPath) {
                    relPath = backAt
                    fileName = NpnPaths.fileName(backAt)
                }
                val hiddenAt = state.hiddenBy(doc)?.physicalMillis ?: 0L
                resurrected.add(Resurrected(doc, relPath, wasDeleted = journalDeleted, hiddenAtMillis = hiddenAt))
            }
            return Lifecycle(trashed, deleted, missing, relPath, fileName, origPath)
        }

        /**
         * Where a hidden [doc]'s own bytes are back in the library: at its row's path, else at
         * [origPath], the path it was trashed from, when no other row holds that. Only a file whose
         * hash the rescan knows ([contentAt]) is evidence: another document's file at the path, or
         * one still being written, says nothing about this one.
         */
        private fun returnedPath(
            doc: String,
            row: DocumentEntity,
            origPath: String?,
            contentAt: Map<String, String>,
        ): String? =
            row.relPath.takeIf { contentAt[it] == doc }
                ?: origPath?.takeIf { contentAt[it] == doc && it !in holders }
    }

    private companion object {
        const val HASH_CONCURRENCY = 4
    }
}

/**
 * Whether the file [this] describes still has the [size] and [mtime] a document's row stored: the
 * evidence a rescan and an import (`ImportService`) take that the bytes there are still that
 * document's without hashing them, for a document that is not hidden. A hidden one gave its path
 * up, so a file there is hashed.
 */
internal fun FileMeta.hasStoredStat(
    size: Long,
    mtime: Long,
): Boolean = this.size == size && mtimeMillis == mtime

/**
 * Whether the file at [row]'s path holds its document's bytes, [size] of them, by the index's
 * standard of proof: a live row's file that still has the size and mtime the row stored holds them
 * ([hasStoredStat]), and any other file of that size is hashed. Moving or keeping a document's file
 * goes by this, so an unchanged file costs a stat and a file replaced since the last rescan is never
 * taken for the document.
 */
internal suspend fun LibraryFileSystem.holdsDocument(
    row: DocumentEntity,
    size: Long = row.size,
): Boolean {
    val meta = stat(row.relPath)?.takeIf { !it.isDirectory && it.size == size } ?: return false
    return (row.isLive && meta.hasStoredStat(row.size, row.mtime)) || sha256(row.relPath) == row.docId
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
