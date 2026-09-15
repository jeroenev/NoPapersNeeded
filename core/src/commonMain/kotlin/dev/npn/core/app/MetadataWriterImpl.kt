package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.effectiveFieldType
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator

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
    private val applyAutoMatch: suspend (String) -> Unit = {},
) : MetadataWriter,
    TagAdmin,
    DocLifecycle {
    private val documentDao get() = db.documentDao()

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
        documentDao.getDocIdsWithTag(t).forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagMerge(f, t)))
        documentDao.getDocIdsWithTag(t).forEach { pathRuleApplicator.applyFor(it) }
    }

    override suspend fun deleteTag(name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        val affected = documentDao.getDocIdsWithTag(t)
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
        val origPath = doc.relPath
        if (fs.exists(origPath)) {
            trashOps.moveToTrash(origPath, docId)
        }
        // Immediate DB sync so the UI hides it now; the event carries origPath for cross-device
        // restore. The optimistic row write runs under the coordinator mutex, atomically with the
        // committing fold, so a concurrent reconcile cannot clobber the trashed flag before the
        // journal event is folded (see JournalCoordinator.commitWithOptimisticLifecycle).
        coordinator.commitWithOptimisticLifecycle(
            ops = listOf(PendingOp.docTrash(docId, origPath)),
            lifecycleDocs = setOf(docId),
        ) {
            documentDao.upsert(doc.copy(trashed = true, trashedOrigPath = origPath, missing = false))
        }
    }

    override suspend fun restoreDoc(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        if (!doc.trashed) return
        val target = doc.trashedOrigPath ?: doc.relPath
        // The file lives in trash under a hash-suffixed name; find it and move it back (uniquify
        // via TrashOps.restore if the target is taken).
        val finalPath = restoreFromTrash(docId, target) ?: target
        coordinator.commitWithOptimisticLifecycle(
            ops = listOf(PendingOp.docRestore(docId), PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, finalPath)),
            lifecycleDocs = setOf(docId),
        ) {
            documentDao.upsert(
                doc.copy(
                    trashed = false,
                    trashedOrigPath = null,
                    relPath = finalPath,
                    fileName = finalPath.substringAfterLast('/'),
                    missing = false,
                ),
            )
        }
    }

    override suspend fun deleteDocPermanently(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        val trashEntry = findTrashEntry(docId)
        when {
            trashEntry != null -> runCatching { fs.delete(trashEntry) }
            fs.exists(doc.relPath) -> runCatching { fs.delete(doc.relPath) }
        }
        // Tombstone in DB immediately; keep it hidden (deleted flag) after any rescan. The
        // optimistic row write is serialized with the committing fold under the coordinator mutex.
        coordinator.commitWithOptimisticLifecycle(
            ops = listOf(PendingOp.docDelete(docId)),
            lifecycleDocs = setOf(docId),
        ) {
            documentDao.upsert(doc.copy(deleted = true, trashed = false, missing = false))
        }
    }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ) {
        relocate(docId) { doc ->
            val clean = NpnPaths.sanitizeFileName(newFileName.trim()).ifEmpty { return@relocate null }
            val dir = doc.relPath.substringBeforeLast('/', "")
            val target = if (dir.isEmpty()) clean else "$dir/$clean"
            target to clean
        }
    }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ) {
        relocate(docId) { doc ->
            val dir = NpnPaths.normalize(newDirRelPath)
            val target = if (dir.isEmpty()) doc.fileName else "$dir/${doc.fileName}"
            target to target.substringAfterLast('/')
        }
    }

    private suspend fun relocate(
        docId: String,
        destination: (DocumentEntity) -> Pair<String, String>?,
    ) {
        val doc = documentDao.getByDocId(docId) ?: return
        if (doc.trashed || doc.deleted || doc.missing) return
        val (target, _) = destination(doc) ?: return
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

    private suspend fun restoreFromTrash(
        docId: String,
        toRelPath: String,
    ): String? {
        val entry = findTrashEntry(docId) ?: return null
        return trashOps.restore(entry, toRelPath)
    }
}
