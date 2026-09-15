package dev.npn.core.sync

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.SyncRemoteDao
import dev.npn.core.db.SyncRemoteEntryEntity
import kotlinx.coroutines.CancellationException

/**
 * What the sync engine needs from the session it runs inside. Kept as a seam so the engine never
 * holds the session: the fold is read through the coordinator, and a rescan is the session's own
 * (it also re-selects sidecars and queues OCR for what arrived).
 */
interface SyncHooks {
    /**
     * Where the journal wants each document's file ([dev.npn.core.journal.FoldedState.intendedPath]):
     * null for a document with no storage path, and for one the library does not show.
     */
    suspend fun intendedPaths(docIds: Collection<String>): Map<String, String?>

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
     * Delete [relPath], a copy of the deleted document [docId], under the journal's writer lock and
     * only while the journal still has [docId] deleted and the file still holds its bytes: an import
     * that brought the bytes back since the pass planned keeps them. Returns whether it deleted.
     */
    suspend fun deleteDeletedCopy(
        docId: String,
        relPath: String,
    ): Boolean
}

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

    /** Forget entries the listing no longer shows. A missing file is unknown, never "deleted locally". */
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
        )

    private fun ManifestEntry.toEntity(nowMillis: Long): SyncRemoteEntryEntity =
        SyncRemoteEntryEntity(
            remotePath = remotePath,
            kind = if (kind == ManifestKind.TRASH) KIND_TRASH else KIND_DOCUMENT,
            size = size,
            mtime = mtimeMillis,
            docId = docId,
            syncedAt = nowMillis,
        )

    companion object {
        const val KEY_REMOTE = "sync.remote"
        const val KEY_LAST_RUN = "sync.lastRun"
        private const val KIND_DOCUMENT = "document"
        private const val KIND_TRASH = "trash"
    }
}

/** Mutable accumulator for one pass's [SyncReport]. */
class SyncReportBuilder(
    private val startedAt: Long,
) {
    var downloadedDocuments = 0
    var uploadedDocuments = 0
    var downloadedObjects = 0
    var uploadedObjects = 0
    var movedRemote = 0
    var deletedRemote = 0
    var appliedLocally = 0
    val conflicts = ArrayList<SyncConflict>()
    val errors = ArrayList<SyncError>()

    fun error(
        path: String,
        cause: Throwable,
    ) {
        errors += SyncError(path, cause.message ?: cause::class.simpleName ?: "failed")
    }

    fun build(nowMillis: Long): SyncReport =
        SyncReport(
            startedAt = startedAt,
            durationMillis = nowMillis - startedAt,
            downloadedDocuments = downloadedDocuments,
            uploadedDocuments = uploadedDocuments,
            downloadedObjects = downloadedObjects,
            uploadedObjects = uploadedObjects,
            movedRemote = movedRemote,
            deletedRemote = deletedRemote,
            appliedLocally = appliedLocally,
            conflicts = conflicts.toList(),
            errors = errors.toList(),
        )
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
