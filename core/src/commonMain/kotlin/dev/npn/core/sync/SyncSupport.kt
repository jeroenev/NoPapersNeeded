package dev.npn.core.sync

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.SyncRemoteDao
import dev.npn.core.db.SyncRemoteEntryEntity
import dev.npn.core.util.reason
import kotlinx.coroutines.CancellationException

/**
 * What the sync engine needs from the session it runs inside. Kept as a seam so the engine never
 * holds the session: the fold is read through the coordinator, and a rescan is the session's own
 * (it also re-selects sidecars and queues OCR for what arrived).
 */
interface SyncHooks {
    /**
     * Whether the journal takes writes and folds whole. While it does not, the index rows are not
     * the journal's answer, so a pass moves no document on either side.
     */
    suspend fun journalReady(): Boolean

    /** What the journal says of each of [docIds] ([JournalDocView]), read from the live fold. */
    suspend fun journalView(docIds: Collection<String>): Map<String, JournalDocView>

    /** Journal objects or sidecars were published locally: fold them in and re-select sidecars. */
    suspend fun metadataArrived()

    /** PDFs were published, moved or removed locally: rescan so the index and path intents catch up. */
    suspend fun documentsChanged()

    /**
     * The library's trash retention in days, 0 purging at the next chance; null while the journal
     * holds one this build cannot read, which purges nothing.
     */
    suspend fun trashRetentionDays(): Int?

    /**
     * When each document the journal or the index knows left the library for the trash or for good,
     * null while it is in neither: what the local trash purge decides by
     * ([dev.npn.core.fs.TrashOps.purgeOlderThan]), and so the remote's too.
     */
    suspend fun trashTimes(): Map<String, Long?>

    /**
     * Run [block] under the journal's writer lock, so what it checks of the journal ([SyncJournal])
     * still holds when it moves or removes a file: a restore, an import or a delete the user makes
     * meanwhile waits for it, and is not undone by it.
     */
    suspend fun <T> serialized(block: suspend (SyncJournal) -> T): T
}

/** The journal's lifecycle answers, for a step that holds the writer lock ([SyncHooks.serialized]). */
interface SyncJournal {
    /** Deleted, unless an import brought it back in this session unrecorded. */
    suspend fun isDeleted(docId: String): Boolean

    suspend fun isTrashed(docId: String): Boolean

    /** Shown by the library: neither hidden nor superseded by a relink. */
    suspend fun isCurrent(docId: String): Boolean

    /** The document [docId]'s bytes became, its relink chain followed; [docId] when none superseded it. */
    suspend fun successorOf(docId: String): String
}

/** One document as the journal's fold has it, for planning. */
data class JournalDocView(
    /** Where its file is meant to be; null for no storage path, and for a document not shown. */
    val intendedPath: String?,
    /** The fold has it deleted, whatever an unrecorded import made of it on this device. */
    val deleted: Boolean,
    val trashed: Boolean,
    /** The document a relink made of it (a bake's result), its chain followed; null when none did. */
    val supersededBy: String? = null,
)

/**
 * The persisted remote manifest ([SyncRemoteEntryEntity]) with an in-memory mirror for the pass.
 * Writes go through immediately: a pass killed halfway keeps every identity it established.
 */
