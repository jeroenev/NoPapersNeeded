package dev.npn.core.app

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.holds
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Journal-backed write side. Every metadata change is one or more [PendingOp]s committed through
 * the [JournalCoordinator], which serializes access to the live [dev.npn.core.journal.FoldedState]
 * (needed to build correct OR-set tag removes) and refolds+applies to the DB in the same critical
 * section.
 *
 * File-level operations (trash/restore/permanent delete/rename/move) touch the filesystem directly
 * and keep the DB row in sync immediately (flags/paths) so the UI reacts without waiting for a
 * rescan. Rename and move journal an intended location before the fold reconciles the physical PDF.
 */
class MetadataWriterImpl(
    private val coordinator: JournalCoordinator,
    private val db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val trashOps: TrashOps,
    private val pathRuleApplicator: StoragePathRuleApplicator,
    private val log: NpnLog,
    private val applyAutoMatch: suspend (String) -> Unit = {},
) : MetadataWriter,
    TagAdmin,
    DocLifecycle {
    private val documentDao get() = db.documentDao()
    private val tagDao get() = db.tagDao()

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return
        coordinator.commitAndApply(docIds.map { PendingOp.tagAdd(it, t) })
        docIds.forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return
        coordinator.commitBuilt { state -> state.tagRemovalOps(docIds, t) }
        docIds.forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) {
        if (docIds.isEmpty()) return
        val v = if (reviewed) "true" else "false"
        coordinator.commitAndApply(docIds.map { PendingOp.propSet(it, SystemProperty.REVIEWED, v) })
    }

    /**
     * Journal-only write of the OCR-engine pin (an LWW property). Not part of any public facet:
     * the single public home is [OcrFacade.setOcrEngine], which commits through here and then
     * re-selects the indexed version on this device.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        val v = engine.normalized()
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.OCR_ENGINE, v)))
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = setField(listOf(docId), key, value)

    override suspend fun setField(
        docIds: List<String>,
        key: String,
        value: String?,
    ) {
        val k = FieldKeys.normalize(key) ?: return
        val ids = docIds.distinct()
        if (ids.isEmpty()) return
        val changed =
            coordinator.commitBuilt { state ->
                val v = canonical(state, k, value)
                ids.filter { state.effectiveFields(it)[k] != v }.map { PendingOp.fieldSet(it, k, v) }
            }
        if (!changed) return
        ids.forEach { docId ->
            if (k == FieldKeys.TITLE) applyAutoMatch(docId)
            pathRuleApplicator.applyFor(docId)
        }
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        fields: Map<String, String?>,
    ) {
        if (docIds.isEmpty()) return
        val tags =
            addTags
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
        val ids = docIds.distinct()
        val keyed = fields.mapNotNull { (key, value) -> FieldKeys.normalize(key)?.let { it to value } }
        if (tags.isEmpty() && keyed.isEmpty()) return
        coordinator.commitBuilt { state ->
            val values = keyed.map { (key, value) -> key to canonical(state, key, value) }
            ids.flatMap { docId ->
                tags.map { PendingOp.tagAdd(docId, it) } +
                    values.map { (key, value) -> PendingOp.fieldSet(docId, key, value) }
            }
        }
        // The same follow-ups the individual setters do, but once per document for the whole edit
        // instead of once per value. Auto-match only reacts to a new title, matching setField.
        ids.forEach { docId ->
            if (keyed.any { it.first == FieldKeys.TITLE }) applyAutoMatch(docId)
            pathRuleApplicator.applyFor(docId)
        }
    }

    /**
     * The canonical stored form of [raw] for field [key], per the live registry: an unregistered
     * key is TEXT. Runs under the coordinator mutex so the type it validates against is the one
     * the write is folded with.
     */
    private fun canonical(
        state: FoldedState,
        key: String,
        raw: String?,
    ): String? {
        val type = state.effectiveFieldType(key)
        return when (val parsed = FieldValues.canonicalize(type, raw)) {
            is FieldParse.Ok -> parsed.canonical

            is FieldParse.Invalid -> throw IllegalArgumentException(
                "'${parsed.raw}' is not a valid ${type.wire} for field '$key'",
            )
        }
    }

    override suspend fun renameTag(
        from: String,
        to: String,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagRename(f, t)))
        tagDao.docIdsWithTag(t).forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagMerge(f, t)))
        tagDao.docIdsWithTag(t).forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun deleteTag(name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        val affected = tagDao.docIdsWithTag(t)
        // No dedicated journal op by design: remove the tag from every doc currently holding it,
        // listing all observed dots (OR-set delete-everywhere).
        coordinator.commitBuilt { state -> state.tagRemovalOps(state.taggedDocs(), t) }
        affected.forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) {
        val t = tag.trim()
        if (t.isEmpty()) return
        coordinator.commitAndApply(listOf(PendingOp.tagStyle(t, color, icon)))
    }

    override suspend fun trashDoc(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        if (doc.trashed || doc.deleted) return
        // A trash entry with no doc-trash event is invisible: the next rescan marks the document
        // missing and there is nothing to restore. So refuse before the move, not after it.
        coordinator.checkWritable()
        val origPath = doc.relPath
        // From the move on, a caller that goes away must not split the trash in two: a cancellation
        // after the event was published would move the PDF back under a trashed document.
        withContext(NonCancellable) {
            val trashPath = if (fs.exists(origPath)) trashOps.moveToTrash(origPath, docId) else null
            var committed = false
            try {
                // Immediate DB sync so the UI hides it now; the event carries origPath for cross-device
                // restore. The optimistic row write runs under the coordinator mutex, atomically with
                // the committing fold, so a concurrent reconcile cannot clobber the trashed flag before
                // the journal event is folded; a commit that fails puts the row back (see
                // JournalCoordinator.commitWithOptimisticLifecycle).
                coordinator.commitWithOptimisticLifecycle(
                    ops = listOf(PendingOp.docTrash(docId, origPath)),
                    lifecycleDocs = setOf(docId),
                ) {
                    documentDao.upsert(doc.copy(trashed = true, trashedOrigPath = origPath, missing = false))
                }
                committed = true
            } finally {
                if (!committed && trashPath != null) moveBack(trashPath, origPath)
            }
        }
    }

    /**
     * Best-effort undo of a move whose commit published nothing. The caller still sees the commit's
     * failure; an undo that fails too is only logged.
     */
    private suspend fun moveBack(
        from: String,
        to: String,
    ) {
        withContext(NonCancellable) {
            runCatchingCancellable { fs.move(from, to) }.onFailure { log.warn("Could not move $from back to $to", it) }
        }
    }

    override suspend fun restoreDoc(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        if (!doc.trashed) return
        val target = doc.trashedOrigPath ?: doc.relPath
        // A file moved out of the trash with no doc-restore event leaves the document trashed with
        // its trash entry gone, so refuse before the move, as trashDoc does.
        coordinator.checkWritable()
        // The file lives in trash under a hash-suffixed name; find it and move it back (uniquify
        // via TrashOps.restore if the target is taken). Another document may hold the target since
        // the trash gave it up, so even with no copy left the row goes beside that one's file.
        val entry = findTrashEntry(docId)
        // Not cancellable from the move on, as in trashDoc.
        withContext(NonCancellable) {
            val finalPath =
                entry?.let { trashOps.restore(it, target) }
                    ?: target.takeIf { !fs.exists(it) || fs.holds(it, docId) }
                    ?: trashOps.restoreTarget(target)
            var committed = false
            try {
                coordinator.commitWithOptimisticLifecycle(
                    ops =
                        listOf(
                            PendingOp.docRestore(docId),
                            PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, finalPath),
                        ),
                    lifecycleDocs = setOf(docId),
                ) {
                    documentDao.upsert(
                        doc.copy(
                            trashed = false,
                            trashedOrigPath = null,
                            trashedAt = null,
                            relPath = finalPath,
                            fileName = NpnPaths.fileName(finalPath),
                            missing = false,
                        ),
                    )
                }
                committed = true
            } finally {
                if (!committed && entry != null) moveBack(finalPath, entry)
            }
        }
    }

    override suspend fun deleteDocPermanently(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        // Tombstone first, bytes second: a refused commit must leave the file where it was. The
        // optimistic row write keeps it hidden (deleted flag) after any rescan, and is serialized
        // with the committing fold under the coordinator mutex.
        coordinator.commitWithOptimisticLifecycle(
            ops = listOf(PendingOp.docDelete(docId)),
            lifecycleDocs = setOf(docId),
        ) {
            documentDao.upsert(doc.copy(deleted = true, trashed = false, trashedAt = null, missing = false))
        }
        // The tombstone is committed, so a failure here only leaves the bytes on disk, hidden. A
        // trashed or missing document gave its path up, so the file there may be another one now.
        log.runLogged("Deleting the file of $docId") {
            val path = findTrashEntry(docId) ?: doc.relPath.takeIf { fs.holds(it, docId) }
            if (path != null) fs.delete(path)
        }
    }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ) {
        relocate(docId) { doc ->
            val clean = NpnPaths.sanitizeFileName(newFileName.trim()).ifEmpty { return@relocate null }
            val dir = NpnPaths.parentDir(doc.relPath)
            if (dir.isEmpty()) clean else "$dir/$clean"
        }
    }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ) {
        relocate(docId) { doc ->
            val dir = NpnPaths.normalize(newDirRelPath)
            if (dir.isEmpty()) doc.fileName else "$dir/${doc.fileName}"
        }
    }

    private suspend fun relocate(
        docId: String,
        destination: (DocumentEntity) -> String?,
    ) {
        val doc = documentDao.getByDocId(docId) ?: return
        if (!doc.isLive) return
        val target = destination(doc) ?: return
        if (NpnPaths.normalize(target) == NpnPaths.normalize(doc.relPath)) return
        require(DocumentPaths.isDocumentPath(target)) { "Invalid document path: $target" }
        check(!fs.exists(target)) { "Target already exists: $target" }
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, target)))
        applyAutoMatch(docId)
        pathRuleApplicator.applyFor(docId)
    }

    /** Trim a system-property input, coercing null/blank to null (clears the property). */
    private fun String?.normalized(): String? = this?.trim()?.ifEmpty { null }

    /** Locate the file currently in `.npn/trash/` that belongs to [docId] (hash-suffixed name). */
    private suspend fun findTrashEntry(docId: String): String? {
        val marker = "__${docId.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)}"
        return trashOps.listTrash().firstOrNull { it.name.contains(marker) }?.relPath
    }
}

