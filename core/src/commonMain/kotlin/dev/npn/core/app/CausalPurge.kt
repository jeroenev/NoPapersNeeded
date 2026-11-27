package dev.npn.core.app

import dev.npn.core.index.AckRecords
import dev.npn.core.index.AckedDevice
import dev.npn.core.util.EpochClock

/**
 * Which trash times the purge may go by: a document trashed at T leaves the trash only once every
 * active device has acknowledged, by a record written at T + retention or later, a chain whose every
 * object this device has applied ([AckRecords]). A restore that device made before that record is
 * then folded here, so the purge never takes the bytes of a document restored somewhere this device
 * has not heard of yet; one made after it is a restore past the retention, the user's own choice.
 *
 * Active: a device that writes records, is not retired ([retiredKey], a synced setting any device
 * sets), and wrote one within [SILENT_AFTER_MILLIS]. A device silent for longer (a lost phone) holds
 * nothing back; a build that writes no records is not known here at all, and the purge goes for it
 * by the retention alone, as before.
 */
internal class CausalPurge(
    private val acks: AckRecords,
    private val setting: (String) -> String?,
    private val clock: EpochClock,
) {
    /**
     * Until when every active device has acknowledged: the earliest of their covered records' times
     * ([AckedDevice.covered]); [Acknowledged.ALL] when none holds the purge back, and
     * [Acknowledged.NOTHING] while one has no chain applied here yet.
     */
    suspend fun acknowledgedUntil(): Acknowledged {
        val waiting = active()
        if (waiting.isEmpty()) return Acknowledged.ALL
        val covered = waiting.map { it.covered?.atMillis ?: return Acknowledged.NOTHING }
        return Acknowledged(covered.min())
    }

    /** Every other device that writes records and holds the purge back: neither retired nor silent. */
    suspend fun active(): List<AckedDevice> {
        val now = clock.nowMillis()
        return acks.devices().filter { device ->
            setting(retiredKey(device.device)) == null && now - device.lastHeardMillis <= SILENT_AFTER_MILLIS
        }
    }

    /** Until when every active device acknowledged; see [acknowledgedUntil]. */
    @JvmInline
    value class Acknowledged(
        private val untilMillis: Long,
    ) {
        /**
         * [times] (by document, when it was trashed or deleted for good; null while it is in
         * neither) with every time not acknowledged past [retentionMillis] made null: kept.
         */
        fun filter(
            times: Map<String, Long?>,
            retentionMillis: Long,
        ): Map<String, Long?> =
            when (this) {
                ALL -> times
                NOTHING -> times.mapValues { null }
                else -> times.mapValues { (_, trashed) -> trashed?.takeIf { it <= untilMillis - retentionMillis } }
            }

        companion object {
            val ALL = Acknowledged(Long.MAX_VALUE)
            val NOTHING = Acknowledged(Long.MIN_VALUE)
        }
    }

    companion object {
        /** How long a device may write no record and still hold the purge back: 90 days. */
        const val SILENT_AFTER_MILLIS = 90L * 24 * 60 * 60 * 1000

        /** The synced setting that retires [deviceId]: set, by any device, it holds nothing back. */
        fun retiredKey(deviceId: String): String = "${LibrarySettingKeys.DEVICE_RETIRED_PREFIX}$deviceId"
    }
}