class SyncManifest(
    private val dao: SyncRemoteDao,
    private val kv: KeyValueDao,
) {
    private val cache = LinkedHashMap<String, ManifestEntry>()

    val entries: Collection<ManifestEntry> get() = cache.values

    operator fun get(remotePath: String): ManifestEntry? = cache[remotePath]

    /**
     * Load the manifest for [remoteId], clearing it first when the last pass targeted a different
     * remote: identities established against one remote say nothing about another.
     */
    suspend fun load(remoteId: String) {
        if (kv.get(KEY_REMOTE) != remoteId) {
            dao.clear()
            kv.put(KEY_REMOTE, remoteId)
        }
        cache.clear()
        for (row in dao.all()) cache[row.remotePath] = row.toEntry()
    }

    /** Whether a pass already found [remoteId] to be this library's own ([bind]). */
    suspend fun isBound(remoteId: String): Boolean = kv.get(KEY_BOUND) == remoteId

    /**
     * Remember that [remoteId] holds this library, once a pass found it so: later passes trust it,
     * so a library that gains documents before its first pass completes is not refused its own remote.
     */
    suspend fun bind(remoteId: String) {
        if (!isBound(remoteId)) kv.put(KEY_BOUND, remoteId)
    }

    /**
     * Forget entries none of [listedPaths] names (the listing, and what the caller keeps known). A
     * missing file is unknown, never "deleted locally".
     */
    suspend fun prune(listedPaths: Set<String>) {
        val stale = cache.keys.filter { it !in listedPaths }
        if (stale.isEmpty()) return
        dao.deleteByPaths(stale)
        stale.forEach(cache::remove)
    }

    suspend fun record(
        entry: ManifestEntry,
        nowMillis: Long,
    ) {
        cache[entry.remotePath] = entry
        dao.upsertAll(listOf(entry.toEntity(nowMillis)))
    }

    /** Remember that the copy at [remotePath] now mirrors the library file at [localPath]. */
    suspend fun recordLocalPath(
        remotePath: String,
        localPath: String,
        nowMillis: Long,
    ) {
        val entry = cache[remotePath] ?: return
        if (entry.localPath != localPath) record(entry.copy(localPath = localPath), nowMillis)
    }

    suspend fun remove(remotePath: String) {
        if (cache.remove(remotePath) != null) dao.deleteByPaths(listOf(remotePath))
    }

    private fun SyncRemoteEntryEntity.toEntry(): ManifestEntry =
        ManifestEntry(
            remotePath = remotePath,
            kind = if (kind == KIND_TRASH) ManifestKind.TRASH else ManifestKind.DOCUMENT,
            size = size,
            mtimeMillis = mtime,
            docId = docId,
            localPath = localPath,
        )

    private fun ManifestEntry.toEntity(nowMillis: Long): SyncRemoteEntryEntity =
        SyncRemoteEntryEntity(
            remotePath = remotePath,
            kind = if (kind == ManifestKind.TRASH) KIND_TRASH else KIND_DOCUMENT,
            size = size,
            mtime = mtimeMillis,
            docId = docId,
            syncedAt = nowMillis,
            localPath = localPath,
        )

    companion object {
        const val KEY_REMOTE = "sync.remote"
        const val KEY_LAST_RUN = "sync.lastRun"

        /** The remote a pass last found to hold this library ([bind]). */
        const val KEY_BOUND = "sync.bound"
        private const val KIND_DOCUMENT = "document"
        private const val KIND_TRASH = "trash"
    }
}

/** Mutable accumulator for one pass's [SyncReport]. */
class SyncReportBuilder(
    private val startedAt: Long,
    /** Told of every item failure as it is recorded: the session's log. */
    private val onError: (path: String, cause: Throwable) -> Unit = { _, _ -> },
) {
    var downloadedDocuments = 0
    var uploadedDocuments = 0
    var downloadedObjects = 0
    var uploadedObjects = 0

    /** Trash entries downloaded by the local half, which the plan's downloads count. */
    var downloadedTrash = 0
    var movedRemote = 0
    var deletedRemote = 0
    var appliedLocally = 0
    val conflicts = ArrayList<SyncConflict>()
    val errors = ArrayList<SyncError>()

    fun error(
        path: String,
        cause: Throwable,
    ) {
        errors += SyncError(path, cause.reason())
        onError(path, cause)
    }

    fun build(
        nowMillis: Long,
        plan: SyncPlan? = null,
        planCheckedAt: Long? = null,
    ): SyncReport {
        // What the plan found, less what this pass finished: the rest waits for the next one. A
        // count is never negative (a plan may be re-checked after a retry inside a transfer).
        val pendingUploads =
            ((plan?.plannedUploads ?: 0) - uploadedDocuments - uploadedObjects).coerceAtLeast(0)
        val pendingDownloads =
            ((plan?.plannedDownloads ?: 0) - downloadedDocuments - downloadedTrash).coerceAtLeast(0)
        return SyncReport(
            startedAt = startedAt,
            durationMillis = nowMillis - startedAt,
            downloadedDocuments = downloadedDocuments,
            uploadedDocuments = uploadedDocuments,
            downloadedObjects = downloadedObjects,
            uploadedObjects = uploadedObjects,
            movedRemote = movedRemote,
            deletedRemote = deletedRemote,
            appliedLocally = appliedLocally,
            pendingUploads = pendingUploads,
            pendingDownloads = pendingDownloads,
            pendingCheckedAt = plan?.let { planCheckedAt },
            conflicts = conflicts.toList(),
            errors = errors.toList(),
        )
    }
}

/**
 * Run one item's work, recording a failure against [path] instead of aborting the pass.
 * Cancellation is never swallowed: an interrupted pass leaves whatever it did not reach for the
 * next one, which is exactly what a report cannot express.
 */
@Suppress("TooGenericExceptionCaught")
internal inline fun <T> SyncReportBuilder.attempt(
    path: String,
    block: () -> T,
): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        error(path, failure)
        null
    }
