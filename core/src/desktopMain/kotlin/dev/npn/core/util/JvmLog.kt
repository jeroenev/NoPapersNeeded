package dev.npn.core.util

import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * [NpnLog] for a JVM host. Every entry goes to [stderr] and, when there is a [file], to that file
 * as well. The file rolls over to `<file>.1` once it reaches [maxBytes], so the two together hold
 * about twice that: enough to cover the run a user reports, never enough to fill a disk.
 *
 * Writing the file is best effort. A full disk or an unwritable directory must not turn a log call
 * into a second failure; stderr still gets the entry.
 */
class JvmLog(
    private val file: Path? = null,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val stderr: PrintStream = System.err,
) : NpnLog {
    private val lock = Any()

    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) {
        val entry =
            buildString {
                append(Instant.now()).append(' ').append(level).append(' ').append(message).append('\n')
                if (error != null) append(error.stackTraceToString())
            }
        synchronized(lock) {
            stderr.print(entry)
            file?.let { append(it, entry) }
        }
    }

    private fun append(
        target: Path,
        entry: String,
    ) {
        try {
            target.parent?.let(Files::createDirectories)
            if (Files.exists(target) && Files.size(target) >= maxBytes) {
                Files.move(target, target.resolveSibling("${target.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(target, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: IOException) {
            // Best effort, see the class KDoc: stderr already has the entry.
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000L
    }
}
