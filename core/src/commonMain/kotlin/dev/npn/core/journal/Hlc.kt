package dev.npn.core.journal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hybrid logical clock.
 *
 * Total order: physical time first, logical counter second, device id last (tiebreak).
 * The device id tiebreak guarantees that two distinct devices can never produce equal
 * HLCs, which makes last-writer-wins deterministic without any coordination.
 *
 * On-disk wire form is `{"t":<physicalMillis>,"c":<counter>,"d":<deviceId>}`.
 */
@Serializable
data class Hlc(
    @SerialName("t") val physicalMillis: Long,
    @SerialName("c") val counter: Int,
    @SerialName("d") val deviceId: String,
) : Comparable<Hlc> {
    override fun compareTo(other: Hlc): Int {
        val byPhysical = physicalMillis.compareTo(other.physicalMillis)
        if (byPhysical != 0) return byPhysical
        val byCounter = counter.compareTo(other.counter)
        if (byCounter != 0) return byCounter
        return deviceId.compareTo(other.deviceId)
    }

    companion object {
        val ZERO = Hlc(0L, 0, "")
    }
}

/** Wall-clock source in epoch milliseconds. */
fun interface EpochClock {
    fun nowMillis(): Long
}

/** Platform wall clock, backed by [systemNowMillis]. */
val SystemEpochClock: EpochClock = EpochClock { systemNowMillis() }

/** Platform wall clock in epoch milliseconds (actual per target). */
expect fun systemNowMillis(): Long

/**
 * Single monotonic HLC watermark: tracks the highest (physicalMillis, counter) produced or
 * observed so far and hands out the next value, with the actor (device/transaction id) supplied
 * per call so one allocator can stamp events for several actors (content-addressed transactions).
 *
 * Clamp policy (documented per spec leeway):
 * - When generating locally, wall time is always trusted: if the wall clock jumped far
 *   ahead of the last physical time we simply accept it (real time genuinely moved on,
 *   e.g. after suspend). We never let a backwards or stalled wall clock rewind the HLC;
 *   in that case we keep the last physical time and bump the counter.
 * - Clamping only guards against a *remote* device with an absurd future clock. On
 *   [observe], if `remote.physical > localWall + CLAMP_MS` we treat the remote physical
 *   as `localWall + CLAMP_MS` for merge purposes so a misconfigured peer can only push our
 *   clock at most [CLAMP_MS] into the future rather than arbitrarily far. The remote HLC
 *   is still stored verbatim on disk; clamping only bounds the damage to *our* local clock.
 *
 * Not thread-safe; callers serialize access (single writer per device).
 */
class HlcWatermarkAllocator(
    private val clock: EpochClock = SystemEpochClock,
    initialPhysicalMillis: Long = 0L,
    initialCounter: Int = 0,
) {
    var physicalMillis: Long = initialPhysicalMillis
        private set
    var counter: Int = initialCounter
        private set

    /**
     * Merge a remote HLC into the watermark (standard HLC receive rule), advancing it so any
     * subsequent [next] stays strictly ahead of [remote].
     */
    fun observe(remote: Hlc) {
        val wall = clock.nowMillis()
        val capped = minOf(remote.physicalMillis, wall + HlcClock.CLAMP_MS)
        val nextPhysical = maxOf(wall, physicalMillis, capped)
        counter = when {
            nextPhysical == physicalMillis && nextPhysical == capped -> maxOf(counter, remote.counter) + 1
            nextPhysical == physicalMillis -> counter + 1
            nextPhysical == capped -> remote.counter + 1
            else -> 0
        }
        physicalMillis = nextPhysical
    }

    /** Produce the next HLC for [actor], monotonically greater than every prior value. */
    fun next(actor: String): Hlc {
        val wall = clock.nowMillis()
        val nextPhysical = maxOf(wall, physicalMillis)
        counter = if (nextPhysical == physicalMillis) counter + 1 else 0
        physicalMillis = nextPhysical
        return Hlc(physicalMillis, counter, actor)
    }
}

/**
 * Generates monotonic HLCs for a single device and merges remote ones. A thin, single-actor
 * facade over [HlcWatermarkAllocator]: [deviceId] is the fixed actor and [last] exposes the
 * watermark as a full [Hlc] for the receive-rule guard in `SegmentWriter`.
 *
 * Not thread-safe; callers serialize access (single writer per device).
 */
class HlcClock(
    val deviceId: String,
    epochClock: EpochClock = SystemEpochClock,
    initial: Hlc = Hlc(0L, 0, deviceId),
) {
    private val allocator = HlcWatermarkAllocator(epochClock, initial.physicalMillis, initial.counter)

    val last: Hlc get() = Hlc(allocator.physicalMillis, allocator.counter, deviceId)

    /** Produce the next local HLC, monotonically greater than every previously produced or observed one. */
    fun next(): Hlc = allocator.next(deviceId)

    /**
     * Merge a remote HLC into the local clock (standard HLC receive rule) and return the
     * new local HLC to stamp on the received-and-locally-reacted event. Also advances [last]
     * so subsequent [next] calls stay ahead of anything observed.
     */
    fun observe(remote: Hlc): Hlc {
        allocator.observe(remote)
        return last
    }

    companion object {
        /** 24 hours. Max amount a remote clock can push the local HLC into the future on observe. */
        const val CLAMP_MS: Long = 24L * 60L * 60L * 1000L
    }
}
