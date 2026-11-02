package dev.npn.core.sync

import dev.npn.core.fs.NpnPaths

/** What kind of remote file a [ManifestEntry] describes. */
enum class ManifestKind {
    DOCUMENT,
    TRASH,
}

/**
 * What this device last knew about one remote file: its identity and the size/mtime it had when
 * that identity was established. A remote entry whose size or mtime moved since is re-identified
 * rather than trusted, and every destructive remote call is guarded by the same pair.
 */
data class ManifestEntry(
    val remotePath: String,
    val kind: ManifestKind,
    val size: Long,
    val mtimeMillis: Long,
    val docId: String?,
    /** Where the document's library file was when this entry was last reconciled with it, if known. */
    val localPath: String? = null,
) {
    fun matches(entry: RemoteEntry): Boolean = entry.size == size && entry.mtimeMillis == mtimeMillis
}

/** One document row as the planner sees it. */
data class LocalDocument(
    val docId: String,
    /**
     * The cached actual path ([dev.npn.core.db.DocumentEntity.relPath]). A hidden row keeps it while
     * no other row takes it, and otherwise waits at a sentinel that is no document path.
     */
    val relPath: String,
    val size: Long,
    val trashed: Boolean,
    /** While [trashed]: when the winning trash happened, the episode its trash entry is named for. */
    val trashedAt: Long? = null,
    /** While [trashed]: where the document was when it was trashed, which names its trash entry. */
    val trashedOrigPath: String? = null,
    val deleted: Boolean,
    /** The journaled `storagePath` intent, when one exists. */
    val intent: String?,
    /** Whether a file is present at [relPath] right now (identity is verified at execution). */
    val fileAtRelPath: Boolean,
    /** The `.npn/trash/` entry carrying this document's marker, when one exists locally. */
    val trashEntry: String?,
)

/** A local `.npn/trash/` entry. */
data class TrashFile(
    val mtimeMillis: Long,
    val size: Long,
)

data class LocalSnapshot(
    val documents: List<LocalDocument>,
    /** Canonical journal objects, snapshots and write-once internals present locally: path to size. */
    val metadataFiles: Map<String, Long>,
    val trashFiles: Map<String, TrashFile>,
    val nowMillis: Long,
    /**
     * Trash entries trashed longer ago than this are purged from the remote and not transferred;
     * null disables the purge. When an entry was trashed is the episode in its name
     * ([NpnPaths.trashEpisodeMillis]), never its mtime, which a move into trash keeps from the PDF.
     */
    val trashRetentionMillis: Long?,
    /**
     * What the journal says of every document a row or the remote names ([JournalDocView]). The
     * rows cache it and may lag; a destructive step on the remote waits for the journal to agree.
     */
    val journal: Map<String, JournalDocView> = emptyMap(),
    /**
     * When each document left the library for the trash or for good, null while it is in neither
     * ([SyncHooks.trashTimes]): a remote trash entry goes only once every document its name could
     * stand for left long enough ago, as a local one does.
     */
    val trashTimes: Map<String, Long?> = emptyMap(),
    /**
     * Whether this library moves documents to their journaled paths itself
     * ([dev.npn.core.fs.LibraryFileSystem.supportsDocumentRelocation]): the remote then follows the
     * library's names; one that cannot takes the remote's.
     */
    val relocates: Boolean = true,
)

/** One step of a plan: a [Local] one changes the library folder, a [Remote] one the remote. */
sealed interface SyncAction {
    /** A step that changes the library folder ([LocalApplier]). */
    sealed interface Local : SyncAction

    /** A step that changes the remote ([RemotePusher]). */
    sealed interface Remote : SyncAction

    /** Download the remote document and install it in the library at [targetRelPath] (uniquified if taken). */
    data class PublishDocument(
        val docId: String,
        val remotePath: String,
        val targetRelPath: String,
    ) : Local

    /** A live document whose bytes sit in the local trash: move them back instead of downloading. */
    data class RestoreLocalTrash(
        val docId: String,
        val trashEntry: String,
        val targetRelPath: String,
    ) : Local

    /**
     * The journal trashed this document and the remote no longer holds it live: park the local file
     * in the trash, under a name for the trash episode [episodeMillis] ([NpnPaths.trashPath]).
     */
    data class MoveLocalToTrash(
        val docId: String,
        val relPath: String,
        val episodeMillis: Long,
    ) : Local

