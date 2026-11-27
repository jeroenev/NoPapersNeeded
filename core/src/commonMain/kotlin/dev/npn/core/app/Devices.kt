package dev.npn.core.app

import dev.npn.core.index.AckRecords
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.util.EpochClock

/** A device that shares this library, as its acknowledgment records show it. */
data class LibraryDevice(
    /** Its journal device id. */
    val deviceId: String,
    /** This installation's own device. */
    val thisDevice: Boolean,
    /** When it last wrote a record; null for none yet (this device before its first). */
    val lastHeardMillis: Long?,
    /** Retired by a user on any device: it holds the trash purge back no more. */
    val retired: Boolean,
    /** Silent for longer than 90 days: retired by itself until it writes a record again. */
    val silent: Boolean,
    /**
     * It runs a build that does not read a format this one would write (the keep store), so this
     * device waits to write it until it is updated or retired. A device that writes no records at all
     * (a build from before them) is one too, known by its installation marker alone.
     */
    val needsUpdate: Boolean = false,
)

/**
 * The devices that share this library, and the retirement that lets the trash purge and the keep
 * store stop waiting for one ([CausalPurge], [KeepGate]): a lost or wiped phone, an old installation.
 * A build from before acknowledgment records is known by its installation marker alone: it holds the
 * purge back no more than before, but the keep store waits for it.
 */
interface DevicesFacade {
    /** This device first, then every other that writes records, most recently heard from first. */
    suspend fun devices(): List<LibraryDevice>

    /** Retire [deviceId] on every device: the trash purge no longer waits for it. */
    suspend fun retire(deviceId: String)

    /** Undo [retire]: the purge waits for [deviceId] again while it writes records. */
    suspend fun unretire(deviceId: String)
}

internal class JournalDevices(
    private val coordinator: JournalCoordinator,
    private val acks: AckRecords,
    private val lifetime: SessionLifetime,
    private val clock: EpochClock,
    private val deviceId: String,
    private val keepGate: KeepGate? = null,
) : DevicesFacade {
    override suspend fun devices(): List<LibraryDevice> =
        lifetime.owned {
            val now = clock.nowMillis()
            val recorded = acks.devices()
            val others =
                recorded.map { device ->
                    LibraryDevice(
                        deviceId = device.device,
                        thisDevice = false,
                        lastHeardMillis = device.lastHeardMillis,
                        retired = retired(device.device),
                        silent = now - device.lastHeardMillis > CausalPurge.SILENT_AFTER_MILLIS,
                        needsUpdate = AckRecords.KEEP_V1 !in device.caps,
                    )
                }
            val markedOnly =
                (acks.markedDevices() - recorded.mapTo(HashSet()) { it.device }).map { device ->
                    LibraryDevice(device, false, null, retired(device), silent = false, needsUpdate = true)
                }
            val self = LibraryDevice(deviceId, true, acks.lastWrittenMillis(), retired = false, silent = false)
            listOf(self) + others.sortedByDescending { it.lastHeardMillis } + markedOnly.sortedBy { it.deviceId }
        }

    private fun retired(device: String): Boolean = coordinator.setting(CausalPurge.retiredKey(device)) != null

    override suspend fun retire(deviceId: String) = setRetired(deviceId, true)

    override suspend fun unretire(deviceId: String) = setRetired(deviceId, false)

    private suspend fun setRetired(
        device: String,
        retired: Boolean,
    ) {
        require(device != deviceId) { "This device cannot retire itself" }
        lifetime.owned {
            coordinator.putSetting(CausalPurge.retiredKey(device), if (retired) clock.nowMillis().toString() else null)
            keepGate?.refresh()
        }
    }
}
