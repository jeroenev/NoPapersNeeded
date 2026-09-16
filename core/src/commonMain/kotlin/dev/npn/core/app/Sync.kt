package dev.npn.core.app

import dev.npn.core.sync.CloudSetup
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Cloud synchronization of this library through the device's transfer engine (rclone). One pass at
 * a time per library; the remote and the schedule come from the device settings, the algorithm
 * from `core.sync`.
 */
interface SyncFacade {
    val status: Flow<SyncStatus>

    /** Run a pass now, whatever the automatic toggle says. Needs a configured remote. */
    suspend fun syncNow(): SyncOutcome

    /**
     * Run a pass if automatic sync is on and the interval has elapsed since the last one; for
     * schedulers (the session's own tick, Android's maintenance job) that must not override the
     * user's choice.
     */
    suspend fun syncScheduled(): SyncOutcome

    suspend fun transportAvailability(): TransportAvailability

    /** Remote names from the transfer engine's configuration, for the settings picker. */
    suspend fun listRemotes(): List<String>

    /**
     * In-app remote setup (provider catalog, sign-in, folder picker), or null on a host that wired
     * no configurable engine. The wizard in settings is the only consumer.
     */
    val setup: CloudSetup?
}

/** The facade of a session whose host wired no transfer engine. */
object NoSyncFacade : SyncFacade {
    override val status: Flow<SyncStatus> = MutableStateFlow(SyncStatus())

    override suspend fun syncNow(): SyncOutcome = SyncOutcome.Unavailable(UNAVAILABLE)

    override suspend fun syncScheduled(): SyncOutcome = SyncOutcome.NotConfigured

    override suspend fun transportAvailability(): TransportAvailability = UNAVAILABLE

    override suspend fun listRemotes(): List<String> = emptyList()

    override val setup: CloudSetup? = null

    private val UNAVAILABLE = TransportAvailability.NotInstalled("no transfer engine wired for this host")
}
