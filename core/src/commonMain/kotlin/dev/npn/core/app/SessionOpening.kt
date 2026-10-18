package dev.npn.core.app

import dev.npn.core.ai.RoomChatHistoryProvider
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.defs
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.Compaction
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.SnapshotSupport
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// How a session opens: [openLibrarySession], then the steps it takes, in the order it takes them.
// [LibrarySessionImpl.openSession] is the way in from outside core.

/**
 * Boot a session over [deps]: move device state an older index still holds into the device
 * database, settle the device id ([DeviceIdentity]: a fresh one for a restored or cloned
 * installation), wire the journal engine, repositories, pipelines, then do the initial fold so
 * [LibrarySessionImpl.documents] is populated before the caller's initial rescan. A deleted
 * document's return is recorded as a `doc-undelete` when [writeUndelete].
 *
 * Refuses, with [VerifiedPublicationUnavailableException], a library on a filesystem that cannot
 * publish without replacement: there is no other journal format to write. Every open publishes
 * through the same primitive before it writes anything ([DeviceIdentity]: this installation's
 * marker, or a throwaway object once that is there), so each one finds out.
 */
internal suspend fun openLibrarySession(
    deps: SessionDeps,
    writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
): LibrarySessionImpl {
    val lifetime = SessionLifetime(deps.scope)
    var coordinator: JournalCoordinator? = null
    var opened = false
    try {
        // Before anything reads the device id: an index from before device.db holds it.
        moveDeviceState(deps)
        val indexer = Indexer(deps.fs, deps.db, deps.clock, deps.log)
        val journal = createCoordinator(deps, indexer, writeUndelete).also { coordinator = it }
        return wire(deps, lifetime, indexer, journal).also { opened = true }
    } finally {
        // Nothing a failed open started may outlive it (a cancelled open included): stop
        // whatever runs on the lifetime, while the failure goes on to the caller.
        if (!opened) {
            withContext(NonCancellable) {
                lifetime.close()
                coordinator?.close()
            }
        }
    }
}

/** The session [openLibrarySession] opens over its [coordinator], from the device id on. */
private suspend fun wire(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    indexer: Indexer,
    coordinator: JournalCoordinator,
): LibrarySessionImpl {
    val deviceId = DeviceIdentity(deps.deviceDb.keyValueDao(), deps.fs, deps.installationId, deps.log).resolve()
    foldJournal(deps, coordinator)
    val graph = SessionGraph(deps, lifetime, coordinator, indexer, deviceId)
    graph.prepare()
    return LibrarySessionImpl(deps, graph)
}

/**
 * The `kv` keys an index from before [DeviceDatabase] kept for this device. The v1 writer's
 * bookkeeping moves too, so the index sheds it; [DeviceIdentity] then drops it.
 */
private val DEVICE_KEYS = listOf(DeviceIdentity.KEY_DEVICE_ID) + DeviceIdentity.LEGACY_JOURNAL_KEYS

/** Move what an index from before [DeviceDatabase] kept for this device into the device database. */
private suspend fun moveDeviceState(deps: SessionDeps) =
    LegacyIndexTables.moveDeviceState(deps.db, deps.deviceDb, DEVICE_KEYS, RoomChatHistoryProvider.KEY_PREFIX)

/**
 * The journal writer: storage-v2 content-addressed objects are the only format this build writes
 * (v1 segments are still read). A filesystem that cannot publish without replacement throws
 * [VerifiedPublicationUnavailableException] at its first publication, which [DeviceIdentity] makes
 * before anything else is written, rather than be written in a weaker format.
 */
private suspend fun createObjectWriter(deps: SessionDeps): ContentAddressedJournalWriter {
    val state = deps.db.metadataProjectionDao().state()
    return ContentAddressedJournalWriter(
        fs = deps.fs,
        installationId = deps.installationId,
        allocator =
            HlcWatermarkAllocator(
                clock = deps.clock,
                initialPhysicalMillis = state?.allocatorPhysical ?: 0L,
                initialCounter = state?.allocatorCounter ?: 0,
            ),
    )
}

private suspend fun createCoordinator(
    deps: SessionDeps,
    indexer: Indexer,
    writeUndelete: Boolean,
): JournalCoordinator {
    val snapshots = SnapshotSupport(deps.fs, deps.db.metadataProjectionDao())
    return JournalCoordinator(
        writer = createObjectWriter(deps),
        indexer = indexer,
        accumulator = MetadataAccumulator(deps.fs, deps.db, snapshots),
        compaction = Compaction(SnapshotCompactor(deps.fs, deps.installationId), snapshots),
        clock = deps.clock,
        log = deps.log,
        writeUndelete = writeUndelete,
    )
}

/**
 * Fold the journal, so the documents are there before the caller's initial rescan, and bring the
 * library's own data up to what this build expects: the seeded fields (with the per-document flag
 * an older build may have dropped since), and the library settings this device kept before they
 * became library data.
 *
 * A new library's first seeding is part of opening it: without it the library has no fields. Every
 * other step is an [upgradeAtOpen].
 */
private suspend fun foldJournal(
    deps: SessionDeps,
    coordinator: JournalCoordinator,
) {
    coordinator.refreshState()
    val seeds = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) }
    if (coordinator.setting(FieldKeys.SEEDED_SETTING) == null) {
        coordinator.seedFieldRegistryIfNeeded(seeds)
    } else {
        deps.upgradeAtOpen("Upgrading the seeded field definitions") { coordinator.seedFieldRegistryIfNeeded(seeds) }
    }
    deps.upgradeAtOpen("Restoring the seeded fields' per-document flag") {
        coordinator.restoreSeededPerDocument(seeds, projected = deps.db.fieldDefDao().defs())
    }
    deps.upgradeAtOpen("Adopting this device's library settings") {
        coordinator.adoptSettings(deps.settings.legacyLibrarySettings().journalSettings())
    }
}

/**
 * Bring the library's data up to this build at open, once per library, as best effort: a failure
 * (read-only or flaky storage) is logged and the next open tries again, rather than refusing this
 * one. Storage that cannot publish without replacement at all still refuses the library, as it
 * does wherever an open finds that out.
 */
internal suspend fun SessionDeps.upgradeAtOpen(
    what: String,
    upgrade: suspend () -> Unit,
) {
    runCatchingCancellable { upgrade() }.onFailure { failure ->
        if (failure is VerifiedPublicationUnavailableException) throw failure
        log.warn("$what failed; trying again at the next open", failure)
    }
}
