package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DuplicateEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.TagEntity
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalIndex
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.DocProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reconciles the library folder + metadata journals into the Room index cache (PLAN.md sec. 1-2).
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
    private val journalIndex: JournalIndex,
    private val foldedTracker: DbFoldedSegmentTracker,
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
    private val duplicateDao get() = db.duplicateDao()

    suspend fun rescan(
        fullHash: Boolean = false,
        onProgress: (IndexProgress) -> Unit = {},
    ): ScanReport = rescanMutex.withLock {
        val started = clock.nowMillis()
        fun report(p: IndexProgress) {
            _scanState.value = p
            onProgress(p)
        }

        report(IndexProgress(IndexProgress.Phase.LISTING))
        val all = fs.listAll()

        // --- step 1: partition ---
        val indexable = ArrayList<FileMeta>()
        val conflictFiles = ArrayList<String>()
        for (meta in all) {
            if (meta.isDirectory) continue
            if (NpnPaths.isInternal(meta.relPath)) continue
            if (NpnPaths.isSyncTemp(meta.name)) continue
            if (NpnPaths.isSyncConflict(meta.name)) {
                conflictFiles.add(meta.relPath)
                continue
            }
            if (!NpnPaths.isPdf(meta.name)) continue
            if (NpnPaths.isHidden(meta.relPath)) continue
            indexable.add(meta)
        }

        // --- step 2: fast path vs hash ---
        val dbProjection = documentDao.allPathsAndMeta()
        val byPath = dbProjection.associateBy { it.relPath }
        // hash by docId of everything currently in the DB (for move/dup detection).
        val docIdByPath = HashMap<String, String>() // relPath -> known docId (fast path unchanged)
        val toHash = ArrayList<FileMeta>()
        for (meta in indexable) {
            val known = byPath[meta.relPath]
            if (!fullHash && known != null && known.size == meta.size && known.mtime == meta.mtimeMillis) {
                docIdByPath[meta.relPath] = known.docId
            } else {
                toHash.add(meta)
            }
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

        // Live set of paths present on disk (indexable).
        val diskPaths = indexable.map { it.relPath }.toHashSet()
        val metaByPath = indexable.associateBy { it.relPath }

        // Load existing canonical doc rows we may touch, keyed by docId and by path.
        val existingByDocId = HashMap<String, DocumentEntity>()
        val existingByPath = HashMap<String, DocumentEntity>()
        for (pm in dbProjection) {
            val row = documentDao.getByDocId(pm.docId)
            if (row != null) {
                existingByDocId[row.docId] = row
                existingByPath[row.relPath] = row
            }
        }

        var added = 0
        var moved = 0
        var changed = 0
        val duplicates = ArrayList<DuplicateReport>()
        val relinkCandidates = ArrayList<RelinkCandidate>()
        // New docs created this scan: docId -> (relPath, meta) for relink pairing.
        val newDocs = HashMap<String, FileMeta>()
        // Track paths whose canonical doc we already placed to avoid re-processing.
        val now = clock.nowMillis()

        for (meta in toHash) {
            val hash = hashes[meta.relPath] ?: continue
            val existingDoc = existingByDocId[hash]

            if (existingDoc != null) {
                val oldPath = existingDoc.relPath
                val canonicalStillOnDisk = oldPath in diskPaths && oldPath != meta.relPath
                if (canonicalStillOnDisk) {
                    // External duplicate: canonical untouched, record the extra path.
                    duplicates.add(DuplicateReport(hash, meta.relPath))
                } else if (oldPath != meta.relPath) {
                    // The file moved/renamed: canonical path no longer on disk (or is this path).
                    documentDao.upsert(
                        existingDoc.copy(
                            relPath = meta.relPath,
                            fileName = meta.name,
                            size = meta.size,
                            mtime = meta.mtimeMillis,
                            missing = false,
                        ),
                    )
                    existingByDocId[hash] = documentDao.getByDocId(hash)!!
                    moved++
                } else {
                    // Same path, bytes unchanged but was flagged (e.g. mtime touched): refresh.
                    if (existingDoc.missing || existingDoc.size != meta.size || existingDoc.mtime != meta.mtimeMillis) {
                        documentDao.upsert(
                            existingDoc.copy(
                                size = meta.size,
                                mtime = meta.mtimeMillis,
                                missing = false,
                            ),
                        )
                        existingByDocId[hash] = documentDao.getByDocId(hash)!!
                    }
                }
                continue
            }

            // Hash unknown. Does this path currently hold a different doc? (external edit)
            val docAtPath = existingByPath[meta.relPath]
            if (docAtPath != null && docAtPath.docId != hash) {
                // External edit: mark old missing (keep metadata), create new doc here. The old
                // doc must vacate the path (relPath is unique) so the new doc can take it; we move
                // it to a per-doc sentinel path under .npn (never indexed) that preserves identity.
                documentDao.upsert(
                    docAtPath.copy(relPath = missingSentinel(docAtPath.docId), missing = true),
                )
                existingByDocId[docAtPath.docId] = documentDao.getByDocId(docAtPath.docId)!!
                existingByPath.remove(meta.relPath)
                insertNewDocument(hash, meta, now)
                newDocs[hash] = meta
                added++
                changed++
                relinkCandidates.add(
                    RelinkCandidate(docAtPath.docId, hash, reason = "path-match", score = 100),
                )
            } else {
                // Brand new hash at a new path.
                insertNewDocument(hash, meta, now)
                newDocs[hash] = meta
                added++
            }
        }

        // --- step 4: disappeared files -> missing ---
        var missingCount = 0
        val missingDocs = ArrayList<DocumentEntity>()
        for (pm in dbProjection) {
            val row = existingByDocId[pm.docId] ?: continue
            if (row.deleted || row.trashed) continue
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

        // Pair missing docs against brand-new docs for relink candidates.
        for (missing in missingDocs) {
            for ((newDocId, newMeta) in newDocs) {
                if (relinkCandidates.any { it.oldDocId == missing.docId && it.newDocId == newDocId }) continue
                val score = scoreRelink(missing, newMeta)
                if (score.first > 0) {
                    relinkCandidates.add(RelinkCandidate(missing.docId, newDocId, score.second, score.first))
                }
            }
        }

        // --- step 6: journal fold -> DB projection ---
        report(IndexProgress(IndexProgress.Phase.FOLDING))
        val previouslyFolded = foldedTracker.load()
        val fold = journalIndex.refresh(previouslyFolded)
        val resurrected = applyFold(fold.state, diskPaths, metaByPath)
        foldedTracker.save(fold.foldedSegments)

        // Persist external duplicates (replace per doc so re-scans are idempotent).
        val dupByDoc = duplicates.groupBy { it.docId }
        for ((docId, entries) in dupByDoc) {
            duplicateDao.replaceAll(docId, entries.map { DuplicateEntity(docId, it.extraRelPath) })
        }

        report(IndexProgress(IndexProgress.Phase.DONE))
        _scanState.value = null

        ScanReport(
            added = added,
            moved = moved,
            changed = changed,
            missingCount = missingCount,
            duplicates = duplicates,
            conflictFiles = conflictFiles,
            resurrected = resurrected,
            relinkCandidates = relinkCandidates,
            hashedCount = hashedCount,
            durationMillis = clock.nowMillis() - started,
        )
    }

    /**
     * Fold-only refresh: refold the journals and re-apply the effective views (tags, props,
     * trash/delete, relinks) into the DB, WITHOUT walking/hashing the file tree for changes.
     *
     * Used by the service layer after it commits its own journal ops (tag add/remove, prop set,
     * trash, ...): a metadata write never changes the file set, so a full rescan would be wasteful.
     * It still needs the current disk paths so the resurrection logic (prefer-file-over-tombstone)
     * stays correct, so it does one lightweight [LibraryFileSystem.listAll] but never hashes.
     *
     * Reuses the exact same [applyFold] logic as [rescan]; no duplication.
     */
    suspend fun refreshFold(): List<Resurrected> = rescanMutex.withLock {
        _scanState.value = IndexProgress(IndexProgress.Phase.FOLDING)
        val all = fs.listAll()
        val diskPaths = HashSet<String>()
        val metaByPath = HashMap<String, FileMeta>()
        for (meta in all) {
            if (meta.isDirectory) continue
            if (NpnPaths.isInternal(meta.relPath)) continue
            if (NpnPaths.isSyncTemp(meta.name)) continue
            if (NpnPaths.isSyncConflict(meta.name)) continue
            if (!NpnPaths.isPdf(meta.name)) continue
            if (NpnPaths.isHidden(meta.relPath)) continue
            diskPaths.add(meta.relPath)
            metaByPath[meta.relPath] = meta
        }
        val previouslyFolded = foldedTracker.load()
        val fold = journalIndex.refresh(previouslyFolded)
        val resurrected = applyFold(fold.state, diskPaths, metaByPath)
        foldedTracker.save(fold.foldedSegments)
        _scanState.value = null
        resurrected
    }

    /**
     * A synthetic, unique relPath for a doc whose file was replaced in place (external edit). It
     * lives under `.npn/` so it is never indexed, and encodes the docId to satisfy the unique
     * relPath constraint while the doc waits for a relink.
     */
    private fun missingSentinel(docId: String): String = "${NpnPaths.NPN_DIR}/missing/$docId"

    private suspend fun insertNewDocument(hash: String, meta: FileMeta, now: Long) {
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
    private fun scoreRelink(missing: DocumentEntity, newMeta: FileMeta): Pair<Int, String> {
        var score = 0
        val reasons = ArrayList<String>()
        if (missing.fileName.equals(newMeta.name, ignoreCase = true)) {
            score += 50
            reasons.add("name")
        }
        if (missing.size == newMeta.size) {
            score += 40
            reasons.add("size")
        }
        if (NameSimilarity.parentDir(missing.relPath) == NameSimilarity.parentDir(newMeta.relPath)) {
            score += 10
            reasons.add("dir")
        }
        if (score == 0) {
            if (NameSimilarity.prefixMatch(missing.fileName, newMeta.name) ||
                NameSimilarity.fuzzyMatch(missing.fileName, newMeta.name)
            ) {
                score += 15
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
        diskPaths: Set<String>,
        metaByPath: Map<String, FileMeta>,
    ): List<Resurrected> {
        val resurrected = ArrayList<Resurrected>()

        // Collect all docs the journal has any data about.
        val journalDocs = LinkedHashSet<String>()
        journalDocs.addAll(state.taggedDocs())
        journalDocs.addAll(state.propDocs())

        // Also include every doc currently in the DB so trash/delete flags get cleared correctly.
        for (pm in documentDao.allPathsAndMeta()) journalDocs.add(pm.docId)

        // Upsert all tags referenced anywhere (with styles when set).
        val allTags = LinkedHashSet<String>()
        for (doc in journalDocs) allTags.addAll(state.effectiveTags(doc))
        for (tag in allTags) {
            val style = state.tagStyle(tag)
            tagDao.upsert(TagEntity(name = tag, color = style?.color, icon = style?.icon))
        }

        // Handle relink supersession: mark old docs deleted where a winning relink resolves to a
        // different, known id. Record predecessors so metadata carries onto the resolved target.
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

        for (doc in journalDocs) {
            val row = documentDao.getByDocId(doc) ?: continue

            // Predecessors (older ids that relinked onto this doc): their metadata carries over.
            val preds = predecessors[doc].orEmpty()

            // Tags: this doc's own effective tags plus every predecessor's effective tags.
            val tagSet = LinkedHashSet<String>()
            tagSet.addAll(state.effectiveTags(doc))
            for (p in preds) tagSet.addAll(state.effectiveTags(p))
            val effTags = tagSet.toList().sorted()
            tagDao.setForDocument(doc, effTags)

            // Props: own value wins; otherwise inherit from a predecessor (first that has it).
            fun prop(key: DocProperty): String? {
                state.effectiveProp(doc, key)?.let { return it }
                for (p in preds) state.effectiveProp(p, key)?.let { return it }
                return null
            }
            val reviewed = prop(DocProperty.REVIEWED) == "true"
            val correspondent = prop(DocProperty.CORRESPONDENT)
            val docType = prop(DocProperty.DOC_TYPE)
            val docDate = prop(DocProperty.DOC_DATE)
            val title = prop(DocProperty.TITLE)

            // Trash / delete state, with resurrection preference for the file.
            val journalDeleted = state.isDeleted(doc) || doc in superseded
            val journalTrashed = state.isTrashed(doc) && !state.isDeleted(doc)
            val origPath = state.trashOrigPath(doc)

            var trashed = journalTrashed
            var deleted = journalDeleted
            var missing = row.missing
            var relPath = row.relPath
            var fileName = row.fileName

            val origOnDisk = origPath?.takeIf { it in diskPaths }
            val fileAtCurrent = relPath in diskPaths

            if ((journalTrashed || journalDeleted) && (origOnDisk != null || fileAtCurrent)) {
                // Resurrection: prefer the file. Clear trashed/deleted for the working view.
                if (!superseded.contains(doc)) {
                    trashed = false
                    deleted = false
                    missing = false
                    if (origOnDisk != null) {
                        relPath = origOnDisk
                        fileName = origOnDisk.substringAfterLast('/')
                    }
                    resurrected.add(Resurrected(doc, relPath, wasDeleted = journalDeleted))
                }
            }

            documentDao.upsert(
                row.copy(
                    relPath = relPath,
                    fileName = fileName,
                    reviewed = reviewed,
                    correspondent = correspondent,
                    docType = docType,
                    docDate = docDate,
                    title = title,
                    trashed = trashed,
                    trashedOrigPath = if (trashed) origPath else null,
                    deleted = deleted,
                    missing = missing,
                ),
            )

            // FTS: preserve the existing extracted-text content (OCR/embedded text written by the
            // OCR pipeline). The fold only owns the metadata-derived columns; carry content
            // forward so a metadata change (tag add, prop set) never wipes full-text search.
            // A brand-new doc has no fts row yet -> null -> empty content until OCR fills it.
            // Under the shared gate so this read-then-replace cannot interleave with an OCR
            // content write for the same doc (see FtsWriteGate).
            ftsWriteGate.withGate {
                val existingContent = ftsDao.contentForDoc(doc) ?: ""
                ftsDao.replaceDocument(
                    docId = doc,
                    fileName = fileName,
                    title = title,
                    content = existingContent,
                    tags = if (effTags.isEmpty()) null else effTags.joinToString(" "),
                    correspondent = correspondent,
                    docType = docType,
                )
            }
        }

        return resurrected
    }
}
