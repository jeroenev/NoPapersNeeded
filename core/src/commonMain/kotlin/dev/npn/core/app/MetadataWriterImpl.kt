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
import dev.npn.core.index.holdsDocument
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Journal-backed write side. Every metadata change is one or more [PendingOp]s committed through
 * the [JournalCoordinator], which serializes access to the live [dev.npn.core.journal.FoldedState]
 * (needed to build correct OR-set tag removes) and refolds+applies to the DB in the same critical
 * section.
 *
 * File-level operations (trash/restore/permanent delete/rename/move) touch the filesystem directly
 * and keep the DB row in sync immediately (flags/paths) so the UI reacts without waiting for a
 * rescan. Rename and move journal an intended location before the fold reconciles the physical PDF.
 * Trash, restore and delete read the row, check the file and move it under the writer lock, in the
 * same step as their journal write ([JournalCoordinator.changeLifecycle]): no rescan or storage-path
 * move lands in between, and a refused commit moves nothing (a trash entry with no doc-trash event
 * would be invisible).
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
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null || doc.trashed || doc.deleted) return@changeLifecycle null
            val origPath = doc.relPath
            // Only the document's own file goes into the trash, known by a stat while it is unchanged.
            // Another app may have written over it since the last rescan; that file stays where it is
            // for the next rescan to index, and the trash is recorded as for a document whose file is
            // gone.
            val trashPath = if (fs.holdsDocument(doc)) trashOps.trashTarget(origPath, docId) else null
            LifecycleChange(
                // The event carries origPath for a restore on another device.
                ops = listOf(PendingOp.docTrash(docId, origPath)),
                move = { trashPath?.let { fs.move(origPath, it) } },
                optimisticWrite = {
                    documentDao.upsert(doc.copy(trashed = true, trashedOrigPath = origPath, missing = false))
                },
                undoMove = { trashPath?.let { fs.move(it, origPath) } },
            )
        }
    }

    override suspend fun restoreDoc(docId: String) {
        // Hashed before the lock, which every write, import and rescan waits on; under it, only an
        // entry whose stat changed since is hashed again.
        val hashed = trashOps.hashCandidates(docId)
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null || !doc.trashed) return@changeLifecycle null
            val target = doc.trashedOrigPath ?: doc.relPath
            // The file lives in trash under a hash-suffixed name; the entry that holds its bytes goes
            // back to the target, or beside it when the target is taken. Another document may hold the
            // target since the trash gave it up, so even with no copy left the row goes beside that
            // one's file.
            val entry = trashOps.copiesOf(docId, hashed).firstOrNull()
            val finalPath =
                if (entry != null) {
                    trashOps.restoreTarget(target)
                } else {
                    target.takeIf { freeOrHolding(it, doc) } ?: trashOps.restoreTarget(target)
                }
            LifecycleChange(
                ops =
                    listOf(
                        PendingOp.docRestore(docId),
                        PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, finalPath),
                    ),
                move = { entry?.let { fs.move(it, finalPath) } },
                optimisticWrite = { documentDao.upsert(doc.restoredAt(finalPath)) },
                undoMove = { entry?.let { fs.move(finalPath, it) } },
            )
        }
    }

    /** Whether nothing is at [path], or [doc]'s bytes are; a file of another size is not hashed. */
    private suspend fun freeOrHolding(
        path: String,
        doc: DocumentEntity,
    ): Boolean {
        val meta = fs.stat(path) ?: return true
        return !meta.isDirectory && meta.size == doc.size && fs.holds(path, doc.docId)
    }

    /** This trashed row, restored to [relPath]. */
    private fun DocumentEntity.restoredAt(relPath: String) =
        copy(
            trashed = false,
            trashedOrigPath = null,
            trashedAt = null,
            relPath = relPath,
            fileName = NpnPaths.fileName(relPath),
            missing = false,
        )

    override suspend fun deleteDocPermanently(docId: String) {
        var deleted: DocumentEntity? = null
        // Tombstone first, bytes second: a refused commit must leave the file where it was. The
        // optimistic row write keeps it hidden (deleted flag) after any rescan.
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null) return@changeLifecycle null
            deleted = doc
            LifecycleChange(
                ops = listOf(PendingOp.docDelete(docId)),
                optimisticWrite = {
                    documentDao.upsert(doc.copy(deleted = true, trashed = false, trashedAt = null, missing = false))
                },
            )
        }
        val doc = deleted ?: return
        // The tombstone is committed, so a failure here only leaves the bytes on disk, hidden. Only
        // the document's bytes go: its copies in the trash, or else its file. A trashed or missing
        // document gave its path up, so the file there may be another one now. Each is hashed right
        // before it goes, outside the lock: a whole file read there would hold up every other write.
        log.runLogged("Deleting the file of $docId") {
            val own = trashOps.copiesOf(docId).ifEmpty { listOfNotNull(doc.relPath.takeIf { fs.holds(it, docId) }) }
            own.forEach { fs.delete(it) }
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
