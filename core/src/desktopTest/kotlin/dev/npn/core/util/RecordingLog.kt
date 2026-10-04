package dev.npn.core.util

import java.util.Collections

/** [NpnLog] that keeps every entry, for tests that assert a failure was reported. Thread-safe. */
class RecordingLog : NpnLog {
    data class Entry(
        val level: NpnLog.Level,
        val message: String,
        val error: Throwable?,
    )

    private val recorded = Collections.synchronizedList(ArrayList<Entry>())

    val entries: List<Entry> get() = synchronized(recorded) { recorded.toList() }

    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) {
        recorded += Entry(level, message, error)
    }
}
