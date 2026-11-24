package dev.npn.core.sync

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One sync pass between the library and a remote, in the order that keeps replicas from undoing
 * each other:
 *
 * 1. list the remote (a failed listing fails the pass; a missing file is *unknown*, never deleted),
 *    and refuse a folder that holds no library but other files, or another library ([checkLibrary]);
 * 2. pull metadata the fold lacks and fold it, so the lifecycle mirrored below is the journal's; a
 *    journal that does not take writes ends the pass here, and one whose pull or fold failed in part
 *    moves no document this pass;
 * 3. identify every remote PDF ([DocumentResolver]);
 * 4. plan ([SyncPlanner]) and apply the local half — install new documents, mirror trash/delete;
 * 5. push documents — uploads, moves toward journaled intents, trash/delete mirrors;
 * 6. push metadata last, so a replica that sees this device's journal event always finds the
 *    remote file state that goes with it already in place;
 * 7. rescan if anything landed in the library folder.
 *
 * The engine holds no session state between passes beyond the persisted manifest, which lives in
 * [deviceDb] so that rebuilding the index does not cost identifying the whole remote again.
 */
class SyncEngine(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    deviceDb: DeviceDatabase,
    private val staging: LocalStaging,
    private val installationId: String,
    private val hooks: SyncHooks,
    private val clock: EpochClock,
    private val ids: IdSource = IdSource.Random,
    /** Where each item that failed is logged, beside the report the settings show. */
    private val log: NpnLog = NpnLog.None,
) {
    private val manifest = SyncManifest(deviceDb.syncRemoteDao(), deviceDb.keyValueDao())
    private val reader = LocalSnapshotReader(fs, db.documentDao(), hooks)

    suspend fun run(
        store: RemoteStore,
        remoteId: String,
        onProgress: (SyncProgress) -> Unit,
    ): SyncReport {
        val startedAt = clock.nowMillis()
        val report = SyncReportBuilder(startedAt) { path, cause -> log.warn("Cloud sync: $path", cause) }
        staging.clear()

        onProgress(SyncProgress(SyncPhase.LISTING))
        val remote = RemoteIndex(store.list())
        manifest.load(remoteId)
        val localMetadata = reader.metadataFiles()
        if (!manifest.isBound(remoteId)) {
            checkLibrary(remote, localMetadata.keys)
            manifest.bind(remoteId)
        }
        // A trash entry this device knew on the remote stays known while it is in the local trash,
        // even once gone from the remote: another device purged it, and it must not go up again.
        manifest.prune(remote.documents.keys + remote.trash.keys + reader.trashPaths())

        val metadata = MetadataTransfer(fs, staging, store, installationId, ids)
        val pull = SyncPlanner.metadataPull(remote, localMetadata.keys)
        val errorsBefore = report.errors.size
        val arrived =
            metadata.pull(
                pull,
                report,
            ) { done, total -> onProgress(SyncProgress(SyncPhase.PULL_METADATA, done, total)) }
        if (arrived > 0) report.attempt("metadata") { hooks.metadataArrived() }
        if (!hooks.journalReady()) throw SyncRefusedException(SyncFailure.JOURNAL_BLOCKED)
        // A pull or a fold that failed in part leaves the fold short of events another device
        // wrote: what it would plan could undo that device's trash or delete. No rescan runs
        // between the fold and the mirror below: one would see a file another device trashed still
        // at its path, and take it for the document come back.
        val documentsSafe = report.errors.size == errorsBefore

        val resolver = DocumentResolver(fs, staging = staging, store = store, manifest = manifest)
        // The last plan made, for the report's pending counts; null when the pass never planned.
        var planned: SyncPlan? = null
        var planCheckedAt: Long? = null
        try {
            val rows = db.documentDao().getAllRows()
            resolver.resolve(remote, rows, startedAt, report) { done, total ->
                onProgress(SyncProgress(SyncPhase.RESOLVE_DOCUMENTS, done, total))
            }

            val remoteDocIds = manifest.entries.mapNotNull { it.docId }
            val plan = SyncPlanner(reader.read(startedAt, remoteDocIds), remote, manifest.entries).plan()
            planned = plan
            planCheckedAt = clock.nowMillis()

            var changed = 0
            if (documentsSafe) {
                report.conflicts += plan.conflicts
                val applier = LocalApplier(fs, staging, store, resolver.downloads, hooks, manifest, startedAt)
                changed =
                    applier.apply(
                        plan.local,
                        report,
                    ) { done, total -> onProgress(SyncProgress(SyncPhase.APPLY_LOCAL, done, total)) }

                RemotePusher(fs, staging, store, manifest, startedAt).push(plan.remote, report) { done, total ->
                    onProgress(SyncProgress(SyncPhase.PUSH_DOCUMENTS, done, total))
                }
            }
            metadata.push(
                plan.pushMetadata,
                report,
            ) { done, total -> onProgress(SyncProgress(SyncPhase.PUSH_METADATA, done, total)) }

            if (changed > 0) {
                onProgress(SyncProgress(SyncPhase.INDEXING))
                report.attempt("index") { hooks.documentsChanged() }
            }
        } finally {
            withContext(NonCancellable) { resolver.cleanup() }
        }
        return report.build(clock.nowMillis(), planned, planCheckedAt)
    }

    /**
     * Refuse a remote folder this library must not be mirrored into, before anything transfers,
     * while this library has documents of its own:
     * - one with files but no library ([SyncFailure.NOT_A_LIBRARY]): a drive's root or someone's
     *   Documents, whose every PDF the pass would otherwise take into the library;
     * - another library ([SyncFailure.OTHER_LIBRARY]): its journal shares no object with this one's,
     *   so the two would merge for good, as journal objects are never deleted.
     *
     * A library with no documents yet takes either in: that is a new device joining a library, or
     * a folder of PDFs made a library. A remote this library synced with before (the manifest names
     * files on it, or the two journals share an object) is its own. Once a pass found the remote to
     * be this library's, it is bound ([SyncManifest.bind]) and not checked again.
     */
    private suspend fun checkLibrary(
        remote: RemoteIndex,
        localMetadata: Set<String>,
    ) {
        if (manifest.entries.isNotEmpty()) return
        val remoteObjects = remote.objectHashes.keys
        if (remoteObjects.any { it in localMetadata }) return
        val holdsDocuments = remote.documents.isNotEmpty() || remote.trash.isNotEmpty()
        if (remoteObjects.isEmpty() && !holdsDocuments) return
        if (db.documentDao().getAllRows().isEmpty()) return
        throw SyncRefusedException(
            if (remoteObjects.isEmpty()) SyncFailure.NOT_A_LIBRARY else SyncFailure.OTHER_LIBRARY,
        )
    }
}