    /**
     * The remote renamed a document the journal names no path for: give the library file the
     * remote's name, as a folder syncer delivers another device's rename. Done only while [to] is free.
     */
    data class MoveLocal(
        val docId: String,
        val from: String,
        val to: String,
        /** The remote copy whose name [to] is, whose manifest entry then records it. */
        val remotePath: String,
    ) : Local

    /**
     * Nothing moves: remember that the remote copy at [remotePath] and the library file at
     * [localPath] are one document's agreed names, the baseline later renames are told from.
     */
    data class RecordLocalPath(
        val docId: String,
        val remotePath: String,
        val localPath: String,
    ) : Local

    /** The journal deleted this document and every remote copy is gone: remove the local file too. */
    data class DeleteLocalFile(
        val docId: String,
        val relPath: String,
    ) : Local

    data class UploadDocument(
        val docId: String,
        val relPath: String,
        val remotePath: String,
        /**
         * Where the file is read from instead when it is not at [relPath]: its name before a
         * [MoveLocal] this pass planned to give it [relPath], which may not have happened.
         */
        val formerRelPath: String? = null,
    ) : Remote

    /** Server-side move, guarded by [expected] still describing [from] and [to] being free. */
    data class MoveRemote(
        val docId: String?,
        val from: String,
        val to: String,
        val expected: RemoteEntry,
        /** The library path the moved copy now mirrors, for its manifest entry. */
        val localPath: String? = null,
    ) : Remote

    /** Remote deletion, guarded by [expected] still describing [path]. */
    data class DeleteRemote(
        val docId: String?,
        val path: String,
        val expected: RemoteEntry,
    ) : Remote

    data class DownloadTrash(
        val entry: RemoteEntry,
    ) : Local

    data class UploadTrash(
        val relPath: String,
        /** The document the entry belongs to, when the marker and size identify exactly one. */
        val docId: String?,
    ) : Remote

    /** Download a journal object/snapshot ([hash] verified) or a write-once internal ([hash] null). */
    data class DownloadObject(
        val entry: RemoteEntry,
        val hash: String?,
    ) : SyncAction

    data class UploadObject(
        val relPath: String,
    ) : SyncAction
}

/**
 * The steps of one pass after metadata has been pulled and every remote document identified.
 * Metadata pull is planned separately ([SyncPlanner.metadataPull]) because it must run *before*
 * the local snapshot is read: the lifecycle the planner mirrors comes out of the fold.
 */
data class SyncPlan(
    val local: List<SyncAction.Local>,
    val remote: List<SyncAction.Remote>,
    val pushMetadata: List<SyncAction.UploadObject>,
    val conflicts: List<SyncConflict>,
) {
    val isEmpty: Boolean
        get() = local.isEmpty() && remote.isEmpty() && pushMetadata.isEmpty()
}

/**
 * Decides what one pass transfers, moves and removes. Pure: it reads the local snapshot, the
 * remote listing and the manifest (which by now names the document behind every remote PDF) and
 * emits [SyncAction]s; nothing here touches a filesystem or a network.
 *
 * The rules, in one place:
 * - Metadata (journal objects, snapshots, sidecars) is a set union: whatever one side lacks it
 *   receives. Nothing deletes a journal object, so the union only grows.
 * - A document's remote path follows its journaled `storagePath` intent, exactly as its local
 *   path does. For a document with no intent, the side that renamed it since the last pass wins
 *   ([ManifestEntry.localPath]), and a name made unique on the remote is the library's name too, so
 *   every replica ends with the same names.
 * - Trash and delete are the journal's decisions, and a remote copy is moved or deleted only while
 *   the journal (not just an index row) agrees. The remote mirrors them (move into `.npn/trash`,
 *   delete), and a replica applies the same file move to its own copy once the remote confirms the
 *   document is no longer live there. Without that mirror the next rescan would resurrect the file,
 *   because the indexer treats a file on disk as authoritative.
 * - Bytes a relink superseded (a bake's original) leave the remote, and are never installed.
 * - The remote trash is purged by the local trash's rule: by the journal's trash times, never by
 *   mtime, and never an entry no document accounts for.
 * - Paths collide case-insensitively, as on the consumer clouds that fold case.
 * - Nothing is ever deleted or replaced to resolve a conflict. An occupied target is reported and
 *   the bytes go to a uniquified name instead.
 */
