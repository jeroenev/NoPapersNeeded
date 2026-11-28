package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.DeleteAuthority
import dev.npn.core.fs.DeleteGate
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.KeepStore
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OperationLog
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.LifecycleDone
import dev.npn.core.index.heldDocumentStat
import dev.npn.core.index.holdsDocument
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.pathTaken
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

/**
 * Journal-backed [DocLifecycle]: a document's trash, restore, delete for good, rename and move.
 *
 * These touch the filesystem directly and keep the DB row in sync immediately (flags/paths) so the
 * UI reacts without waiting for a rescan. Rename and move journal an intended location before the
 * fold reconciles the physical PDF. Trash, restore and delete read the row, check the file and move
 * it under the writer lock, in the same step as their journal write
 * ([JournalCoordinator.changeLifecycle]): no rescan or storage-path move lands in between, and a
 * refused commit moves nothing (a trash entry with no doc-trash event would be invisible). A trash
 * or delete for good first waits for any bake in place ([BakeLock]), then acts on the document the
 * bake left.
 */
internal class DocLifecycleImpl(
    /** Every call runs whole as work the session owns ([SessionLifetime.owned]). */
    private val lifetime: SessionLifetime,
    private val coordinator: JournalCoordinator,
    db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val trashOps: TrashOps,
    private val log: NpnLog,
    /** The session's, shared with its bakes: a trash or delete for good waits for a bake. */
    private val bakeLock: BakeLock,
    /** The session's, so a trash entry's name carries the episode its event's time stands for. */
    private val clock: EpochClock,
    /**
     * What a rename or move sets off once it is published ([MetadataWriterImpl.followUpRelocation]);
     * the move landed whatever it does, so it logs its failures rather than throwing them.
     */
    private val followUp: suspend (docId: String) -> Unit = {},
    /** The keep store: restores read it whenever it holds a document; a trash writes it while [keepOn]. */
    private val keep: KeepStore? = null,
    /** Whether every device reads the keep store ([KeepGate]), so a trash may leave its copy only there. */
    private val keepOn: () -> Boolean = { false },
    /** Where a trash into the keep store notes itself, so a crash between its event and its file is finished. */
    private val operations: OperationLog = OperationLog.None,
    /** Names the recovery file a trash into the keep store claims its file under. */
    private val ids: IdSource = IdSource.Random,
) : DocLifecycle {
    private val documentDao = db.documentDao()

    /** The one way a delete for good takes a document's bytes. */
    private val gate = DeleteGate(fs)

    /** Trash [docId], or what a bake made of it once that bake is done: the receipt names the one trashed. */
    override suspend fun trashDoc(docId: String): TrashReceipt? = lifetime.owned { bakeLock.held { trash(docId) } }

    private suspend fun trash(docId: String): TrashReceipt? {
        val store = keep?.takeIf { keepOn() }
        var intent: String? = null
        val done =
            try {
                trashChange(docId, store) { intent = it }
            } finally {
                intent?.let(operations::release)
            }
        return done?.let { TrashReceipt(it.before.docId, it.after?.trashedAt) }
    }

    /** [trash]'s change, the intent of a trash into [store] handed to [noted] once it is open. */
    private suspend fun trashChange(
        docId: String,
        store: KeepStore?,
        noted: (String) -> Unit,
    ): LifecycleDone? =
        coordinator.changeLifecycle(docId, followRelinks = true) { doc ->
            if (doc == null || !LifecyclePlan.trashes(doc)) return@changeLifecycle null
            if (store != null) {
                // The event first, with the stat of the file it proved the document's; then the
                // file leaves for the keep store. Cut short in between, the file stays where it
                // was with that stat: in the trash, its file back, as the journal has it.
                val held = fs.heldDocumentStat(doc)
                // Before the event: its entry's episode is no later than the trash's own time.
                val episode = clock.nowMillis()
                val aside = NpnPaths.Temp.recoveryPath(beside = doc.relPath, nonce = ids.next())
                val intent =
                    held
                        ?.let { stat ->
                            operations.trashKept(doc.docId, doc.relPath, stat, episode, aside)
                        }?.also(noted)
                return@changeLifecycle LifecyclePlan.trashKept(doc, held) {
                    dematerialize(store, doc, aside, episode)
                    intent?.let { id -> operations.done(id) }
                }
            }
            // Only the document's own file goes into the trash, known by a stat while it is
            // unchanged. Another app may have written over it since the last rescan; that file
            // stays where it is for the next rescan to index, and the trash is recorded as for a
            // document whose file is gone.
            // The entry's name carries when this trash happened, so a purge can tell this episode's
            // entry from an older one's that a lagging journal still describes (TrashOps.purgeOlderThan).
            val trashPath =
                if (fs.holdsDocument(doc)) trashOps.trashTarget(doc.relPath, doc.docId, clock.nowMillis()) else null
            // A move keeps the stat, so the file it took is checked the same way where it went, and
            // the stat that proves it there is the one the event records.
            LifecyclePlan.trash(doc, trashPath) { moved -> fs.heldDocumentStat(doc.copy(relPath = moved)) }
        }

    /**
     * Take [doc]'s file at its path into the keep store in [episode], then delete it: a copy
     * published under its hash and verified, then the file claimed under [aside] and deleted once the
     * claimed file proves the document's ([DeleteGate.reapClaimed]). A file another process wrote
     * over meanwhile fails the copy's verification, or the claim's, and stays.
     */
    private suspend fun dematerialize(
        store: KeepStore,
        doc: DocumentEntity,
        aside: String,
        episode: Long,
    ) {
        val kept = store.put(doc.relPath, doc.docId, episode) ?: return
        gate.reapClaimed(doc.relPath, aside, doc.docId, DeleteAuthority.ExtraCopy(kept))
    }

    override suspend fun restoreDoc(docId: String) = lifetime.owned { restore(docId, listing = null) }

    override suspend fun restoreDocs(docIds: List<String>): Int =
        lifetime.owned { forEachListed(docIds, "Restoring") { docId, listing -> restore(docId, listing) } }

    override suspend fun undoTrash(trashes: List<TrashReceipt>): Int {
        val trashedAt = trashes.associate { it.docId to it.trashedAt }
        return lifetime.owned {
            forEachListed(trashedAt.keys.toList(), "Restoring") { docId, listing ->
                restore(docId, listing) { it.trashedAt == trashedAt[docId] }
            }
        }
    }

    /**
     * Restore [docId] from the trash as [listing] (from [TrashOps.listTrash]) found it, or as listed
     * now without one, while [byTrash] says the trash that holds it, as its row has it, is the one to
     * restore from (an Undo's; any, otherwise).
     */
    private suspend fun restore(
        docId: String,
        listing: List<FileMeta>?,
        byTrash: (DocumentEntity) -> Boolean = { true },
    ) {
        // Hashed before the lock, which every write, import and rescan waits on; under it, those
        // entries are checked by a stat, and only one whose stat changed since is hashed again.
        val hashed = trashOps.hashCandidates(docId, listing)
        coordinator.changeLifecycle(docId) { doc ->
            if (doc == null || !LifecyclePlan.restores(doc) || !byTrash(doc)) return@changeLifecycle null
            val target = LifecyclePlan.restorePath(doc)
            // Its bytes back where it was (a put-back that kept the mtime, or the copy a sync tool has
            // yet to take away: either stays in the trash until restored) are taken where they are.
            heldInPlace(listOf(target, doc.relPath), doc)?.let { file ->
                return@changeLifecycle LifecyclePlan.restore(doc, file.relPath, entry = null, entryStat = file) {
                    discardTrashCopies(docId, hashed, file)
                }
            }
            // The file lives in trash under a hash-suffixed name; the entry that holds its bytes goes
            // back to the target, or beside it when the target is taken. Another document may hold the
            // target since the trash gave it up, so even with no copy left the row goes beside that
            // one's file, or beside its row while that names the path with the file gone.
            val entry = trashOps.confirmedCopiesOf(docId, hashed).firstOrNull()
            // The stat of the entry just confirmed as the document's, which the move keeps: the row's
            // proof from then on. A row the fold made for a trash it never saw the file of has none.
            val entryStat = entry?.let { fs.stat(it) }
            // With no trash entry here, the keep store's copy: put back once the restore stands. Cut
            // short, the document is shown missing until a rescan copies it back ([KeepStore]).
            val kept = if (entry == null) keep?.entry(docId) else null
            val finalPath =
                if (entry != null || kept != null) {
                    trashOps.restoreTarget(target, docId, ::takenForRestore)
                } else {
                    target.takeIf { freeFor(it, doc) } ?: trashOps.restoreTarget(target, docId, ::takenForRestore)
                }
            if (kept != null) {
                LifecyclePlan.restoreKept(doc, finalPath) { keep?.materialize(docId, finalPath) }
            } else {
                LifecyclePlan.restore(doc, finalPath, entry, entryStat)
            }
        }
    }

    /**
     * Remove [docId]'s copies in the trash, as [hashed] (before the lock) confirmed them, once a
     * restore took its file [kept] where it is: nothing else would, as the purge takes only a hidden
     * document's. Each goes through the gate while [kept] still holds the bytes. Under the lock.
     */
    private suspend fun discardTrashCopies(
        docId: String,
        hashed: Map<FileMeta, Boolean>,
        kept: FileMeta,
    ) {
        val proved = hashed.filterValues { it }.keys.associateBy { it.relPath }
        val authority = DeleteAuthority.ExtraCopy(kept.relPath, kept)
        for (path in trashOps.confirmedCopiesOf(docId, hashed)) gate.reap(path, docId, authority, proved[path])
    }

    /**
     * The first of [paths], a document path no live row of another document names, whose file holds
     * [doc]'s bytes, with its stat; null when none does. A file of another size than the row's is
     * not read, unless the row has no stat (one the fold made for a trash it never saw the file of).
     */
    private suspend fun heldInPlace(
        paths: List<String>,
        doc: DocumentEntity,
    ): FileMeta? =
        paths.distinct().filter(NpnPaths::isDocumentPath).firstNotNullOfOrNull { path ->
            val other = documentDao.getByRelPath(path)?.takeIf { it.docId != doc.docId && it.isLive }
            fs.stat(path)?.takeIf { meta ->
                other == null && !meta.isDirectory && (doc.size == 0L || meta.size == doc.size) &&
                    fs.holds(path, doc.docId)
            }
        }

    /**
     * Whether a restore must leave [path] alone: a file is there, or a live row names it. A live row
     * whose file went outside the app names its path until a rescan finds it missing, and the
     * restored row could not take the path from it. The trashed row itself is never live.
     */
    private suspend fun takenForRestore(path: String): Boolean = pathTaken(fs, documentDao, currentDocId = null, path)

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

    override suspend fun deleteDocPermanently(docId: String) =
        lifetime.owned { bakeLock.held { deleteForGood(docId, listing = null) } }

    /** Held once around the whole run, so no bake starts or ends between its documents. */
    override suspend fun deleteDocsPermanently(docIds: List<String>): PermanentDeleteTally =
        lifetime.owned {
            bakeLock.held {
                var filesLeft = 0
                val failed =
                    forEachListed(docIds, "Deleting") { docId, listing ->
                        if (deleteForGood(docId, listing) == PermanentDeleteOutcome.FILE_LEFT) filesLeft++
                    }
                PermanentDeleteTally(failed = failed, filesLeft = filesLeft)
            }
        }

    /**
     * Delete [docId] for good, or what a bake made of it, its copies in the trash as [listing] (from
     * [TrashOps.listTrash]) found them, or as listed after the deletion without one. Only while it is
     * in the trash: one restored since (here, on another device, or by an import) is left alone. Run
     * under [bakeLock].
     */
    private suspend fun deleteForGood(
        docId: String,
        listing: List<FileMeta>?,
    ): PermanentDeleteOutcome {
        // Tombstone first, bytes second: a refused commit must leave the file where it was. The
        // optimistic row write keeps it hidden (deleted flag) after any rescan.
        val done =
            coordinator.changeLifecycle(docId, followRelinks = true) { doc ->
                if (doc == null || !LifecyclePlan.deletes(doc)) null else LifecyclePlan.delete(doc)
            }
        val doc = done?.before ?: return PermanentDeleteOutcome.NOT_IN_TRASH
        // The tombstone is committed, so a failure here only leaves the bytes on disk, hidden: the
        // document stays deleted, and the caller hears that its file did not go.
        return runCatchingCancellable { removeBytes(doc, listing) }.fold(
            onSuccess = { PermanentDeleteOutcome.DELETED },
            onFailure = {
                log.warn("Deleting the file of ${doc.docId} failed", it)
                PermanentDeleteOutcome.FILE_LEFT
            },
        )
    }

    /**
     * Remove the bytes of [doc], its row before it was deleted for good: its copies in the trash as
     * [listing] found them (listed now without one), and the file at its path when that holds them
     * (a sync can bring them back there). A trashed or missing document gave its path up, so the file
     * there may be another one now. Each is hashed before the lock, as a whole file read under it
     * would hold up every other write; one whose stat changed since, or that appeared since, is
     * hashed again under it, since skipping it would leave bytes a rescan brings the document back
     * from. Each goes only while the journal still has the document deleted ([DeleteGate]): an
     * import of its bytes can have brought it back meanwhile, with that very file. Its row cannot
     * say: a rescan meanwhile shows a file still in place again without that being a return.
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
            val inTrash = trashOps.confirmedCopiesOf(docId, hashed)
            val atItsPath = listOfNotNull(doc.relPath.takeIf { stillHolds(it, docId, file) })
            val kept = keep?.entriesOf(docId).orEmpty()
            // The stat each was proved by before the lock, so the gate hashes none again that is
            // unchanged; a keep-store entry's name is its verified hash, so its listed stat is its proof.
            val proved =
                (hashed + file).filterValues { it }.keys.associateBy { it.relPath } +
                    kept.associate { it.path to it.meta }
            for (path in (inTrash + atItsPath + kept.map { it.path }).distinct()) {
                gate.reap(path, docId, DeleteAuthority.Tombstone(journal), proved[path])
            }
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
    ): RelocationOutcome =
        lifetime.owned {
            relocate(docId) { doc ->
                val clean = NpnPaths.sanitizeFileName(newFileName.trim()).ifEmpty { return@relocate null }
                val name = if (NpnPaths.isPdf(clean)) clean else "$clean.pdf"
                val dir = NpnPaths.parentDir(doc.relPath)
                if (dir.isEmpty()) name else "$dir/$name"
            }
        }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ): RelocationOutcome =
        if (!NpnPaths.isSafe(newDirRelPath)) {
            RelocationOutcome.INVALID_NAME
        } else {
            lifetime.owned {
                relocate(docId) { doc ->
                    val dir = NpnPaths.normalize(newDirRelPath)
                    if (dir.isEmpty()) doc.fileName else "$dir/${doc.fileName}"
                }
            }
        }

    /**
     * Journal [destination] as [docId]'s location, which the fold then moves its file to. A target that
     * is the document's own file under another spelling (a case-only rename where the filesystem
     * ignores case) is left alone: the fold cannot tell that move apart from one already made.
     */
    private suspend fun relocate(
        docId: String,
        destination: (DocumentEntity) -> String?,
    ): RelocationOutcome {
        val doc = documentDao.getByDocId(docId)?.takeIf { it.isLive } ?: return RelocationOutcome.UNCHANGED
        val target = destination(doc) ?: return RelocationOutcome.UNCHANGED
        val refusal =
            when {
                !NpnPaths.isDocumentPath(target) -> RelocationOutcome.INVALID_NAME
                target == doc.relPath -> RelocationOutcome.UNCHANGED
                !fs.exists(target) -> null
                fs.sameFile(doc.relPath, target) -> RelocationOutcome.UNCHANGED
                else -> RelocationOutcome.TAKEN
            }
        refusal?.let { return it }
        coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, target)))
        followUp(docId)
        return RelocationOutcome.MOVED
    }
}