/**
 * [ReviewConfigFacade] over the journal: the review workflow is a library setting, and the switch
 * to the inbox workflow marks documents reviewed with the same `prop-set` events [MetadataWriterImpl]
 * writes. Writes run through [owned], so the session waits them out before it closes.
 */
internal class JournalReviewConfig(
    private val coordinator: JournalCoordinator,
    private val documentDao: DocumentDao,
    private val owned: suspend (suspend () -> Unit) -> Unit,
) : ReviewConfigFacade {
    override val reviewMode: Flow<ReviewMode> =
        coordinator.settingFlow(ReviewMode.WIRE_KEY).map(ReviewMode::fromWire)

    override suspend fun setReviewMode(mode: ReviewMode) =
        owned { coordinator.commitAndApply(listOf(PendingOp.settingSet(ReviewMode.WIRE_KEY, mode.wire))) }

    // Journal events are permanent, so only documents the projection does not show reviewed yet
    // get one: a second run, or a library already reviewed, writes nothing.
    override suspend fun markReviewedForMigration(onlyTagged: Boolean) =
        owned {
            val ops =
                documentDao
                    .unreviewedLiveDocIds(taggedOnly = onlyTagged)
                    .map { PendingOp.propSet(it, SystemProperty.REVIEWED, "true") }
            if (ops.isNotEmpty()) coordinator.commitAndApply(ops)
        }
}
