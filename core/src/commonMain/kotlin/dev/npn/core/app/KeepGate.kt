package dev.npn.core.app

import dev.npn.core.index.AckRecords
import dev.npn.core.util.EpochClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether this device writes the keep store ([dev.npn.core.fs.KeepStore]): only once every other
 * device that may open the library reads it, as a build that does not cannot restore a document
 * whose bytes are only there. It would show it missing, not lose it, but the trash it relies on
 * would hold nothing to restore.
 *
 * Every device an installation marker names ([AckRecords.markedDevices]) or a record of whose this
 * device holds waits, unless it is retired ([CausalPurge.retiredKey]), silent for longer than
 * [CausalPurge.SILENT_AFTER_MILLIS] by its records, or its latest record lists [AckRecords.KEEP_V1].
 * A build from before records writes a marker and no record: it holds the gate shut until it is
 * updated or retired, which the Devices screen shows ([waiting]).
 *
 * Read again after each background rescan ([refresh]); shut until the first read.
 */
internal class KeepGate(
    private val acks: AckRecords,
    private val setting: (String) -> String?,
    private val clock: EpochClock,
) {
    private val state = MutableStateFlow(Gate(open = false, waiting = emptySet()))

    /** Whether this device writes the keep store, as the last [refresh] found. */
    val open: Boolean get() = state.value.open

    /** The devices that held the gate shut at the last [refresh]. */
    val waiting: StateFlow<Gate> = state.asStateFlow()

    /** The gate as one [refresh] found it: whether open, and which devices keep it shut. */
    data class Gate(
        val open: Boolean,
        val waiting: Set<String>,
    )

    /** Read every device's markers and records again; returns whether the gate is open. */
    suspend fun refresh(): Boolean {
        val now = clock.nowMillis()
        val recorded = acks.devices().associateBy { it.device }
        val waiting =
            (acks.markedDevices() + recorded.keys).filterTo(HashSet()) { device ->
                val record = recorded[device]
                setting(CausalPurge.retiredKey(device)) == null &&
                    (
                        record == null ||
                            (
                                now - record.lastHeardMillis <= CausalPurge.SILENT_AFTER_MILLIS &&
                                    AckRecords.KEEP_V1 !in record.caps
                            )
                    )
            }
        state.value = Gate(open = waiting.isEmpty(), waiting = waiting)
        return waiting.isEmpty()
    }
}
