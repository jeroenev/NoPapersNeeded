package dev.npn.core.app

import dev.npn.core.sync.CloudSetup
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.SyncHealth
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cloud synchronization of this library through the device's transfer engine (rclone). One pass at
 * a time per library; the remote and the schedule are this library's own on this device ([config]),
 * the algorithm comes from `core.sync`.
 */
interface SyncFacade {
    val status: Flow<SyncStatus>

    /**
     * The same status read as the settings screen says it: the last success and failure streak, the
     * next automatic pass, and what the last plan left pending ([SyncHealth]).
     */
    val health: Flow<SyncHealth>

    /** This library's remote and schedule on this device. */
    val config: Flow<CloudSyncConfig>

    /**
     * Change this library's remote or schedule. Clearing the remote also turns automatic sync off,
     * and a new remote forgets the last pass's result, which was another folder's.
     */
    suspend fun configure(change: (CloudSyncConfig) -> CloudSyncConfig)

    /** Run a pass now, whatever the automatic toggle says. Needs a configured remote. */
    suspend fun syncNow(): SyncOutcome

    /**
     * Run a pass if automatic sync is on and the interval has elapsed since the last one; for
     * schedulers (the session's own tick, Android's maintenance job) that must not override the
     * user's choice.
     */
    suspend fun syncScheduled(): SyncOutcome

    suspend fun transportAvailability(): TransportAvailability

    /**
     * In-app remote setup (provider catalog, sign-in, folder picker), or null on a host that wired
     * no configurable engine. The wizard in settings is the only consumer.
     */
    val setup: CloudSetup?
}

/** The facade of a session whose host wired no transfer engine. */
object NoSyncFacade : SyncFacade {
    override val status: Flow<SyncStatus> = MutableStateFlow(SyncStatus())

    override val health: Flow<SyncHealth> = MutableStateFlow(SyncHealth())

    override val config: Flow<CloudSyncConfig> = MutableStateFlow(CloudSyncConfig.DISABLED)

    override suspend fun configure(change: (CloudSyncConfig) -> CloudSyncConfig) = Unit

    override suspend fun syncNow(): SyncOutcome = SyncOutcome.Unavailable(UNAVAILABLE)

    override suspend fun syncScheduled(): SyncOutcome = SyncOutcome.NotConfigured

    override suspend fun transportAvailability(): TransportAvailability = UNAVAILABLE

    override val setup: CloudSetup? = null

    /** No engine was ever looked for: an empty path, which the UI shows as no engine at all. */
    private val UNAVAILABLE = TransportAvailability.NotInstalled("")
}
