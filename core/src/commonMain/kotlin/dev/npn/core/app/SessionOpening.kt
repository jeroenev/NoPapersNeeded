package dev.npn.core.app

import dev.npn.core.ai.RoomChatHistoryProvider
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.defs
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

// How a session opens: [openSession], then the steps it takes, in the order it takes them.

/**
 * Open a session over the library [deps] wire: the one way in from outside core. The host then
 * [starts][LibrarySession.start] it, and [closes][LibrarySession.close] it before it closes the
 * databases.
 *
 * Refuses, with [VerifiedPublicationUnavailableException], a library on a filesystem that cannot
 * publish without replacement: there is no other journal format to write. Every open publishes
 * through the same primitive before it writes anything ([DeviceIdentity]: this installation's
 * marker, or a throwaway object once that is there), so each one finds out.
 */
suspend fun openSession(deps: SessionDeps): LibrarySession = openSession(deps, JournalOp.DocUndelete.WRITTEN)

/**
 * Boot a session over [deps]: move device state an older index still holds into the device
 * database, settle the device id ([DeviceIdentity]: a fresh one for a restored or cloned
 * installation), wire the journal engine, repositories, pipelines, then do the initial fold so
 * [LibrarySessionImpl.documents] is populated before the caller's initial rescan. A deleted
 * document's return is recorded as a `doc-undelete` when [writeUndelete], which only a test sets
 * apart from [JournalOp.DocUndelete.WRITTEN].
 */
internal suspend fun openSession(
    deps: SessionDeps,
    writeUndelete: Boolean,
): LibrarySessionImpl {
    val lifetime = SessionLifetime(deps.scope)
    var opened = false
    try {
        // Before anything reads the device id: an index from before device.db holds it.
        moveDeviceState(deps)
        val indexer = Indexer(deps.fs, deps.db, deps.clock, deps.log, hashing = deps.computation, ids = deps.ids)
        val coordinator = createCoordinator(deps, indexer, writeUndelete)
        // Waits out a write a caller started outside the lifetime, then refuses every later one.
        lifetime.releaseOnClose(coordinator::close)
        return wire(deps, lifetime, indexer, coordinator).also { opened = true }
    } finally {
        // Nothing a failed open started may outlive it (a cancelled open included): stop whatever
        // runs on the lifetime and release what it holds, while the failure goes on to the caller.
        @Suppress("SuspendFunInFinallySection") // SessionLifetime.close runs in NonCancellable itself.
        if (!opened) lifetime.close()
    }
}

/** The session [openSession] opens over its [coordinator], from the device id on. */
private suspend fun wire(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    indexer: Indexer,
    coordinator: JournalCoordinator,
): LibrarySessionImpl {
    val deviceId =
        DeviceIdentity(
            deps.deviceDb.keyValueDao(),
            deps.fs,
            deps.installationId,
            deps.log,
            deps.ids,
        ).resolve()
    foldJournal(deps, coordinator)
    val graph = SessionGraph(deps, lifetime, coordinator, indexer, deviceId)
    graph.prepare()
    return LibrarySessionImpl(deps, graph)
}

/** The `kv` keys an index from before [DeviceDatabase] kept for this device. */
private val DEVICE_KEYS = listOf(DeviceIdentity.KEY_DEVICE_ID)

/** Move what an index from before [DeviceDatabase] kept for this device into the device database. */
private suspend fun moveDeviceState(deps: SessionDeps) =
    LegacyIndexTables.moveDeviceState(deps.db, deps.deviceDb, DEVICE_KEYS, RoomChatHistoryProvider.KEY_PREFIX)

/**
 * The journal writer: storage-v2 content-addressed objects are the only format this build writes
 * or reads, beside the snapshots earlier builds wrote. A filesystem that cannot publish without replacement throws
 * [VerifiedPublicationUnavailableException] at its first publication, which [DeviceIdentity] makes
 * before anything else is written, rather than be written in a weaker format.
 */
private suspend fun createObjectWriter(deps: SessionDeps): ContentAddressedJournalWriter {
    val state = deps.db.metadataProjectionDao().state()
    return ContentAddressedJournalWriter(
        fs = deps.fs,
        installationId = deps.installationId,
        ids = deps.ids,
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
): JournalCoordinator =
    JournalCoordinator(
        writer = createObjectWriter(deps),
        indexer = indexer,
        accumulator = MetadataAccumulator(deps.fs, deps.db),
        dispatcher = deps.computation,
        clock = deps.clock,
        log = deps.log,
        writeUndelete = writeUndelete,
    )

/**
 * Fold the journal, so the documents are there before the caller's initial rescan, and bring the
 * library's own data up to what this build expects: the seeded fields (with the per-document flag
 * an older build may have dropped since), and the library settings this device kept before they
 * became library data.
 *
 * A new library's first seeding is part of opening it: without it the library has no fields. Every
 * other step is an [upgradeAtOpen], and so is the first seeding of a library that opens read-only,
 * its journal not folding whole ([JournalCoordinator.refreshState]).
 */
private suspend fun foldJournal(
    deps: SessionDeps,
    coordinator: JournalCoordinator,
) {
    coordinator.refreshState()
    val seeds = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) }
    if (coordinator.setting(FieldKeys.SEEDED_SETTING) == null && coordinator.canWrite()) {
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
