package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.PathMeta
import dev.npn.core.db.TagEntity
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
import dev.npn.core.model.FieldType
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
 *  5. handles trash resurrection (reported through `onResurrected` once the fold has committed),
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
    private val documentPaths = DocumentPathReconciler(fs, db)
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

    @Suppress("LongMethod")
    suspend fun rescan(
        metadataState: FoldedState,
        fullHash: Boolean = false,
        onProgress: (IndexProgress) -> Unit = {},
        onExternalMoves: (suspend (List<PendingOp>) -> Unit)? = null,
        onResurrected: suspend (List<Resurrected>) -> Unit = {},
    ): ScanReport {
        check(!documentPaths.collecting) { "A rescan does filesystem work, so it cannot run inside a projection" }
        val started = clock.nowMillis()

        fun report(p: IndexProgress) {
            _scanState.value = p
            onProgress(p)
        }

        report(IndexProgress(IndexProgress.Phase.LISTING))
        return try {
            // Indexable walk: `.npn/` (journal, OCR sidecars, trash) is never indexed, so skip
            // it instead of traversing the whole metadata tree on every rescan.
            val all = fs.listAllIndexable()

            val (indexable, conflictFiles) = partition(all)

            val dbProjection = documentDao.allPathsAndMeta()
            val byPath = dbProjection.associateBy { it.relPath }
            val changed =
                indexable.filter { meta ->
                    val known = byPath[meta.relPath]
                    fullHash || known == null || known.size != meta.size || known.mtime != meta.mtimeMillis
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

            report(IndexProgress(IndexProgress.Phase.HASHING, hashed = 0, total = toHash.size))
            val hashes = HashMap<String, String>() // relPath -> sha256
            val progressMutex = Mutex()
            var hashedCount = 0
            // A fixed pool of workers pulling from a shared queue. Chunking the input instead
            // would size the CHUNKS at HASH_CONCURRENCY and launch one coroutine per chunk —
            // a thousand files to hash meant 250 coroutines all hopping to Dispatchers.IO,
            // which is unbounded read concurrency against the storage provider rather than
            // the intended cap. A queue also balances the work, which matters because file
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
                                report(
                                    IndexProgress(
                                        IndexProgress.Phase.HASHING,
                                        hashed = hashedCount,
                                        total = toHash.size,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            report(IndexProgress(IndexProgress.Phase.RECONCILING))
            if (onExternalMoves != null) {
                val moves =
                    ExternalDocumentMoves(
                        fs,
                        db.keyValueDao(),
                    ).detect(metadataState, dbProjection, indexable, hashes)
                if (moves.isNotEmpty()) onExternalMoves(moves)
            }
            val diskPaths = indexable.map { it.relPath }.toHashSet()

            // The reconcile writes and the fold land in one transaction, so no reader sees a row
            // before its tags and fields; the files their intents move wait until it commits.
            val recon =
                documentPaths.deferring(known = knownHashes(indexable, changed, byPath, hashes)) {
                    db.withWriteTransaction {
                        reconcile(toHash, hashes, dbProjection, diskPaths).also { recon ->
                            val missing = detectMissing(dbProjection, diskPaths, recon.existingByDocId)
                            recon.missingCount = missing.size
                            recon.relinkCandidates.addAll(
                                RelinkPairing.pair(missing, recon.newDocs, recon.relinkCandidates),
                            )

                            report(IndexProgress(IndexProgress.Phase.FOLDING))
                            recon.resurrected = applyFold(metadataState, FoldScope.FullRescan(diskPaths))
                        }
                    }
                }
            if (recon.resurrected.isNotEmpty()) onResurrected(recon.resurrected)

            if (recon.duplicates.isNotEmpty()) {
                val duplicateDocs = recon.duplicates.mapTo(LinkedHashSet()) { it.docId }
                documentPaths.reconcileDuplicates(metadataState, recon.duplicates)
                duplicateDao.replaceForDocs(
                    duplicateDocs,
                    recon.duplicates.map { DuplicateEntity(it.docId, it.extraRelPath) },
                )
            }

            // The listing is at hand: clear away what a crash left of the app's own temporary files.
            tempFiles.sweep(all, started)

            report(IndexProgress(IndexProgress.Phase.DONE))

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
            if (!meta.isDirectory && !NpnPaths.isInternal(meta.relPath) && NpnPaths.isSyncConflict(meta.name)) {
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

        // Rows to write, in the order the pass decided them: relPath is unique, so a row that
        // vacates a path must be written before the one that takes it.
        val writes = ArrayList<DocumentEntity>()
    }

    /**
     * Reconcile hashed files against the DB. For each hashed file, one of: an unchanged/refreshed
     * canonical, a move/rename, an external duplicate, an external edit (old -> missing sentinel +
     * new doc + path-match relink candidate), or a brand-new doc. Writes the rows it changed in one
     * batch and returns the counters + new docs + duplicates + relink candidates collected.
     */
    private suspend fun reconcile(
        toHash: List<FileMeta>,
        hashes: Map<String, String>,
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
    ): Reconciliation {
        val recon = Reconciliation()
        val existingByPath = HashMap<String, DocumentEntity>()
        // One bulk read (chunked under SQLite's bind limit) rather than a query per document:
        // this runs over the whole projection on every rescan, so a per-row read here was an
        // N+1 across the entire library.
        for (row in documentDao.getByDocIds(dbProjection.map { it.docId })) {
            recon.existingByDocId[row.docId] = row
            existingByPath[row.relPath] = row
        }
        val now = clock.nowMillis()

        for (meta in toHash) {
            val hash = hashes[meta.relPath] ?: continue
            val existingDoc = recon.existingByDocId[hash]

            if (existingDoc != null) {
                reconcileKnownHash(existingDoc, meta, hash, diskPaths, recon)
                continue
            }

            // Hash unknown. Does this path currently hold a different doc? (external edit)
            val docAtPath = existingByPath[meta.relPath]
            if (docAtPath != null && docAtPath.docId != hash) {
                // External edit: mark old missing (keep metadata), create new doc here. The old
                // doc must vacate the path (relPath is unique) so the new doc can take it; move it
                // to a per-doc sentinel path under .npn (never indexed) that preserves identity.
                val missingDoc =
                    docAtPath.copy(relPath = DocumentPathReconciler.missingSentinel(docAtPath.docId), missing = true)
                recon.writes += missingDoc
                recon.existingByDocId[docAtPath.docId] = missingDoc
                existingByPath.remove(meta.relPath)
                recon.existingByDocId[hash] = newDocument(hash, meta, now).also { recon.writes += it }
                recon.newDocs[hash] = meta
                recon.added++
                recon.changed++
                recon.relinkCandidates.add(
                    RelinkCandidate(
                        docAtPath.docId,
                        hash,
                        reason = RelinkPairing.PATH_MATCH,
                        score = RelinkPairing.SCORE_PATH_MATCH,
                    ),
                )
            } else {
                recon.existingByDocId[hash] = newDocument(hash, meta, now).also { recon.writes += it }
                recon.newDocs[hash] = meta
                recon.added++
            }
        }
        if (recon.writes.isNotEmpty()) documentDao.upsertAll(recon.writes)
        return recon
    }

    /** Handle a hashed file whose content hash already maps to a known canonical doc. */
    private fun reconcileKnownHash(
        existingDoc: DocumentEntity,
        meta: FileMeta,
        hash: String,
        diskPaths: Set<String>,
        recon: Reconciliation,
    ) {
        val oldPath = existingDoc.relPath
        val canonicalStillOnDisk = oldPath in diskPaths && oldPath != meta.relPath
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
            recon.writes += movedDoc
            recon.existingByDocId[hash] = movedDoc
            recon.moved++
        } else if (existingDoc.missing || existingDoc.size != meta.size || existingDoc.mtime != meta.mtimeMillis) {
            // Same path, bytes unchanged but was flagged (e.g. mtime touched): refresh.
            val refreshedDoc = existingDoc.copy(size = meta.size, mtime = meta.mtimeMillis, missing = false)
            recon.writes += refreshedDoc
            recon.existingByDocId[hash] = refreshedDoc
        }
    }

    /**
     * Mark docs whose canonical path is no longer on disk as missing (skipping deleted/trashed), and
     * return every doc that is missing now.
     */
    private suspend fun detectMissing(
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
        existingByDocId: Map<String, DocumentEntity>,
    ): List<DocumentEntity> {
        val missingDocs = ArrayList<DocumentEntity>()
        val nowMissing = ArrayList<DocumentEntity>()
        for (pm in dbProjection) {
            // [Reconciliation.existingByDocId] is kept current for every row the reconcile pass
            // upserted, so it already holds the post-reconcile row. Re-reading each one from the
            // DB here was a second N+1 over the whole library for no extra information.
            val current = existingByDocId[pm.docId] ?: continue
            if (current.deleted || current.trashed) continue
            if (current.relPath !in diskPaths) {
                if (!current.missing) nowMissing.add(current.copy(missing = true))
                missingDocs.add(current)
            }
        }
        if (nowMissing.isNotEmpty()) documentDao.upsertAll(nowMissing)
        return missingDocs
    }

    /** Which documents one [applyFold] projects, and what else it takes into account. */
    private sealed interface FoldScope {
        /**
         * A rescan's fold: every document, preferring a file the rescan found at [diskPaths] over a
         * journal that hides its document (see [FoldPass.lifecycle]).
         */
        class FullRescan(
            val diskPaths: Set<String>,
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
     *   or deleted but the file exists at its original or current path, the file wins (flags and
     *   missing cleared) and the document is reported, so the caller can record a restore; until
     *   one is recorded, any other fold hides it again (see [FoldPass.lifecycle]).
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
        val preloaded = preloadRows(state, journalDocs)
        upsertReferencedTags(state, journalDocs, (scope as? FoldScope.Events)?.styledTags.orEmpty())
        // The registry is library-wide and small; a diffed replace per fold keeps it in step
        // with the fold at negligible cost and needs no per-op scoping.
        fieldDefDao.replaceAll(state.effectiveFieldDefs())
        val changes = ProjectionChanges()
        val supersession = resolveSupersessions(state, journalDocs, preloaded.rows, changes)
        val work = documentPaths.work()
        work.state = state

        val pass = FoldPass(state, scope, supersession, preloaded, changes, work)
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
     * the per-doc projection performs no serial reads. [rows] is kept mutable: supersession
     * flips the deleted flag in place so the projection and the final flush both see it.
     */
    private class PreloadedRows(
        val rows: HashMap<String, DocumentEntity>,
        val tags: Map<String, List<String>>,
        val fields: Map<String, List<DocumentFieldEntity>>,
        /** Registry types by key, for the numeric shadow on field rows. */
        val fieldTypes: Map<String, FieldType>,
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

    private suspend fun preloadRows(
        state: FoldedState,
        journalDocs: Set<String>,
    ): PreloadedRows {
        val docIds = journalDocs.toList()
        val rows = HashMap<String, DocumentEntity>(docIds.size)
        for (row in documentDao.getByDocIds(docIds)) rows[row.docId] = row

        val tags = HashMap<String, List<String>>(docIds.size)
        for ((docId, tagRows) in tagDao.tagsForDocs(docIds).groupBy { it.docId }) {
            tags[docId] = tagRows.map { it.tag }.sorted()
        }

        val fields = fieldDao.fieldsForDocs(docIds).groupBy { it.docId }
        val fieldTypes = state.effectiveFieldDefs().associate { it.key to it.type }

        val ftsIndexed = ftsDao.indexedDocIds(docIds).toHashSet()
        return PreloadedRows(rows, tags, fields, fieldTypes, ftsIndexed)
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
     * Result of the relink-supersession pass: which docs were marked deleted/superseded, and, per
     * resolved target, the older ids that relinked onto it (so their metadata carries over).
     */
    private class Supersession(
        val superseded: Set<String>,
        val predecessors: Map<String, List<String>>,
    )

    /**
     * Mark old docs deleted where a winning relink resolves to a different, known id, and record
     * predecessors so metadata carries onto the resolved target. Superseded rows are updated in
     * [rowsByDocId] and recorded in [changes] so the deletion flushes with the rest of the fold.
     */
    private fun resolveSupersessions(
        state: FoldedState,
        journalDocs: Set<String>,
        rowsByDocId: HashMap<String, DocumentEntity>,
        changes: ProjectionChanges,
    ): Supersession {
        val superseded = HashSet<String>()
        val predecessors = HashMap<String, MutableList<String>>() // target -> old docs resolving here
        for (doc in journalDocs) {
            val resolved = state.resolveDocId(doc)
            if (resolved != doc) {
                predecessors.getOrPut(resolved) { ArrayList() }.add(doc)
                val oldRow = rowsByDocId[doc]
                if (oldRow != null && !oldRow.deleted) {
                    val deletedRow = oldRow.copy(deleted = true)
                    rowsByDocId[doc] = deletedRow
                    changes.docRows.add(deletedRow)
                    superseded.add(doc)
                }
            }
        }
        return Supersession(superseded, predecessors)
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
     * [preloaded] rows, the [supersession]) and where it leaves what it projected: [changes] for the
     * database, [work] for the path work after the transaction, [resurrected] for the caller.
     */
    private inner class FoldPass(
        private val state: FoldedState,
        private val scope: FoldScope,
        private val supersession: Supersession,
        private val preloaded: PreloadedRows,
        private val changes: ProjectionChanges,
        private val work: DocumentPathReconciler.PathWork,
    ) {
        val resurrected = ArrayList<Resurrected>()

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
            val preds = supersession.predecessors[doc].orEmpty()

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
                    val type = preloaded.fieldTypes[key]
                    DocumentFieldEntity(doc, key, value, numValue = type?.let { FieldValues.numeric(it, value) })
                }
            if (preloaded.fields[doc].orEmpty().sortedBy { it.fieldKey } != rows.sortedBy { it.fieldKey }) {
                changes.fields[doc] = rows
            }
            return effFields
        }

        /**
         * Resolve the doc's trash/delete state. On a [FoldScope.FullRescan] the file is preferred:
         * if the journal says trashed/deleted but the file exists at the original (or current)
         * path, the flags are cleared and a [Resurrected] recorded. Docs an [FoldScope.Events] fold
         * preserves keep their stored lifecycle.
         */
        private fun lifecycle(
            doc: String,
            row: DocumentEntity,
        ): Lifecycle {
            val superseded = doc in supersession.superseded
            val journalDeleted = state.isDeleted(doc) || superseded
            val journalTrashed = state.isTrashed(doc)
            val origPath = state.trashOrigPath(doc)
            val preserved = scope is FoldScope.Events && doc in scope.preserveLifecycleDocs

            var trashed = if (preserved) row.trashed else journalTrashed
            var deleted = if (preserved) row.deleted else journalDeleted
            var missing = row.missing
            var relPath = row.relPath
            var fileName = row.fileName

            val diskPaths = (scope as? FoldScope.FullRescan)?.diskPaths
            val origOnDisk = origPath?.takeIf { diskPaths != null && it in diskPaths }
            val tombstonedFileExists =
                (journalTrashed || journalDeleted) && (origOnDisk != null || diskPaths?.contains(relPath) == true)

            if (tombstonedFileExists && !superseded) {
                // Resurrection: prefer the file. Clear trashed/deleted for the working view.
                trashed = false
                deleted = false
                missing = false
                if (origOnDisk != null) {
                    relPath = origOnDisk
                    fileName = NpnPaths.fileName(origOnDisk)
                }
                val hiddenAt = state.hiddenBy(doc)?.physicalMillis ?: 0L
                resurrected.add(Resurrected(doc, relPath, wasDeleted = journalDeleted, hiddenAtMillis = hiddenAt))
            }
            return Lifecycle(trashed, deleted, missing, relPath, fileName, origPath)
        }
    }

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
