package dev.npn.core.sync

import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One sync pass between the library and a remote, in the order that keeps replicas from undoing
 * each other:
 *
 * 1. list the remote (a failed listing fails the pass; a missing file is *unknown*, never deleted);
 * 2. pull metadata the fold lacks and fold it, so the lifecycle mirrored below is the journal's;
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
) {
    private val manifest = SyncManifest(deviceDb.syncRemoteDao(), deviceDb.keyValueDao())
    private val reader = LocalSnapshotReader(fs, db.documentDao(), hooks)

    suspend fun run(
        store: RemoteStore,
        remoteId: String,
        onProgress: (SyncProgress) -> Unit,
    ): SyncReport {
        val startedAt = clock.nowMillis()
        val report = SyncReportBuilder(startedAt)
        staging.clear()

        onProgress(SyncProgress(SyncPhase.LISTING))
        val remote = RemoteIndex(store.list())
        manifest.load(remoteId)
        manifest.prune(remote.documents.keys + remote.trash.keys)

        val metadata = MetadataTransfer(fs, staging, store, installationId, ids)
        val pull = SyncPlanner.metadataPull(remote, reader.metadataFiles())
        val arrived =
            metadata.pull(
                pull,
                report,
            ) { done, total -> onProgress(SyncProgress(SyncPhase.PULL_METADATA, done, total)) }
        if (arrived > 0) report.attempt("metadata") { hooks.metadataArrived() }

        val resolver = DocumentResolver(store, staging, manifest)
        try {
            val rows = db.documentDao().getAllRows()
            resolver.resolve(remote, rows, startedAt, report) { done, total ->
                onProgress(SyncProgress(SyncPhase.RESOLVE_DOCUMENTS, done, total))
            }

            val plan = SyncPlanner(reader.read(startedAt), remote, manifest.entries).plan()
            report.conflicts += plan.conflicts

            val applier = LocalApplier(fs, staging, store, resolver.downloads, hooks)
            val changed =
                applier.apply(
                    plan.local,
                    report,
                ) { done, total -> onProgress(SyncProgress(SyncPhase.APPLY_LOCAL, done, total)) }

            RemotePusher(fs, staging, store, manifest, startedAt).push(plan.remote, report) { done, total ->
                onProgress(SyncProgress(SyncPhase.PUSH_DOCUMENTS, done, total))
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
        return report.build(clock.nowMillis())
    }
}
