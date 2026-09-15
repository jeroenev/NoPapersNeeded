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
    /** Canonical journal objects, snapshots and write-once internals present locally, by path. */
    val metadataFiles: Set<String>,
    val trashFiles: Map<String, TrashFile>,
    val nowMillis: Long,
    /**
     * Trash entries trashed longer ago than this are purged from the remote and not transferred;
     * null disables the purge. When an entry was trashed is the episode in its name
     * ([NpnPaths.trashEpisodeMillis]), not its mtime, which a move into trash keeps from the PDF.
     */
    val trashRetentionMillis: Long?,
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

    /** The journal deleted this document and every remote copy is gone: remove the local file too. */
    data class DeleteLocalFile(
        val docId: String,
        val relPath: String,
    ) : Local

    data class UploadDocument(
        val docId: String,
        val relPath: String,
        val remotePath: String,
    ) : Remote

    /** Server-side move, guarded by [expected] still describing [from] and [to] being free. */
    data class MoveRemote(
        val docId: String?,
        val from: String,
        val to: String,
        val expected: RemoteEntry,
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
 *   path does. A document with no intent is never moved on the remote; its uploaded name stands.
 * - Trash and delete are the journal's decisions. The remote mirrors them (move into `.npn/trash`,
 *   delete), and a replica applies the same file move to its own copy once the remote confirms the
 *   document is no longer live there. Without that mirror the next rescan would resurrect the file,
 *   because the indexer treats a file on disk as authoritative.
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

    fun plan(): SyncPlan {
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

    private fun pushMetadata(): List<SyncAction.UploadObject> =
        local.metadataFiles
            .filter { !remote.has(it) }
            .sorted()
            .map { SyncAction.UploadObject(it) }

    // ---- live documents -------------------------------------------------------------------

    private fun planLive(doc: LocalDocument) {
        val remotePaths = remoteDocsById[doc.docId].orEmpty()
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
        if (remotePaths.isEmpty()) {
            // A restored document's bytes may still sit in the remote trash: moving them back is
            // the mirror of the local restore, and spares the upload.
            val trashCopy = remoteTrashById[doc.docId].orEmpty().firstOrNull()
            if (trashCopy != null && !isRemoteTaken(target)) {
                remoteActions += SyncAction.MoveRemote(doc.docId, trashCopy, target, remote.trash.getValue(trashCopy))
                claimedRemote += target
            } else {
                uploadTo(doc, target)
            }
            return
        }
        if (target in remotePaths) {
            if (doc.intent != null) deleteRemoteExtras(doc.docId, remotePaths - target)
        } else if (doc.intent != null) {
            relocateRemote(doc, target, remotePaths)
        }
        // With no journaled wish the remote's own name stands: a replica that merely uploaded under
        // its local name has no say over what the remote calls the document.
    }

    /** The remote holds the document under another name and the journal says where it belongs. */
    private fun relocateRemote(
        doc: LocalDocument,
        target: String,
        remotePaths: List<String>,
    ) {
        if (isRemoteTaken(target)) {
            conflicts += SyncConflict(doc.docId, target, "Intended path is occupied on the remote by another file")
            return
        }
        val from = remotePaths.first()
        remoteActions += SyncAction.MoveRemote(doc.docId, from, target, remote.documents.getValue(from))
        claimedRemote += target
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
            conflicts += SyncConflict(doc.docId, target, "Remote file at this path was not identified; not uploaded")
            return
        }
        val path =
            if (!isRemoteTaken(target)) {
                target
            } else {
                conflicts += SyncConflict(doc.docId, target, "Path is occupied on the remote by another file")
                uniquifyRemote(target)
            }
        remoteActions += SyncAction.UploadDocument(doc.docId, doc.relPath, path)
        claimedRemote += path
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
        val episode = doc.trashedAt ?: local.nowMillis
        var remoteHasTrash = remoteTrashById[doc.docId].orEmpty().isNotEmpty()
        val liveCopies = remoteDocsById[doc.docId].orEmpty()
        for (path in liveCopies) {
            val entry = remote.documents.getValue(path)
            if (remoteHasTrash) {
                deleteRemote(doc.docId, path, entry)
            } else {
                val trashTarget = doc.trashEntry?.takeUnless(::isRemoteTaken) ?: remoteTrashTarget(doc, episode)
                remoteActions += SyncAction.MoveRemote(doc.docId, path, trashTarget, entry)
                claimedRemote += trashTarget
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

    private fun planUnknownRemoteDocuments() {
        val published = HashSet<String>()
        for ((path, docId) in remoteDocIdAt) {
            if (docId in localById || !published.add(docId)) continue
            localActions += SyncAction.PublishDocument(docId, path, path)
        }
    }

    // ---- trash entries --------------------------------------------------------------------

    private fun planTrashFiles() {
        for ((path, entry) in remote.trash) {
            if (path in local.trashFiles || !withinRetention(path, entry.mtimeMillis) || !wantsTrashCopy(path)) continue
            localActions += SyncAction.DownloadTrash(entry)
        }
        for ((path, file) in local.trashFiles) {
            if (isRemoteTaken(path) || !withinRetention(path, file.mtimeMillis) || markedDeleted(path)) continue
            val owner = docsForMarker(path).singleOrNull { it.size == file.size }
            remoteActions += SyncAction.UploadTrash(path, owner?.docId)
        }
        val retention = local.trashRetentionMillis ?: return
        for ((path, entry) in remote.trash) {
            if (trashedAt(path, entry.mtimeMillis) in 1..(local.nowMillis - retention) && path !in claimedRemote) {
                deleteRemote(null, path, entry)
            }
        }
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

    private fun withinRetention(
        trashPath: String,
        mtimeMillis: Long,
    ): Boolean {
        val retention = local.trashRetentionMillis ?: return true
        return trashedAt(trashPath, mtimeMillis) >= local.nowMillis - retention
    }

    /**
     * When the entry at [trashPath] was trashed: the episode its name carries. An older build's name
     * has none, and only its [mtimeMillis] is left to go by.
     */
    private fun trashedAt(
        trashPath: String,
        mtimeMillis: Long,
    ): Long = NpnPaths.trashEpisodeMillis(NpnPaths.fileName(trashPath)) ?: mtimeMillis

    // ---- helpers ---------------------------------------------------------------------------

    private fun isRemoteTaken(path: String): Boolean = remote.has(path) || path in claimedRemote

    private fun uniquifyRemote(path: String): String {
        val slash = path.lastIndexOf('/')
        val dot = path.lastIndexOf('.')
        val (base, ext) = if (dot > slash + 1) path.substring(0, dot) to path.substring(dot) else path to ""
        var index = 2
        while (true) {
            val candidate = "$base ($index)$ext"
            if (!isRemoteTaken(candidate)) return candidate
            index++
        }
    }

    companion object {
        /**
         * The metadata this device lacks: every remote journal object, snapshot and write-once
         * internal not present locally.
         */
        fun metadataPull(
            remote: RemoteIndex,
            localMetadataFiles: Set<String>,
        ): List<SyncAction.DownloadObject> =
            remote.metadata.values
                .filter { it.path !in localMetadataFiles }
                .map { SyncAction.DownloadObject(it, remote.objectHashes[it.path]) }
    }
}
