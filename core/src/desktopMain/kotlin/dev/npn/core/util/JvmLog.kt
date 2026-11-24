package dev.npn.core.util

import java.io.PrintStream
import java.nio.file.Path

/**
 * [NpnLog] for a JVM host. Every entry goes to [stderr] and, when there is a [file], to that file
 * as well. The file rolls over to `<file>.1` once it reaches [maxBytes] (see [RotatingLogFile]), so
 * the two together hold about twice that: enough to cover the run a user reports, never enough to
 * fill a disk.
 *
 * Writing the file is best effort. A full disk or an unwritable directory must not turn a log call
 * into a second failure; stderr still gets the entry.
 */
internal class JvmLog(
    file: Path? = null,
    maxBytes: Long = RotatingLogFile.DEFAULT_MAX_BYTES,
    private val stderr: PrintStream = System.err,
    private val clock: EpochClock = SystemEpochClock,
) : NpnLog {
    private val rotating = file?.let { RotatingLogFile(it, maxBytes) }

    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) {
        val entry = formatLogEntry(level, message, error, clock.nowMillis())
        synchronized(this) {
            stderr.print(entry)
            rotating?.append(entry)
        }
    }

    /** The recent log, for the user's diagnostics bundle. Empty when this host keeps no file. */
    fun tail(maxBytes: Long = RotatingLogFile.DEFAULT_TAIL_BYTES): String = rotating?.tail(maxBytes).orEmpty()

    companion object {
        const val DEFAULT_MAX_BYTES = RotatingLogFile.DEFAULT_MAX_BYTES
    }
}
