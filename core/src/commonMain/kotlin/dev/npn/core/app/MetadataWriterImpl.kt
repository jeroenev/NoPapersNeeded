package dev.npn.core.app

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.holds
import dev.npn.core.index.hasStoredStat
import dev.npn.core.index.holdsDocument
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pathrules.pathTaken
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
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
 *
 * What a write sets off once it is published (the storage-path rule, the auto-match rules) runs per
 * document through [followUp]: the write landed whatever they do, so their failure is logged, never
 * thrown.
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
        followUp(docIds)
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        val t = tag.trim()
        if (t.isEmpty() || docIds.isEmpty()) return
        coordinator.commitBuilt { state -> state.tagRemovalOps(docIds, t) }
        followUp(docIds)
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
                // Against what the document shows, inherited values included: clearing one is a write.
                ids.filter { state.inheritedFields(it)[k] != v }.map { PendingOp.fieldSet(it, k, v) }
            }
        if (changed) followUp(ids, autoMatch = k == FieldKeys.TITLE)
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
        followUp(ids, autoMatch = keyed.any { it.first == FieldKeys.TITLE })
    }

    /**
     * What a write to [docIds] sets off once it is published: the auto-match rules when [autoMatch]
     * (a new title), then the storage-path rule, for each document. The write landed whatever they
     * do, so a failure is logged for its document and the others carry on; thrown, it would have a
     * caller take the write for one that did not land, and send it again.
     */
    private suspend fun followUp(
        docIds: Collection<String>,
        autoMatch: Boolean = false,
    ) {
        for (docId in docIds) {
            if (autoMatch) log.runLogged("Auto-matching $docId after a write") { applyAutoMatch(docId) }
            log.runLogged("Applying the storage-path rule to $docId") { pathRuleApplicator.applyFor(docId) }
        }
    }

    /** The documents tagged [tag] now, for a [followUp]; failing to list them is logged as its failure is. */
    private suspend fun taggedNow(tag: String): List<String> =
        log.runLogged("Listing the documents tagged $tag after a write") { tagDao.docIdsWithTag(tag) }.orEmpty()

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
        followUp(taggedNow(t))
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        val f = from.trim()
        val t = to.trim()
        if (f.isEmpty() || t.isEmpty() || f == t) return
        coordinator.commitAndApply(listOf(PendingOp.tagMerge(f, t)))
        followUp(taggedNow(t))
    }

    override suspend fun deleteTag(name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        val affected = tagDao.docIdsWithTag(t)
        // No dedicated journal op by design: remove the tag from every doc currently holding it,
        // listing all observed dots (OR-set delete-everywhere).
        coordinator.commitBuilt { state -> state.tagRemovalOps(state.taggedDocs(), t) }
        followUp(affected)
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

    override suspend fun trashDoc(docId: String): Boolean =
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

    override suspend fun restoreDoc(docId: String) = restore(docId, listing = null)

    override suspend fun restoreDocs(docIds: List<String>): Int = forEachListed(docIds, "Restoring", ::restore)

    /**
     * Restore [docId] from the trash as [listing] (from [TrashOps.listTrash]) found it, or as listed
     * now without one.
     */
    private suspend fun restore(
        docId: String,
        listing: List<FileMeta>?,
    ) {
        // Hashed before the lock, which every write, import and rescan waits on; under it, those
        // entries are checked by a stat, and only one whose stat changed since is hashed again.
        val hashed = trashOps.hashCandidates(docId, listing)
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null || !doc.trashed) return@changeLifecycle null
            val target = doc.trashedOrigPath ?: doc.relPath
            // The file lives in trash under a hash-suffixed name; the entry that holds its bytes goes
            // back to the target, or beside it when the target is taken. Another document may hold the
            // target since the trash gave it up, so even with no copy left the row goes beside that
            // one's file, or beside its row while that names the path with the file gone.
            val entry = trashOps.confirmedCopiesOf(docId, hashed).firstOrNull()
            val finalPath =
                if (entry != null) {
                    trashOps.restoreTarget(target, ::takenForRestore)
                } else {
                    target.takeIf { freeFor(it, doc) } ?: trashOps.restoreTarget(target, ::takenForRestore)
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

    /**
     * Whether a restore must leave [path] alone: a file is there, or a live row names it. A live row
     * whose file went outside the app names its path until a rescan finds it missing, and the
     * restored row could not take the path from it. The trashed row itself is never live.
     */
    private suspend fun takenForRestore(path: String): Boolean =
        pathTaken(fs, documentDao, currentDocId = null, path) { it.isLive }

    /**
     * Whether [doc] can come back at [path] with no copy to move there: no live row names it, and
     * nothing is at it or [doc]'s bytes are; a file of another size is not hashed.
     */
    private suspend fun freeFor(
        path: String,
        doc: DocumentEntity,
    ): Boolean {
        if (documentDao.getByRelPath(path)?.isLive == true) return false
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

    override suspend fun deleteDocPermanently(docId: String) = deleteForGood(docId, listing = null)

    override suspend fun deleteDocsPermanently(docIds: List<String>): PermanentDeleteTally {
        var filesLeft = 0
        val failed =
            forEachListed(docIds, "Deleting") { docId, listing ->
                if (deleteForGood(docId, listing) == PermanentDeleteOutcome.FILE_LEFT) filesLeft++
            }
        return PermanentDeleteTally(failed = failed, filesLeft = filesLeft)
    }

    /**
     * Delete [docId] for good, its copies in the trash as [listing] (from [TrashOps.listTrash]) found
     * them, or as listed after the deletion without one. Only while it is in the trash: one restored
     * since (here, on another device, or by an import) is left alone.
     */
    private suspend fun deleteForGood(
        docId: String,
        listing: List<FileMeta>?,
    ): PermanentDeleteOutcome {
        var deleted: DocumentEntity? = null
        // Tombstone first, bytes second: a refused commit must leave the file where it was. The
        // optimistic row write keeps it hidden (deleted flag) after any rescan.
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null || !doc.trashed) return@changeLifecycle null
            deleted = doc
            LifecycleChange(
                ops = listOf(PendingOp.docDelete(docId)),
                optimisticWrite = {
                    documentDao.upsert(doc.copy(deleted = true, trashed = false, trashedAt = null, missing = false))
                },
            )
        }
        val doc = deleted ?: return PermanentDeleteOutcome.NOT_IN_TRASH
        // The tombstone is committed, so a failure here only leaves the bytes on disk, hidden: the
        // document stays deleted, and the caller hears that its file did not go.
        return runCatchingCancellable { removeBytes(doc, listing) }.fold(
            onSuccess = { PermanentDeleteOutcome.DELETED },
            onFailure = {
                log.warn("Deleting the file of $docId failed", it)
                PermanentDeleteOutcome.FILE_LEFT
            },
        )
    }

    /**
     * Remove the bytes of [doc], its row before it was deleted for good: its copies in the trash as
     * [listing] found them (listed now without one), or else its file. A trashed or missing document
     * gave its path up, so the file there may be another one now. Each is hashed before the lock, as
     * a whole file read under it would hold up every other write, and goes under it only while
     * unchanged since and while the journal still has the document deleted: an import of its bytes
     * can have brought it back meanwhile, with that very file. Its row cannot say: a rescan meanwhile
     * shows a file still in place again without that being a return.
     */
    private suspend fun removeBytes(
        doc: DocumentEntity,
        listing: List<FileMeta>?,
    ) {
        val docId = doc.docId
        val hashed = trashOps.hashCandidates(docId, listing)
        val file = fileAt(doc)
        coordinator.serialized { journal ->
            if (!journal.isDeleted(docId)) return@serialized
            val own =
                trashOps.confirmedCopiesOf(docId, hashed).ifEmpty {
                    listOfNotNull(doc.relPath.takeIf { stillHolds(it, docId, file) })
                }
            own.forEach { fs.delete(it) }
        }
    }

    /**
     * Whether the file at the path of [doc], its row before the deletion, holds its bytes, keyed by
     * the stat it had, for [stillHolds]. A file another live row names with the size and mtime that
     * row stored is that document's, and is not read.
     */
    private suspend fun fileAt(doc: DocumentEntity): Map<FileMeta, Boolean> {
        val meta = fs.stat(doc.relPath) ?: return emptyMap()
        val holder = documentDao.getByRelPath(doc.relPath)?.takeIf { it.docId != doc.docId && it.isLive }
        val held = holder?.let { meta.hasStoredStat(it.size, it.mtime) } != true && fs.holdsDocument(doc)
        return mapOf(meta to held)
    }

    /**
     * Run [step] for each of [docIds] over one listing of the trash, so a bulk restore or delete lists
     * it once rather than once per document; a document that fails does not stop the others. Returns
     * how many failed, each failure logged as "[what] <id> failed".
     */
    private suspend fun forEachListed(
        docIds: List<String>,
        what: String,
        step: suspend (String, List<FileMeta>) -> Unit,
    ): Int {
        if (docIds.isEmpty()) return 0
        val listing = trashOps.listTrash()
        return docIds.count { docId ->
            runCatchingCancellable { step(docId, listing) }
                .onFailure { log.warn("$what $docId failed", it) }
                .isFailure
        }
    }

    /** Whether [path] holds [docId]'s bytes: by [hashed] while it has the stat hashed there, else hashed. */
    private suspend fun stillHolds(
        path: String,
        docId: String,
        hashed: Map<FileMeta, Boolean>,
    ): Boolean {
        val meta = fs.stat(path) ?: return false
        return hashed[meta] ?: fs.holds(path, docId)
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
        followUp(listOf(docId), autoMatch = true)
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
