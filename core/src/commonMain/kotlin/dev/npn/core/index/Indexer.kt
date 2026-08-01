package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.PathMeta
import dev.npn.core.db.TagEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.DocProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 *  5. handles trash resurrection,
 *  6. folds the journals and applies effective views (tags, props, trash, deletes, relinks) into the
 *     DB, then updates FTS,
 *  7. returns a [ScanReport].
 *
 * Only one rescan runs at a time (guarded by a [Mutex]); calling [rescan] repeatedly is safe.
 */
class Indexer(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val clock: EpochClock = SystemEpochClock,
    private val ftsWriteGate: FtsWriteGate = FtsWriteGate(),
) {
    private val rescanMutex = Mutex()
    private val _scanState = MutableStateFlow<IndexProgress?>(null)

    /** Lightweight progress surface; null when idle. */
    val scanState: StateFlow<IndexProgress?> get() = _scanState.asStateFlow()

    private val documentDao get() = db.documentDao()
    private val tagDao get() = db.tagDao()
    private val ftsDao get() = db.ftsDao()
    private val fieldDao get() = db.documentFieldDao()
    private val duplicateDao get() = db.duplicateDao()

    suspend fun rescan(
        metadataState: FoldedState,
        fullHash: Boolean = false,
        onProgress: (IndexProgress) -> Unit = {},
    ): ScanReport =
        rescanMutex.withLock {
            val started = clock.nowMillis()

            fun report(p: IndexProgress) {
                _scanState.value = p
                onProgress(p)
            }

            report(IndexProgress(IndexProgress.Phase.LISTING))
            try {
                val all = fs.listAll()

                val (indexable, conflictFiles) = partition(all)

                val dbProjection = documentDao.allPathsAndMeta()
                val byPath = dbProjection.associateBy { it.relPath }
                val toHash =
                    indexable.filter { meta ->
                        val known = byPath[meta.relPath]
                        fullHash || known == null || known.size != meta.size || known.mtime != meta.mtimeMillis
                    }

                report(IndexProgress(IndexProgress.Phase.HASHING, hashed = 0, total = toHash.size))
                val hashes = HashMap<String, String>() // relPath -> sha256
                var hashedCount = 0
                for (meta in toHash) {
                    hashes[meta.relPath] = fs.sha256(meta.relPath)
                    hashedCount++
                    report(IndexProgress(IndexProgress.Phase.HASHING, hashed = hashedCount, total = toHash.size))
                }

                report(IndexProgress(IndexProgress.Phase.RECONCILING))
                val diskPaths = indexable.map { it.relPath }.toHashSet()

                val recon = reconcile(toHash, hashes, dbProjection, diskPaths)

                val missing = detectMissing(dbProjection, diskPaths, recon.existingByDocId)
                recon.relinkCandidates.addAll(pairRelinks(missing.missingDocs, recon.newDocs, recon.relinkCandidates))

                report(IndexProgress(IndexProgress.Phase.FOLDING))
                val resurrected =
                    applyFold(
                        metadataState,
                        FoldRequest(diskPaths = diskPaths, reconcileFileState = true),
                    )

                val dupByDoc = recon.duplicates.groupBy { it.docId }
                for ((docId, entries) in dupByDoc) {
                    duplicateDao.replaceAll(docId, entries.map { DuplicateEntity(docId, it.extraRelPath) })
                }

                report(IndexProgress(IndexProgress.Phase.DONE))

                ScanReport(
                    added = recon.added,
                    moved = recon.moved,
                    changed = recon.changed,
                    missingCount = missing.missingCount,
                    duplicates = recon.duplicates,
                    conflictFiles = conflictFiles,
                    resurrected = resurrected,
                    relinkCandidates = recon.relinkCandidates,
                    hashedCount = hashedCount,
                    durationMillis = clock.nowMillis() - started,
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

    /** Mutable accumulator for the reconcile pass, plus the doc rows it touched. */
    private class Reconciliation {
        var added = 0
        var moved = 0
        var changed = 0
        val duplicates = ArrayList<DuplicateReport>()
        val relinkCandidates = ArrayList<RelinkCandidate>()

        // New docs created this scan: docId -> meta, for relink pairing.
        val newDocs = HashMap<String, FileMeta>()

        // Canonical rows we loaded/updated, kept current so later steps re-read consistently.
        val existingByDocId = HashMap<String, DocumentEntity>()
    }

    /**
     * Reconcile hashed files against the DB. For each hashed file, one of: an unchanged/refreshed
     * canonical, a move/rename, an external duplicate, an external edit (old -> missing sentinel +
     * new doc + path-match relink candidate), or a brand-new doc. Mutates the DB and returns the
     * counters + new docs + duplicates + relink candidates collected.
     */
    private suspend fun reconcile(
        toHash: List<FileMeta>,
        hashes: Map<String, String>,
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
    ): Reconciliation {
        val recon = Reconciliation()
        val existingByPath = HashMap<String, DocumentEntity>()
        for (pm in dbProjection) {
            val row = documentDao.getByDocId(pm.docId) ?: continue
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
                val missingDoc = docAtPath.copy(relPath = missingSentinel(docAtPath.docId), missing = true)
                documentDao.upsert(missingDoc)
                recon.existingByDocId[docAtPath.docId] = missingDoc
                existingByPath.remove(meta.relPath)
                insertNewDocument(hash, meta, now)
                recon.newDocs[hash] = meta
                recon.added++
                recon.changed++
                recon.relinkCandidates.add(
                    RelinkCandidate(docAtPath.docId, hash, reason = "path-match", score = RELINK_SCORE_PATH_MATCH),
                )
            } else {
                insertNewDocument(hash, meta, now)
                recon.newDocs[hash] = meta
                recon.added++
            }
        }
        return recon
    }

    /** Handle a hashed file whose content hash already maps to a known canonical doc. */
    private suspend fun reconcileKnownHash(
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
            documentDao.upsert(movedDoc)
            recon.existingByDocId[hash] = movedDoc
            recon.moved++
        } else if (existingDoc.missing || existingDoc.size != meta.size || existingDoc.mtime != meta.mtimeMillis) {
            // Same path, bytes unchanged but was flagged (e.g. mtime touched): refresh.
            val refreshedDoc = existingDoc.copy(size = meta.size, mtime = meta.mtimeMillis, missing = false)
            documentDao.upsert(refreshedDoc)
            recon.existingByDocId[hash] = refreshedDoc
        }
    }

    /** Result of the disappeared-files pass. */
    private class MissingResult(
        val missingCount: Int,
        val missingDocs: List<DocumentEntity>,
    )

    /** Mark docs whose canonical path is no longer on disk as missing (skipping deleted/trashed). */
    private suspend fun detectMissing(
        dbProjection: List<PathMeta>,
        diskPaths: Set<String>,
        existingByDocId: Map<String, DocumentEntity>,
    ): MissingResult {
        var missingCount = 0
        val missingDocs = ArrayList<DocumentEntity>()
        for (pm in dbProjection) {
            existingByDocId[pm.docId] ?: continue
            // Re-read: it may have been updated (moved) above.
            val current = documentDao.getByDocId(pm.docId) ?: continue
            if (current.deleted || current.trashed) continue
            if (current.relPath !in diskPaths) {
                if (!current.missing) {
                    documentDao.upsert(current.copy(missing = true))
                }
                missingDocs.add(current)
                missingCount++
            }
        }
        return MissingResult(missingCount, missingDocs)
    }

    /** Pair each missing doc against brand-new docs to produce relink candidates. */
    private fun pairRelinks(
        missingDocs: List<DocumentEntity>,
        newDocs: Map<String, FileMeta>,
        existing: List<RelinkCandidate>,
    ): List<RelinkCandidate> {
        val out = ArrayList<RelinkCandidate>()
        for (missing in missingDocs) {
            for ((newDocId, newMeta) in newDocs) {
                if (existing.any { it.oldDocId == missing.docId && it.newDocId == newDocId }) continue
                if (out.any { it.oldDocId == missing.docId && it.newDocId == newDocId }) continue
                val score = scoreRelink(missing, newMeta)
                if (score.first > 0) {
                    out.add(RelinkCandidate(missing.docId, newDocId, score.second, score.first))
                }
            }
        }
        return out
    }

    /**
     * The non-state inputs of one [applyFold] pass, grouped so the three fold modes are explicit at
     * a glance instead of threading 6 positional/subset args through two functions:
     *  - full rescan: [reconcileFileState] = true + populated [diskPaths] (resurrection preference);
     *  - event refresh: a bounded [documentScope] (+ [styledTags]) folded with [ftsGateHeld] held;
     *  - state refresh: whole-DB reproject (null [documentScope]) with [preserveLifecycleDocs] kept.
     *
     * Defaults describe the plain fold-only mode; each entry point overrides only what differs.
     */
    private class FoldRequest(
        val diskPaths: Set<String> = emptySet(),
        val reconcileFileState: Boolean = false,
        val documentScope: Set<String>? = null,
        val styledTags: Set<String> = emptySet(),
        val ftsGateHeld: Boolean = false,
        val preserveLifecycleDocs: Set<String> = emptySet(),
    )

    /** Apply already-reduced events without reading journal files or walking the PDF library. */
    suspend fun refreshEvents(
        state: FoldedState,
        events: List<JournalEvent>,
        ftsGateHeld: Boolean = false,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ): List<Resurrected> =
        rescanMutex.withLock {
            if (events.isEmpty()) return@withLock emptyList()
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

                        else -> {
                            Unit
                        }
                    }
                }
                documents.toList().forEach { documents += state.resolveDocId(it) }
                applyFold(
                    state,
                    FoldRequest(
                        documentScope = documents,
                        styledTags = styledTags,
                        ftsGateHeld = ftsGateHeld,
                        preserveLifecycleDocs = preserveLifecycleDocs,
                    ),
                )
            } finally {
                _scanState.value = null
            }
        }

    /** Reproject a reconciled mixed-version accumulator without rereading journal sources. */
    suspend fun refreshState(
        state: FoldedState,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ): List<Resurrected> =
        rescanMutex.withLock {
            _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
            try {
                applyFold(state, FoldRequest(preserveLifecycleDocs = preserveLifecycleDocs))
            } finally {
                _scanState.value = null
            }
        }

    suspend fun <T> withFtsWriteGate(block: suspend () -> T): T = ftsWriteGate.withGate(block)

    /**
     * A synthetic, unique relPath for a doc whose file was replaced in place (external edit). It
     * lives under `.npn/` so it is never indexed, and encodes the docId to satisfy the unique
     * relPath constraint while the doc waits for a relink.
     */
    private fun missingSentinel(docId: String): String = "${NpnPaths.NPN_DIR}/missing/$docId"

    private suspend fun insertNewDocument(
        hash: String,
        meta: FileMeta,
        now: Long,
    ) {
        documentDao.upsert(
            DocumentEntity(
                docId = hash,
                relPath = meta.relPath,
                fileName = meta.name,
                size = meta.size,
                mtime = meta.mtimeMillis,
                addedAt = now,
                // reviewed defaults to false (new doc = needs review).
            ),
        )
    }

    /** Returns (score, reason). score 0 means no candidate. */
    private fun scoreRelink(
        missing: DocumentEntity,
        newMeta: FileMeta,
    ): Pair<Int, String> {
        var score = 0
        val reasons = ArrayList<String>()
        if (missing.fileName.equals(newMeta.name, ignoreCase = true)) {
            score += RELINK_SCORE_NAME
            reasons.add("name")
        }
        if (missing.size == newMeta.size) {
            score += RELINK_SCORE_SIZE
            reasons.add("size")
        }
        if (NameSimilarity.parentDir(missing.relPath) == NameSimilarity.parentDir(newMeta.relPath)) {
            score += RELINK_SCORE_DIR
            reasons.add("dir")
        }
        if (score == 0) {
            if (NameSimilarity.prefixMatch(missing.fileName, newMeta.name) ||
                NameSimilarity.fuzzyMatch(missing.fileName, newMeta.name)
            ) {
                score += RELINK_SCORE_FUZZY
                reasons.add("fuzzy-name")
            }
        }
        return score to reasons.joinToString("+")
    }

    /**
     * Apply the folded effective views into Room in one pass and return resurrected docs.
     *
     * Design notes (step 6):
     * - Tags: every tag with a known style gets a [TagEntity] upsert; every doc's document_tags is
     *   replaced with its effective tag set. Tags appearing only in adds (no explicit style) are
     *   upserted with null style so tag-management counts see them.
     * - Props: reviewed/correspondent/docType/docDate/title columns are set from effective props;
     *   absent -> defaults (reviewed false, others null).
     * - Trash: the trashed flag + trashedOrigPath come from the fold's trash state. Resurrection:
     *   if the journal says trashed/deleted but the file exists at the original path, prefer the
     *   file (clear missing, do NOT set trashed) and report it. This keeps the file authoritative.
     * - Delete: a delete tombstone sets `deleted = true`. File cleanup is a separate user action;
     *   a deleted doc whose file still exists keeps the flag (documented divergence: the tombstone
     *   is authoritative for identity, the leftover file is surfaced for manual cleanup elsewhere).
     * - Relinks: fold resolves old->new; the effective views are already keyed on ids. For a folded
     *   relink where both ids are known, the OLD doc is marked deleted/superseded and the NEW doc
     *   carries the metadata (fold exposes effective views on the resolved id).
     * - FTS: after tags/props, each affected doc's fts row is replaced. The fold has no content
     *   source of its own, so it reads the existing extracted-text content from the current fts
     *   row and carries it forward, only updating the metadata-derived columns (fileName, title,
     *   tags, correspondent, docType). This preserves OCR/embedded text (written by the OCR
     *   pipeline) across metadata-only folds and rescans. A brand-new doc with no fts row yet
     *   gets empty content, which the OCR pipeline fills later via its own path.
     */
    private suspend fun applyFold(
        state: FoldedState,
        request: FoldRequest,
    ): List<Resurrected> {
        // Every doc the fold must project: those the journal has data about, plus (for a full
        // fold) every doc currently in the DB so stale trash/delete flags get cleared.
        val journalDocs = LinkedHashSet<String>()
        journalDocs.addAll(request.documentScope ?: state.docsWithMetadata())
        if (request.documentScope == null) {
            for (pm in documentDao.allPathsAndMeta()) journalDocs.add(pm.docId)
        }
        upsertReferencedTags(state, journalDocs, request.styledTags)
        val supersession = resolveSupersessions(state, journalDocs)

        val resurrected = ArrayList<Resurrected>()
        for (doc in journalDocs) {
            val row = documentDao.getByDocId(doc) ?: continue
            projectDocument(
                doc = doc,
                row = row,
                state = state,
                supersession = supersession,
                request = request,
                resurrected = resurrected,
            )
        }
        return resurrected
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
        for (tag in allTags) {
            val style = state.tagStyle(tag)
            val entity = TagEntity(name = tag, color = style?.color, icon = style?.icon)
            if (existingTagRows[tag] != entity) tagDao.upsert(entity)
        }
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
     * predecessors so metadata carries onto the resolved target.
     */
    private suspend fun resolveSupersessions(
        state: FoldedState,
        journalDocs: Set<String>,
    ): Supersession {
        val superseded = HashSet<String>()
        val predecessors = HashMap<String, MutableList<String>>() // target -> old docs resolving here
        for (doc in journalDocs) {
            val resolved = state.resolveDocId(doc)
            if (resolved != doc) {
                predecessors.getOrPut(resolved) { ArrayList() }.add(doc)
                val oldRow = documentDao.getByDocId(doc)
                if (oldRow != null && !oldRow.deleted) {
                    documentDao.upsert(oldRow.copy(deleted = true))
                    superseded.add(doc)
                }
            }
        }
        return Supersession(superseded, predecessors)
    }

    /** Effective metadata columns for a doc: own value wins, else inherit from a predecessor. */
    private class ProjectedMeta(
        val reviewed: Boolean,
        val correspondent: String?,
        val docType: String?,
        val docDate: String?,
        val title: String?,
        val notes: String?,
        val ocrEngineOverride: String?,
    )

    private fun projectMeta(
        state: FoldedState,
        doc: String,
        preds: List<String>,
    ): ProjectedMeta {
        fun prop(key: DocProperty): String? {
            state.effectiveProp(doc, key)?.let { return it }
            for (p in preds) state.effectiveProp(p, key)?.let { return it }
            return null
        }
        return ProjectedMeta(
            reviewed = prop(DocProperty.REVIEWED) == "true",
            correspondent = prop(DocProperty.CORRESPONDENT),
            docType = prop(DocProperty.DOC_TYPE),
            docDate = prop(DocProperty.DOC_DATE),
            title = prop(DocProperty.TITLE),
            notes = prop(DocProperty.NOTES),
            ocrEngineOverride = prop(DocProperty.OCR_ENGINE),
        )
    }

    /**
     * Effective tags for a doc plus every predecessor's effective tags. Diffed: only rewrite the
     * doc's tag rows when the set actually changed, so an unrelated edit does not invalidate
     * `document_tags` observers for every doc. Returns the effective (sorted) tags and whether the
     * stored rows changed.
     */
    private suspend fun projectTags(
        state: FoldedState,
        doc: String,
        preds: List<String>,
    ): Pair<List<String>, Boolean> {
        val tagSet = LinkedHashSet<String>()
        tagSet.addAll(state.effectiveTags(doc))
        for (p in preds) tagSet.addAll(state.effectiveTags(p))
        val effTags = tagSet.toList().sorted()
        val tagsChanged = tagDao.tagsForDocument(doc) != effTags
        if (tagsChanged) tagDao.setForDocument(doc, effTags)
        return effTags to tagsChanged
    }

    /**
     * Custom fields: own value wins; inherit predecessor keys this doc doesn't set. Diffed like
     * tags so an unrelated edit does not rewrite this doc's field rows.
     */
    private suspend fun projectFields(
        state: FoldedState,
        doc: String,
        preds: List<String>,
    ) {
        val effFields = LinkedHashMap<String, String>()
        for (p in preds) effFields.putAll(state.effectiveFields(p))
        effFields.putAll(state.effectiveFields(doc)) // own values override inherited ones
        val curFields = fieldDao.fieldsForDocs(listOf(doc)).associate { it.fieldKey to it.fieldValue }
        if (curFields != effFields) {
            fieldDao.deleteForDoc(doc)
            if (effFields.isNotEmpty()) {
                fieldDao.upsertAll(effFields.map { (k, v) -> DocumentFieldEntity(doc, k, v) })
            }
        }
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
     * Resolve the doc's trash/delete state with resurrection preference for the file: if the
     * journal says trashed/deleted but the file exists at the original (or current) path, prefer
     * the file, clear the flags, and record a [Resurrected]. Docs in [preserveLifecycleDocs] keep
     * their stored lifecycle regardless.
     */
    private fun projectLifecycle(
        state: FoldedState,
        doc: String,
        row: DocumentEntity,
        supersession: Supersession,
        diskPaths: Set<String>,
        reconcileFileState: Boolean,
        preserveLifecycleDocs: Set<String>,
        resurrected: MutableList<Resurrected>,
    ): Lifecycle {
        val journalDeleted = state.isDeleted(doc) || doc in supersession.superseded
        val journalTrashed = state.isTrashed(doc) && !state.isDeleted(doc)
        val origPath = state.trashOrigPath(doc)

        var trashed = if (doc in preserveLifecycleDocs) row.trashed else journalTrashed
        var deleted = if (doc in preserveLifecycleDocs) row.deleted else journalDeleted
        var missing = row.missing
        var relPath = row.relPath
        var fileName = row.fileName

        val origOnDisk = origPath?.takeIf { it in diskPaths }
        val fileAtCurrent = relPath in diskPaths
        val tombstonedFileExists =
            (journalTrashed || journalDeleted) && (origOnDisk != null || fileAtCurrent)

        if (reconcileFileState && tombstonedFileExists && doc !in supersession.superseded) {
            // Resurrection: prefer the file. Clear trashed/deleted for the working view.
            trashed = false
            deleted = false
            missing = false
            if (origOnDisk != null) {
                relPath = origOnDisk
                fileName = origOnDisk.substringAfterLast('/')
            }
            resurrected.add(Resurrected(doc, relPath, wasDeleted = journalDeleted))
        }
        return Lifecycle(trashed, deleted, missing, relPath, fileName, origPath)
    }

    /** Project one doc's tags, fields, props, lifecycle, row and FTS from the folded state. */
    private suspend fun projectDocument(
        doc: String,
        row: DocumentEntity,
        state: FoldedState,
        supersession: Supersession,
        request: FoldRequest,
        resurrected: MutableList<Resurrected>,
    ) {
        // Predecessors (older ids that relinked onto this doc): their metadata carries over.
        val preds = supersession.predecessors[doc].orEmpty()

        val (effTags, tagsChanged) = projectTags(state, doc, preds)
        projectFields(state, doc, preds)
        val meta = projectMeta(state, doc, preds)
        val lifecycle =
            projectLifecycle(
                state,
                doc,
                row,
                supersession,
                request.diskPaths,
                request.reconcileFileState,
                request.preserveLifecycleDocs,
                resurrected,
            )

        // Diffed: skip the upsert when the effective row equals what is stored, so a fold
        // triggered by one edit does not rewrite (and invalidate) every other doc's row.
        val newRow =
            row.copy(
                relPath = lifecycle.relPath,
                fileName = lifecycle.fileName,
                reviewed = meta.reviewed,
                correspondent = meta.correspondent,
                docType = meta.docType,
                docDate = meta.docDate,
                title = meta.title,
                notes = meta.notes,
                ocrEngineOverride = meta.ocrEngineOverride,
                trashed = lifecycle.trashed,
                trashedOrigPath = if (lifecycle.trashed) lifecycle.origPath else null,
                deleted = lifecycle.deleted,
                missing = lifecycle.missing,
            )
        val rowChanged = newRow != row
        if (rowChanged) documentDao.upsert(newRow)

        // FTS: preserve the existing extracted-text content (OCR/embedded text written by the
        // OCR pipeline). The fold only owns the metadata-derived columns; carry content
        // forward so a metadata change (tag add, prop set) never wipes full-text search.
        // A brand-new doc has no fts row yet -> null -> empty content until OCR fills it.
        // Under the shared gate so this read-then-replace cannot interleave with an OCR
        // content write for the same doc (see FtsWriteGate). All fts metadata columns derive
        // from the doc row + tags, so when neither changed and a row already exists there is
        // nothing to write.
        suspend fun updateFts() {
            val existingContent = ftsDao.contentForDoc(doc)
            if (rowChanged || tagsChanged || existingContent == null) {
                ftsDao.replaceDocument(
                    docId = doc,
                    fileName = lifecycle.fileName,
                    title = meta.title,
                    notes = meta.notes,
                    content = existingContent ?: "",
                    tags = if (effTags.isEmpty()) null else effTags.joinToString(" "),
                    correspondent = meta.correspondent,
                    docType = meta.docType,
                )
            }
        }
        if (request.ftsGateHeld) updateFts() else ftsWriteGate.withGate { updateFts() }
    }

    private companion object {
        const val RELINK_SCORE_NAME = 50

        const val RELINK_SCORE_SIZE = 40

        const val RELINK_SCORE_DIR = 10

        const val RELINK_SCORE_FUZZY = 15

        const val RELINK_SCORE_PATH_MATCH = 100
    }
}
