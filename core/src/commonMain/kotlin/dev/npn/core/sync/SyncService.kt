package dev.npn.core.sync

import dev.npn.core.app.SyncFacade
import dev.npn.core.db.KeyValueDao
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnJson
import dev.npn.core.util.NpnLog
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session's [SyncFacade]: owns the library's cloud sync settings, the single-flight gate, the
 * live [SyncStatus], the persisted last-run summary, and the schedule. A pass a caller asks for is
 * awaited by that caller (a manual "sync now" awaits its result; Android's job holds its wakelock
 * across one) but runs as work the session [owned]s, so closing the library cancels and awaits it;
 * the automatic ones run on the session scope this service is [start]ed on.
 *
 * The settings ([CloudSyncConfig]) live in the library's own `device.db`, beside the manifest the
 * passes keep: a remote belongs to one library on this device, never to the device as a whole.
 */
class SyncService(
    private val transport: SyncTransport,
    private val engine: SyncEngine,
    private val kv: KeyValueDao,
    private val clock: EpochClock,
    /** In-app remote setup, or null when the host wired only transfers. */
    override val setup: CloudSetup? = null,
    private val owned: suspend (suspend () -> SyncOutcome) -> SyncOutcome = { it() },
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val soonDelayMillis: Long = DEFAULT_SOON_DELAY_MS,
    /** Where a pass that ended early is logged. */
    private val log: NpnLog = NpnLog.None,
) : SyncFacade {
    private val gate = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    override val status: Flow<SyncStatus> =
        flow {
            runCatchingCancellable { loadLastRun() }
            emitAll(_status)
        }

    private val _config = MutableStateFlow(CloudSyncConfig.DISABLED)
    override val config: Flow<CloudSyncConfig> =
        flow {
            loadConfig()
            emitAll(_config)
        }

    /** The live pass and the last outcome, read as the settings screen says them. */
    override val health: Flow<SyncHealth> =
        combine(config, status) { cfg, current -> SyncHealth.of(cfg, current) }

    /** Guards loading and changing the stored settings and last run. */
    private val store = Mutex()
    private var configLoaded = false
    private var lastRunLoaded = false

    /**
     * "Something changed" requests, collapsed: one waiting is as good as many, and the pass that
     * answers it reads whatever changed meanwhile. Consumed by the loop [start] launches, so a
     * request from any thread is safe.
     */
    private val soon = Channel<Unit>(Channel.CONFLATED)

    /**
     * Start the automatic schedule: a cheap due-check every [checkIntervalMillis], and the passes
     * [requestSoon] asks for.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            launch { answerSoonRequests() }
            loadLastRun()
            while (isActive) {
                delay(checkIntervalMillis)
                runCatchingCancellable { syncScheduled() }
            }
        }

    /**
     * Something changed locally: run a pass soon (debounced), if automatic sync is on. A burst of
     * changes collapses into one pass, run [soonDelayMillis] after the first. A request that arrives
     * while a pass runs is kept for one more pass once it ends, because it may be a real edit the
     * running pass planned before it saw it. The files a pass itself published make the watcher ask
     * once more, which costs one pass that finds nothing to do.
     */
    fun requestSoon() {
        soon.trySend(Unit)
    }

    private suspend fun answerSoonRequests() {
        while (soon.receiveCatching().isSuccess) {
            delay(soonDelayMillis)
            if (!loadConfig().enabled) continue
            val outcome = runCatchingCancellable { owned { run() } }.getOrNull()
            // A pass someone else started holds the gate; the change still needs one after it.
            if (outcome == SyncOutcome.AlreadyRunning) soon.trySend(Unit)
        }
    }

    fun close() {
        soon.close()
    }

    override suspend fun syncNow(): SyncOutcome = owned { run() }

    override suspend fun syncScheduled(): SyncOutcome =
        owned {
            val cfg = loadConfig()
            if (!cfg.enabled) return@owned SyncOutcome.NotConfigured
            loadLastRun()
            val last = _status.value.lastRun
            val due = last == null || clock.nowMillis() - last.finishedAt >= cfg.intervalMinutes * MILLIS_PER_MINUTE
            if (due) run() else SyncOutcome.NotConfigured
        }

    override suspend fun transportAvailability(): TransportAvailability = transport.availability()

    override suspend fun configure(change: (CloudSyncConfig) -> CloudSyncConfig) {
        store.withLock {
            val before = loadConfigLocked()
            val after =
                change(before).let { next ->
                    next.copy(
                        remote = next.remote?.trim()?.takeIf { it.isNotEmpty() },
                        intervalMinutes = next.intervalMinutes.coerceIn(CloudSyncConfig.INTERVAL_RANGE),
                        // Automatic passes need somewhere to go.
                        enabled = next.enabled && !next.remote.isNullOrBlank(),
                    )
                }
            if (after == before) return
            kv.put(KEY_CONFIG, NpnJson.Persisted.encodeToString(CloudSyncConfig.serializer(), after))
            _config.value = after
            if (after.remote != before.remote) {
                // The last pass's result was another folder's; it says nothing about this one.
                kv.delete(SyncManifest.KEY_LAST_RUN)
                lastRunLoaded = true
                _status.update { it.copy(lastRun = null) }
            }
        }
    }

    /**
     * One pass, single-flight. Never throws (except for cancellation): a failure anywhere — reading
     * the settings, probing the transport, the pass itself — comes back as [SyncOutcome.Failed],
     * so a caller on a UI scope cannot be crashed by it.
     */
    private suspend fun run(): SyncOutcome {
        if (!gate.tryLock()) return SyncOutcome.AlreadyRunning
        try {
            return runLocked()
        } finally {
            _status.update { it.copy(running = false, progress = null) }
            gate.unlock()
        }
    }

    private suspend fun runLocked(): SyncOutcome {
        val cfg = runCatchingCancellable { loadConfig() }.getOrElse { return failed(it) }
        val remote = cfg.remote ?: return SyncOutcome.NotConfigured
        val availability =
            runCatchingCancellable { transport.availability() }.getOrElse { return failed(it) }
        if (availability !is TransportAvailability.Available) return SyncOutcome.Unavailable(availability)
        runCatchingCancellable { loadLastRun() }
        val startedAt = clock.nowMillis()
        _status.update { it.copy(running = true, progress = SyncProgress(SyncPhase.LISTING)) }
        return runCatchingCancellable {
            val report =
                engine.run(
                    transport.open(remote),
                    remote,
                ) { progress -> _status.update { it.copy(progress = progress) } }
            finish(SyncRunSummary(startedAt, clock.nowMillis(), report = report))
            SyncOutcome.Completed(report)
        }.getOrElse { failure ->
            log.warn("Cloud sync pass failed", failure)
            val outcome = failed(failure)
            runCatchingCancellable {
                finish(
                    SyncRunSummary(
                        startedAt,
                        clock.nowMillis(),
                        failure = outcome.reason,
                        failureDetail = outcome.detail,
                    ),
                )
            }
            outcome
        }
    }

    private fun failed(failure: Throwable): SyncOutcome.Failed =
        if (failure is SyncRefusedException) {
            SyncOutcome.Failed(failure.reason)
        } else {
            SyncOutcome.Failed(transport.failureOf(failure), failure.reason())
        }

    private suspend fun finish(summary: SyncRunSummary) {
        // A pass that finished clears the streak; one that ended early adds itself to it and keeps
        // the last success, so the settings screen can say how long this library has been failing.
        val previous = _status.value.lastRun
        val next =
            if (summary.ok) {
                summary.copy(consecutiveFailures = 0, lastSuccessAt = summary.finishedAt)
            } else {
                summary.copy(
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    lastSuccessAt = previous?.lastSuccessAt ?: previous?.takeIf { it.ok }?.finishedAt,
                )
            }
        _status.update { it.copy(lastRun = next) }
        kv.put(SyncManifest.KEY_LAST_RUN, NpnJson.Persisted.encodeToString(SyncRunSummary.serializer(), next))
    }

    private suspend fun loadConfig(): CloudSyncConfig = store.withLock { loadConfigLocked() }

    private suspend fun loadConfigLocked(): CloudSyncConfig {
        if (!configLoaded) {
            _config.value =
                kv.get(KEY_CONFIG)?.let { stored ->
                    runCatchingCancellable {
                        NpnJson.Persisted.decodeFromString(CloudSyncConfig.serializer(), stored)
                    }.getOrNull()
                } ?: CloudSyncConfig.DISABLED
            configLoaded = true
        }
        return _config.value
    }

    private suspend fun loadLastRun() {
        store.withLock {
            if (lastRunLoaded) return
            lastRunLoaded = true
            val stored = kv.get(SyncManifest.KEY_LAST_RUN) ?: return
            val summary =
                runCatchingCancellable {
                    NpnJson.Persisted.decodeFromString(SyncRunSummary.serializer(), stored)
                }.getOrNull() ?: return
            _status.update { it.copy(lastRun = it.lastRun ?: summary) }
        }
    }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_MS = 60L * 1000L
        const val DEFAULT_SOON_DELAY_MS = 30L * 1000L
        private const val MILLIS_PER_MINUTE = 60L * 1000L

        /** The library's [CloudSyncConfig] on this device, in its `device.db`. */
        const val KEY_CONFIG = "sync.config"
    }
}
