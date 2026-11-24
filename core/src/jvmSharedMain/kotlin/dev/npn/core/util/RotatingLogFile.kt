package dev.npn.core.util

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * One size-capped log file, shared by the desktop's [JvmLog] and Android's file log.
 *
 * The file rolls over to `<file>.1` once it reaches [maxBytes], so the two together hold about
 * twice that: enough to cover the run a user reports, never enough to fill a disk. Every write and
 * read is best effort; a log call sits in the failure path of whatever it reports and must never
 * throw a second failure.
 */
internal class RotatingLogFile(
    private val file: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lock = Any()

    fun append(entry: String) {
        synchronized(lock) {
            try {
                file.parent?.let(Files::createDirectories)
                if (Files.exists(file) && Files.size(file) >= maxBytes) {
                    Files.move(
                        file,
                        file.resolveSibling("${file.fileName}.1"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                // Files.writeString is API 36.1+ on Android; minSdk is 30, so write bytes explicitly.
                Files.write(
                    file,
                    entry.toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            } catch (_: IOException) {
                // Best effort: the caller has already put the entry where it can.
            }
        }
    }

    /**
     * The most recent about [maxBytes] of the log, the previous file first when the current one
     * holds less than that: the crash a user reports may be in either.
     */
    fun tail(maxBytes: Long = DEFAULT_TAIL_BYTES): String =
        synchronized(lock) {
            try {
                val current = readTail(file, maxBytes)
                val room = maxBytes - current.length
                if (room <= 0) {
                    current
                } else {
                    readTail(file.resolveSibling("${file.fileName}.1"), room) + current
                }
            } catch (_: IOException) {
                ""
            }
        }

    private fun readTail(
        target: Path,
        limit: Long,
    ): String {
        if (!Files.exists(target)) return ""
        RandomAccessFile(target.toFile(), "r").use { raf ->
            val length = raf.length()
            val start = maxOf(0L, length - limit)
            val count = (length - start).toInt()
            if (count <= 0) return ""
            raf.seek(start)
            val bytes = ByteArray(count)
            raf.readFully(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 1_000_000L
        const val DEFAULT_TAIL_BYTES = 64L * 1024
    }
}

/** One log line in the shared format: when, how bad, what, and the stack trace if any. */
internal fun formatLogEntry(
    level: NpnLog.Level,
    message: String,
    error: Throwable?,
    nowMillis: Long,
): String =
    buildString {
        append(Instant.ofEpochMilli(nowMillis))
            .append(' ')
            .append(level)
            .append(' ')
            .append(message)
            .append('\n')
        if (error != null) append(error.stackTraceToString())
    }