class SyncPlanner(
    private val local: LocalSnapshot,
    private val remote: RemoteIndex,
    manifest: Collection<ManifestEntry>,
) {
    private val localById = local.documents.associateBy { it.docId }

    /** The manifest in path order, so every "first" below is deterministic whatever the DB returned. */
    private val sortedManifest = manifest.sortedBy { it.remotePath }

    private val manifestByPath: Map<String, ManifestEntry> = manifest.associateBy { it.remotePath }

    /** The listed remote paths of [kind] the manifest names a document for, as (document, path). */
    private fun identified(
        kind: ManifestKind,
        listed: Map<String, RemoteEntry>,
    ): List<Pair<String, String>> =
        sortedManifest.mapNotNull { entry ->
            entry.docId?.takeIf { entry.kind == kind && entry.remotePath in listed }?.let { it to entry.remotePath }
        }

    /** Remote document paths per known document id, listing-confirmed only. */
    private val remoteDocsById: Map<String, List<String>> =
        identified(ManifestKind.DOCUMENT, remote.documents).groupBy({ it.first }, { it.second })

    private val remoteTrashById: Map<String, List<String>> =
        identified(ManifestKind.TRASH, remote.trash).groupBy({ it.first }, { it.second })

    private val remoteDocIdAt: Map<String, String> =
        identified(ManifestKind.DOCUMENT, remote.documents).associate { (docId, path) -> path to docId }

    /**
     * Remote PDFs whose document could not be named this pass (a failed identification). Nothing
     * may be uploaded over them and nothing is installed from them; the next pass tries again.
     */
    private val unidentifiedRemote: Set<String> = remote.documents.keys - remoteDocIdAt.keys

    private val conflicts = ArrayList<SyncConflict>()
    private val localActions = ArrayList<SyncAction.Local>()
    private val remoteActions = ArrayList<SyncAction.Remote>()

    /** Remote paths this plan will occupy or vacate, so later steps do not collide with earlier ones. */
    private val claimedRemote = HashSet<String>()

    /** Remote paths this plan already deletes, so a second rule does not delete them again. */
    private val deletingRemote = HashSet<String>()

    /**
     * Every listed remote path, case-folded: OneDrive, Dropbox, Box and SMB shares fold case, so a
     * name that differs only in case is taken there, and planning it free would fail every pass.
     */
    private val remoteFolded: Map<String, List<String>> = remote.paths.groupBy { it.lowercase() }

    private val claimedFolded = HashSet<String>()

    /** The library paths this replica's documents hold (case-folded), which a new local name must avoid. */
    private val localTaken: MutableSet<String> =
        local.documents.filter { it.fileAtRelPath }.mapTo(HashSet()) { it.relPath.lowercase() }

    fun plan(): SyncPlan {
        planSupersededRemoteDocuments()
        for (doc in local.documents) {
            when {
                doc.deleted -> planDeleted(doc)
                doc.trashed -> planTrashed(doc)
                else -> planLive(doc)
            }
        }
        planUnknownRemoteDocuments()
        planTrashFiles()
        return SyncPlan(
            local = localActions,
            remote = remoteActions,
            pushMetadata = pushMetadata(),
            conflicts = conflicts,
        )
    }

    // ---- metadata --------------------------------------------------------------------------

    /**
     * What the remote lacks, and what it holds at another size: a write-once file is never
     * rewritten, so a different size is an upload cut short on a backend that writes in place, which
     * every other replica would otherwise fail to verify (an object) or keep truncated (a sidecar).
     */
    private fun pushMetadata(): List<SyncAction.UploadObject> =
        local.metadataFiles
            .filter { (path, size) -> remote[path]?.size != size }
            .keys
            .sorted()
            .map { SyncAction.UploadObject(it) }

    // ---- documents a relink superseded ---------------------------------------------------

    /**
     * A remote copy of bytes a relink superseded (a bake's original) is the old version of its
     * successor: it goes, as the bake removed it locally, and the successor takes its place by the
     * ordinary rules. Planned first, so the path it frees is free for the successor's upload, which
     * runs after it. Nothing ever installs superseded bytes ([planUnknownRemoteDocuments]).
     */
    private fun planSupersededRemoteDocuments() {
        for ((path, docId) in remoteDocIdAt) {
            if (local.journal[docId]?.supersededBy == null) continue
            deleteRemote(docId, path, remote.documents.getValue(path))
        }
    }

    // ---- live documents -------------------------------------------------------------------

    private fun planLive(doc: LocalDocument) {
        val journal = local.journal[doc.docId]
        // A row that lags the journal moves nothing: a trashed document's copy is not brought back.
        if (journal?.supersededBy != null || journal?.trashed == true) return
        if (journal?.deleted == true) {
            // An import brought deleted bytes back on this device without a record of it: every
            // other replica still has the document deleted and would delete an upload at once.
            conflicts += SyncConflict(doc.docId, doc.relPath, ConflictKind.KEPT_ON_THIS_DEVICE)
            return
        }
        val remotePaths = remoteDocsById[doc.docId].orEmpty().filter { it !in deletingRemote }
        // A row whose file went missing carries the `.npn/missing/` sentinel as its path; that is
        // no place to put a document, so a recovered copy lands at the intent or the remote's name.
        val target =
            doc.intent?.takeIf(NpnPaths::isDocumentPath)
                ?: doc.relPath.takeIf(NpnPaths::isDocumentPath)
                ?: remotePaths.firstOrNull()
                ?: return
        if (!doc.fileAtRelPath) {
            recoverLive(doc, target, remotePaths)
            return
        }
        // The library file is where this library put it: its indexer follows the journaled intent,
        // and names a document whose intent another one holds as it does on every replica.
        val here = doc.relPath
        if (remotePaths.isEmpty()) {
            // A restored document's bytes may still sit in the remote trash: moving them back is
            // the mirror of the local restore, and spares the upload.
            val trashCopy = remoteTrashById[doc.docId].orEmpty().firstOrNull()
            if (trashCopy != null && !isRemoteTaken(here, listOf(trashCopy))) {
                remoteActions +=
                    SyncAction.MoveRemote(
                        doc.docId,
                        trashCopy,
                        here,
                        remote.trash.getValue(trashCopy),
                        localPath = here,
                    )
                claim(here)
            } else {
                uploadTo(doc, here)
            }
            return
        }
        if (here in remotePaths) {
            if (doc.intent != null) deleteRemoteExtras(doc.docId, remotePaths - here)
            if (manifestByPath[here]?.localPath != here) {
                localActions += SyncAction.RecordLocalPath(doc.docId, here, here)
            }
            return
        }
        followRename(doc, remotePaths)
    }

    /**
     * Take the remote's name for the library file, as a folder syncer delivers another device's
     * rename; only while that name is free here.
     */
    private fun takeRemoteName(
        doc: LocalDocument,
        name: String,
    ) {
        if (name.lowercase() in localTaken) {
            conflicts += SyncConflict(doc.docId, name, ConflictKind.TARGET_OCCUPIED)
            return
        }
        localActions += SyncAction.MoveLocal(doc.docId, doc.relPath, name, name)
        localTaken += name.lowercase()
    }

    /**
     * The library calls a document one thing and the remote another. Whichever side renamed it since
     * the two last agreed wins, as a folder syncer carries a rename; the manifest remembers the
     * library's name at that moment ([ManifestEntry.localPath]):
     * - the library file moved since: renamed here (a user, or this library's indexer following the
     *   journaled path), so the remote follows;
     * - still where it was, or never agreed on: the remote's name is another device's newer choice,
     *   and the library takes it, unless this library's indexer places the document by its
     *   journaled path itself ([LocalSnapshot.relocates]): it would move the file back, so only
     *   the remote's name is remembered as agreed, and each side keeps its own.
     * Never agreed on and a device's own indexer naming the file differently is how two replicas end
     * with other names for one document; neither then moves it, so they never undo each other.
     */
    private fun followRename(
        doc: LocalDocument,
        remotePaths: List<String>,
    ) {
        val remotePath = doc.intent?.takeIf { it in remotePaths } ?: remotePaths.first()
        val agreed = manifestByPath[remotePath]?.localPath
        when {
            agreed != null && agreed != doc.relPath -> {
                relocateRemote(doc, doc.relPath, remotePaths)
            }

            doc.intent == null || !local.relocates -> {
                takeRemoteName(doc, remotePath)
            }

            agreed == null -> {
                localActions += SyncAction.RecordLocalPath(doc.docId, remotePath, doc.relPath)
            }
        }
    }

    /** The remote holds the document under another name and the journal says where it belongs. */
    private fun relocateRemote(
        doc: LocalDocument,
        target: String,
        remotePaths: List<String>,
    ) {
        if (isRemoteTaken(target, remotePaths)) {
            conflicts += SyncConflict(doc.docId, target, ConflictKind.TARGET_OCCUPIED)
            return
        }
        val from = remotePaths.first()
        remoteActions +=
            SyncAction.MoveRemote(doc.docId, from, target, remote.documents.getValue(from), localPath = target)
        claim(target)
        deleteRemoteExtras(doc.docId, remotePaths - from)
    }

    private fun recoverLive(
        doc: LocalDocument,
        target: String,
        remotePaths: List<String>,
    ) {
        when {
            doc.trashEntry != null -> {
                localActions += SyncAction.RestoreLocalTrash(doc.docId, doc.trashEntry, target)
            }

            remotePaths.isNotEmpty() -> {
                localActions +=
                    SyncAction.PublishDocument(doc.docId, remotePaths.first(), target)
            }
        }
    }

    private fun uploadTo(
        doc: LocalDocument,
        target: String,
    ) {
        if (target in unidentifiedRemote) {
            // The file there may well be this very document; uploading a uniquified duplicate
            // beside it would leave a copy no later pass cleans up. Wait for the identification.
            conflicts += SyncConflict(doc.docId, target, ConflictKind.UNIDENTIFIED)
            return
        }
        val path =
            if (!isRemoteTaken(target)) {
                target
            } else {
                // The new name is the library's too (below), so it must be free on both sides, or the
                // move here fails and the same upload is planned again every pass.
                uniquifyRemote(target, alsoFree = { doc.intent != null || it.lowercase() !in localTaken }).also {
                    conflicts += SyncConflict(doc.docId, target, ConflictKind.UPLOADED_AS_COPY, otherPath = it)
                }
            }
        if (doc.intent == null && path != doc.relPath) {
            // The remote's name for it is now another: the library file takes that name first (local
            // steps run before remote ones), so the document installed at its old name from the
            // remote, if one is, finds it free, and every replica ends with the same two names.
            localActions += SyncAction.MoveLocal(doc.docId, doc.relPath, path, path)
            localTaken += path.lowercase()
            remoteActions += SyncAction.UploadDocument(doc.docId, path, path, formerRelPath = doc.relPath)
        } else {
            remoteActions += SyncAction.UploadDocument(doc.docId, doc.relPath, path)
        }
        claim(path)
    }

    private fun deleteRemoteExtras(
        docId: String,
        extras: List<String>,
    ) {
        for (extra in extras) deleteRemote(docId, extra, remote.documents.getValue(extra))
    }

    private fun deleteRemote(
        docId: String?,
        path: String,
        entry: RemoteEntry,
    ) {
        if (!deletingRemote.add(path)) return
        remoteActions += SyncAction.DeleteRemote(docId, path, entry)
    }

    // ---- trashed documents ----------------------------------------------------------------

    private fun planTrashed(doc: LocalDocument) {
        // A row that lags the journal (a projection that failed) moves nothing until they agree.
        if (local.journal[doc.docId]?.trashed == false) return
        val episode = doc.trashedAt ?: local.nowMillis
        var remoteHasTrash = remoteTrashById[doc.docId].orEmpty().isNotEmpty()
        val liveCopies = remoteDocsById[doc.docId].orEmpty()
        for (path in liveCopies) {
            val entry = remote.documents.getValue(path)
            if (remoteHasTrash) {
                deleteRemote(doc.docId, path, entry)
            } else {
                val trashTarget = doc.trashEntry?.takeUnless { isRemoteTaken(it) } ?: remoteTrashTarget(doc, episode)
                remoteActions += SyncAction.MoveRemote(doc.docId, path, trashTarget, entry)
                claim(trashTarget)
                remoteHasTrash = true
            }
        }
        // The local file is parked only once the remote holds no live copy anywhere: the moves
        // above may still fail their guards, and a replica must not trash ahead of the remote.
        if (doc.fileAtRelPath && liveCopies.isEmpty()) {
            localActions += SyncAction.MoveLocalToTrash(doc.docId, doc.relPath, episode)
        }
    }

    /**
     * Where the remote's trash takes [doc]'s file: [NpnPaths.trashPath] for [episode], or the next
     * episode with a free name, as [dev.npn.core.fs.TrashOps.trashTarget] picks a local one.
     */
    private fun remoteTrashTarget(
        doc: LocalDocument,
        episode: Long,
    ): String {
        val name = NpnPaths.sanitizeFileName(NpnPaths.fileName(doc.trashedOrigPath ?: doc.relPath))
        var next = episode
        while (true) {
            val target = NpnPaths.trashPath(name, doc.docId, next)
            if (!isRemoteTaken(target)) return target
            next++
        }
    }

    // ---- deleted documents ----------------------------------------------------------------

    private fun planDeleted(doc: LocalDocument) {
        // The remote copies are every replica's; they go only while the journal has the document
        // deleted, not on a row that lags it.
        if (local.journal[doc.docId]?.deleted == false) return
        val liveCopies = remoteDocsById[doc.docId].orEmpty()
        for (path in liveCopies) deleteRemote(doc.docId, path, remote.documents.getValue(path))
        val trashCopies = remoteTrashById[doc.docId].orEmpty()
        for (path in trashCopies) deleteRemote(doc.docId, path, remote.trash.getValue(path))
        if (doc.fileAtRelPath && liveCopies.isEmpty()) {
            localActions += SyncAction.DeleteLocalFile(doc.docId, doc.relPath)
        }
        if (doc.trashEntry != null && trashCopies.isEmpty()) {
            localActions += SyncAction.DeleteLocalFile(doc.docId, doc.trashEntry)
        }
    }

    // ---- documents only the remote knows -------------------------------------------------

    /**
     * A remote document this replica has no row for is installed, unless the journal hides it: a
     * deleted or trashed document's file at a library path would read as the document back, and
     * bytes a relink superseded are never brought back. A copy the remote still holds of such a
     * document is the deleting device's to remove.
     */
    private fun planUnknownRemoteDocuments() {
        val published = HashSet<String>()
        for ((path, docId) in remoteDocIdAt) {
            if (installable(path, docId) && published.add(docId)) {
                localActions += SyncAction.PublishDocument(docId, path, path)
            }
        }
    }

    private fun installable(
        path: String,
        docId: String,
    ): Boolean {
        if (docId in localById || path in deletingRemote) return false
        val journal = local.journal[docId] ?: return true
        return !journal.deleted && !journal.trashed && journal.supersededBy == null
    }

    // ---- trash entries --------------------------------------------------------------------

    private fun planTrashFiles() {
        for ((path, entry) in remote.trash) {
            if (path !in local.trashFiles && transferable(path) && wantsTrashCopy(path)) {
                localActions += SyncAction.DownloadTrash(entry)
            }
        }
        for ((path, file) in local.trashFiles) {
            if (uploadable(path)) {
                val owner = docsForMarker(path).singleOrNull { it.size == file.size }
                remoteActions += SyncAction.UploadTrash(path, owner?.docId)
            }
        }
        purgeRemoteTrash()
    }

    /** A trash entry still worth carrying either way: within retention, and not one the purge would take. */
    private fun transferable(trashPath: String): Boolean = withinRetention(trashPath) && !expired(trashPath)

    /**
     * A local trash entry the remote lacks and should get: not one of a deleted document, and not
     * one the remote held once and lost since, which another device purged.
     */
    private fun uploadable(trashPath: String): Boolean =
        !isRemoteTaken(trashPath) &&
            transferable(trashPath) &&
            !markedDeleted(trashPath) &&
            manifestByPath[trashPath]?.kind != ManifestKind.TRASH

    /**
     * The remote trash is purged by the local trash's rule ([dev.npn.core.fs.TrashOps.purgeOlderThan]):
     * an entry goes once every document its name's hash prefix could stand for left the library
     * longer ago than the retention, and it was identified as one of them. An entry tied to no
     * document, or to one still live, unknown here, or trashed recently, stays however old it is.
     */
    private fun purgeRemoteTrash() {
        for ((path, entry) in remote.trash) {
            if (path in claimedRemote || !expired(path)) continue
            val identified = manifestByPath[path]?.takeIf { it.kind == ManifestKind.TRASH }?.docId
            if (identified != null && trashOwners(path).any { (doc, _) -> doc == identified }) {
                deleteRemote(identified, path, entry)
            }
        }
    }

    private val trashTimesByPrefix: Map<String, List<Map.Entry<String, Long?>>> =
        local.trashTimes.entries.groupBy { it.key.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) }

    /** The documents a trash entry's name could stand for, with when each left the library. */
    private fun trashOwners(trashPath: String): List<Map.Entry<String, Long?>> =
        NpnPaths.trashHashPrefix(NpnPaths.fileName(trashPath))?.let(trashTimesByPrefix::get).orEmpty()

    /**
     * Whether the trash entry at [trashPath] is past keeping by the purge's rule: its name's episode
     * is not recent, and every document its hash prefix could stand for left the library longer ago
     * than the retention. Such an entry is neither transferred nor kept on the remote, so one replica
     * never uploads what another purges.
     */
    private fun expired(trashPath: String): Boolean {
        val retention = local.trashRetentionMillis ?: return false
        val cutoff = local.nowMillis - retention
        val episode = NpnPaths.trashEpisodeMillis(NpnPaths.fileName(trashPath))
        if (episode != null && episode >= cutoff) return false
        val owners = trashOwners(trashPath)
        return owners.isNotEmpty() && owners.all { (_, left) -> left != null && left < cutoff }
    }

    /**
     * A trash copy is wanted unless its document is deleted, or this replica already holds the
     * bytes: at the live path (a live document, or a trashed one this pass parks itself) or in its
     * own trash entry.
     */
    private fun wantsTrashCopy(trashPath: String): Boolean {
        val docs = docsForMarker(trashPath)
        if (docs.isEmpty()) return true
        return docs.none { it.deleted || it.fileAtRelPath || it.trashEntry != null }
    }

    private fun markedDeleted(trashPath: String): Boolean = docsForMarker(trashPath).any { it.deleted }

    private fun docsForMarker(trashPath: String): List<LocalDocument> {
        val marker = NpnPaths.trashHashPrefix(NpnPaths.fileName(trashPath)) ?: return emptyList()
        return local.documents.filter { it.docId.startsWith(marker) }
    }

    /**
     * Whether the entry at [trashPath] is still worth transferring: trashed within the retention by
     * the episode its name carries. An older build's name carries none (or a `-N` collision suffix
     * that reads as an episode in 1970), and is transferred: nothing says it is old.
     */
    private fun withinRetention(trashPath: String): Boolean {
        val retention = local.trashRetentionMillis ?: return true
        val episode = NpnPaths.trashEpisodeMillis(NpnPaths.fileName(trashPath))
        if (episode == null || episode < PLAUSIBLE_EPISODE_MILLIS) return true
        return episode >= local.nowMillis - retention
    }

    // ---- helpers ---------------------------------------------------------------------------

    /**
     * Whether [path] is taken on the remote, case folded, by anything but [own] (the document's own
     * copies, which a case-only rename moves onto it) or a file this plan deletes.
     */
    private fun isRemoteTaken(
        path: String,
        own: Collection<String> = emptyList(),
    ): Boolean {
        val folded = path.lowercase()
        if (folded in claimedFolded) return true
        return remoteFolded[folded].orEmpty().any { it !in deletingRemote && it !in own }
    }

    private fun claim(path: String) {
        claimedRemote += path
        claimedFolded += path.lowercase()
    }

    private fun uniquifyRemote(
        path: String,
        alsoFree: (String) -> Boolean = { true },
    ): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        val (base, ext) = if (dot > slash + 1) path.substring(0, dot) to path.substring(dot) else path to ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (!isRemoteTaken(candidate) && alsoFree(candidate)) return candidate
            index++
        }
    }

    companion object {
        /** Before this, a trash name's "episode" is an older build's `-N` collision suffix. */
        private const val PLAUSIBLE_EPISODE_MILLIS = 946_684_800_000L

        /**
         * The metadata this device lacks: every remote journal object, snapshot and write-once
         * internal not present locally.
         */
        fun metadataPull(
            remote: RemoteIndex,
            localMetadataFiles: Collection<String>,
        ): List<SyncAction.DownloadObject> =
            remote.metadata.values
                .filter { it.path !in localMetadataFiles }
                .map { SyncAction.DownloadObject(it, remote.objectHashes[it.path]) }
    }
}
