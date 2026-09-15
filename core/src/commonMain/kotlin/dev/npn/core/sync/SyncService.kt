package dev.npn.core.sync

import dev.npn.core.app.SyncFacade
import dev.npn.core.db.KeyValueDao
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnJson
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * The session's [SyncFacade]: owns the single-flight gate, the live [SyncStatus], the persisted
 * last-run summary, and the schedule. A pass a caller asks for is awaited by that caller (a manual
 * "sync now" awaits its result; Android's job holds its wakelock across one) but runs as work the
 * session [owned]s, so closing the library cancels and awaits it; the automatic ones run on the
 * session scope this service is [start]ed on.
 */
class SyncService(
    private val transport: SyncTransport,
    private val config: suspend () -> CloudSyncConfig,
    private val engine: SyncEngine,
    private val kv: KeyValueDao,
    private val clock: EpochClock,
    private val owned: suspend (suspend () -> SyncOutcome) -> SyncOutcome = { it() },
    private val checkIntervalMillis: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val soonDelayMillis: Long = DEFAULT_SOON_DELAY_MS,
) : SyncFacade {
    private val gate = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    override val status: Flow<SyncStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var soonJob: Job? = null
    private var loaded = false

    /** A change arrived while a pass ran; one more pass is owed once it finishes. */
    private var changedDuringPass = false

    /** Start the automatic schedule: a cheap due-check every [checkIntervalMillis]. */
    fun start(scope: CoroutineScope): Job {
        this.scope = scope
        return scope.launch {
            loadLastRun()
            while (isActive) {
                delay(checkIntervalMillis)
                runCatchingCancellable { syncScheduled() }
            }
        }
    }

    /**
     * Something changed locally: run a pass soon (debounced), if automatic sync is on. A burst of
     * edits collapses into one pass. Requests made just after a pass finished are dropped: the
     * watcher reports the files the pass itself published, and reacting to those would chain a
     * needless pass onto every pass that transferred anything. A request made *while* a pass runs
     * is remembered instead, because it may be a real edit the running pass planned before it saw
     * it; one follow-up pass is scheduled when the running one ends.
     */
    fun requestSoon() {
        val scope = scope ?: return
        val status = _status.value
        if (status.running) {
            changedDuringPass = true
            return
        }
        val justFinished = status.lastRun?.let { clock.nowMillis() - it.finishedAt < soonDelayMillis } == true
        if (justFinished) return
        scheduleSoon(scope)
    }

    private fun scheduleSoon(scope: CoroutineScope) {
        soonJob?.cancel()
        soonJob =
            scope.launch {
                delay(soonDelayMillis)
                if (config().enabled) runCatchingCancellable { run() }
            }
    }

    fun close() {
        soonJob?.cancel()
        soonJob = null
    }

    override suspend fun syncNow(): SyncOutcome = owned { run() }

    override suspend fun syncScheduled(): SyncOutcome =
        owned {
            val cfg = config()
            if (!cfg.enabled) return@owned SyncOutcome.NotConfigured
            loadLastRun()
            val last = _status.value.lastRun
            val due = last == null || clock.nowMillis() - last.finishedAt >= cfg.intervalMinutes * MILLIS_PER_MINUTE
            if (due) run() else SyncOutcome.NotConfigured
        }

    override suspend fun transportAvailability(): TransportAvailability = transport.availability()

    override suspend fun listRemotes(): List<String> = transport.listRemotes()

    /**
     * One pass, single-flight. Never throws (except for cancellation): a failure anywhere — reading
     * the settings, probing the transport, the pass itself — comes back as [SyncOutcome.Failed],
     * so a caller on a UI scope cannot be crashed by it.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun run(): SyncOutcome {
        if (!gate.tryLock()) return SyncOutcome.AlreadyRunning
        val startedAt = clock.nowMillis()
        var started = false
        try {
            val remote = config().remote?.trim()?.takeIf { it.isNotEmpty() } ?: return SyncOutcome.NotConfigured
            val availability = transport.availability()
            if (availability !is TransportAvailability.Available) return SyncOutcome.Unavailable(availability)
            loadLastRun()
            started = true
            changedDuringPass = false
            _status.update { it.copy(running = true, progress = SyncProgress(SyncPhase.LISTING)) }
            val report =
                engine.run(
                    transport.open(remote),
                    remote,
                ) { progress -> _status.update { it.copy(progress = progress) } }
            finish(SyncRunSummary(startedAt, clock.nowMillis(), report = report))
            return SyncOutcome.Completed(report)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: failure::class.simpleName ?: "sync failed"
            if (started) {
                runCatchingCancellable {
                    finish(
                        SyncRunSummary(startedAt, clock.nowMillis(), failure = message),
                    )
                }
            }
            return SyncOutcome.Failed(message)
        } finally {
            _status.update { it.copy(running = false, progress = null) }
            gate.unlock()
            if (started && changedDuringPass) {
                changedDuringPass = false
                scope?.let(::scheduleSoon)
            }
        }
    }

    private suspend fun finish(summary: SyncRunSummary) {
        _status.update { it.copy(lastRun = summary) }
        kv.put(SyncManifest.KEY_LAST_RUN, NpnJson.Persisted.encodeToString(SyncRunSummary.serializer(), summary))
    }

    private suspend fun loadLastRun() {
        if (loaded) return
        val stored = kv.get(SyncManifest.KEY_LAST_RUN)
        loaded = true
        if (stored == null) return
        val summary =
            runCatching { NpnJson.Persisted.decodeFromString(SyncRunSummary.serializer(), stored) }.getOrNull()
                ?: return
        _status.update { it.copy(lastRun = it.lastRun ?: summary) }
    }

    companion object {
        const val DEFAULT_CHECK_INTERVAL_MS = 60L * 1000L
        const val DEFAULT_SOON_DELAY_MS = 30L * 1000L
        private const val MILLIS_PER_MINUTE = 60L * 1000L
    }
}
