package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.DocProperty

/**
 * Journal-backed write side. Every metadata change is one or more [PendingOp]s committed through
 * the [JournalCoordinator], which serializes access to the live [dev.npn.core.journal.FoldedState]
 * (needed to build correct OR-set tag removes) and refolds+applies to the DB in the same critical
 * section.
 *
 * File-level operations (trash/restore/permanent delete/rename/move) touch the filesystem directly
 * and keep the DB row in sync immediately (flags/paths) so the UI reacts without waiting for a
 * rescan. Trash/restore/delete also emit journal events so the state syncs across devices; rename
 * and move do NOT (path is file-level state that the indexer re-derives from disk on rescan; there
 * is no path journal op by design, see PLAN.md sec. 2).
 */
class MetadataWriterImpl(
    private val coordinator: JournalCoordinator,
    private val db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val trashOps: TrashOps,
) : MetadataWriter {

    private val documentDao get() = db.documentDao()

    override suspend fun addTag(docIds: List<String>, tag: String) {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return
        coordinator.commitAndApply(docIds.map { PendingOp.tagAdd(it, t) })
    }

    override suspend fun removeTag(docIds: List<String>, tag: String) {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return
        coordinator.commitBuilt { state -> orSetTagRemovals(state, docIds, t) }
    }

    override suspend fun setReviewed(docIds: List<String>, reviewed: Boolean) {
        if (docIds.isEmpty()) return
        val v = if (reviewed) "true" else "false"
        coordinator.commitAndApply(docIds.map { PendingOp.propSet(it, DocProperty.REVIEWED, v) })
    }

    override suspend fun setCorrespondent(docIds: List<String>, value: String?) {
        if (docIds.isEmpty()) return
        val v = value?.trim()?.ifEmpty { null }
        coordinator.commitAndApply(docIds.map { PendingOp.propSet(it, DocProperty.CORRESPONDENT, v) })
    }

    override suspend fun setDocType(docIds: List<String>, value: String?) {
        if (docIds.isEmpty()) return
        val v = value?.trim()?.ifEmpty { null }
        coordinator.commitAndApply(docIds.map { PendingOp.propSet(it, DocProperty.DOC_TYPE, v) })
    }

    override suspend fun setDocDate(docId: String, isoDate: String?) {
        val v = isoDate?.trim()?.ifEmpty { null }
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, DocProperty.DOC_DATE, v)))
    }

    override suspend fun setTitle(docId: String, title: String?) {
        val v = title?.trim()?.ifEmpty { null }
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, DocProperty.TITLE, v)))
    }

    override suspend fun setField(docId: String, key: String, value: String?) {
        val k = key.trim()
        if (k.isEmpty()) return
        val v = value?.trim()?.ifEmpty { null }
        coordinator.commitAndApply(listOf(PendingOp.fieldSet(docId, k, v)))
    }

    override suspend fun renameTag(from: String, to: String) {
        val f = from.trim(); val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagRename(f, t)))
    }

    override suspend fun mergeTag(from: String, to: String) {
        val f = from.trim(); val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagMerge(f, t)))
    }

    override suspend fun deleteTag(name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        // No dedicated journal op by design: remove the tag from every doc currently holding it,
        // listing all observed dots (OR-set delete-everywhere).
        coordinator.commitBuilt { state -> orSetTagRemovals(state, state.taggedDocs(), t) }
    }

    override suspend fun setTagStyle(tag: String, color: String?, icon: String?) {
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
            ops = listOf(PendingOp.docRestore(docId)),
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
        // Delete the file wherever it currently sits (canonical path or trash).
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

    override suspend fun renameFile(docId: String, newFileName: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        val clean = newFileName.trim().ifEmpty { return }
        val dir = doc.relPath.substringBeforeLast('/', "")
        val target = if (dir.isEmpty()) clean else "$dir/$clean"
        if (NpnPaths.normalize(target) == NpnPaths.normalize(doc.relPath)) return
        fs.move(doc.relPath, target)
        documentDao.upsert(doc.copy(relPath = target, fileName = clean))
        // No journal event: path is file-level state, re-derived on rescan.
    }

    override suspend fun moveFile(docId: String, newDirRelPath: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        val dir = NpnPaths.normalize(newDirRelPath)
        val target = if (dir.isEmpty()) doc.fileName else "$dir/${doc.fileName}"
        if (NpnPaths.normalize(target) == NpnPaths.normalize(doc.relPath)) return
        fs.move(doc.relPath, target)
        documentDao.upsert(doc.copy(relPath = target, fileName = target.substringAfterLast('/')))
        // No journal event: path is file-level state, re-derived on rescan.
    }

    /**
     * Build the OR-set `tag-remove` ops for [tag] over [docIds]: for each doc, list the dots
     * currently observed for `(doc, tag)` in [state] and emit a remove carrying them, skipping docs
     * that do not hold the tag (empty dots). Shared by [removeTag] (a doc subset) and [deleteTag]
     * (every tagged doc) so the "delete-everywhere by observed dots" rule lives in one place.
     */
    private fun orSetTagRemovals(state: FoldedState, docIds: Collection<String>, tag: String): List<PendingOp> =
        docIds.mapNotNull { docId ->
            val dots = state.liveTagDots(docId, tag)
            if (dots.isEmpty()) null else PendingOp.tagRemove(docId, tag, dots)
        }

    // --- trash helpers ---

    /** Locate the file currently in `.npn/trash/` that belongs to [docId] (hash-suffixed name). */
    private suspend fun findTrashEntry(docId: String): String? {
        val marker = "__${docId.take(8)}"
        return trashOps.listTrash().firstOrNull { it.name.contains(marker) }?.relPath
    }

    private suspend fun restoreFromTrash(docId: String, toRelPath: String): String? {
        val entry = findTrashEntry(docId) ?: return null
        return trashOps.restore(entry, toRelPath)
    }
}
