package dev.npn.core.index

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem

/**
 * Whether a PDF the rescan is about to hash has finished being written.
 *
 * A document's identity is the hash of its bytes, so hashing a file that is still being written
 * gives it the identity of a partial (or empty) file, and OCR, rules and the journal then attach
 * to that. Syncthing and the app's own imports write elsewhere and rename into place, but `cp`,
 * `adb push`, scanner apps and some sync tools write the final path in place.
 *
 * A file is ready when it has not been modified for [QUIET_MILLIS] and ends with a PDF's `%%EOF`
 * marker. A file that pauses mid-write would pass the first test alone. A file that never gets
 * the marker (a damaged PDF that still opens) is indexed anyway once it has been left alone for
 * [TRAILER_GRACE_MILLIS]. An empty file is never a document.
 *
 * Ages use [now] against the file's mtime. An mtime further ahead than a window is taken as
 * another device's clock (sync tools keep the source mtime), not as a write in progress.
 */
internal class WriteSettle(
    private val fs: LibraryFileSystem,
    private val now: Long,
) {
    sealed interface Verdict {
        data object Ready : Verdict

        /** Possibly still being written; a rescan after [millis] can decide again. */
        data class Wait(
            val millis: Long,
        ) : Verdict

        /** Settled, but not a document (empty). */
        data object Skip : Verdict
    }

    suspend fun verdict(meta: FileMeta): Verdict {
        val age = now - meta.mtimeMillis
        if (age in -QUIET_MILLIS until QUIET_MILLIS) return Verdict.Wait(QUIET_MILLIS - age)
        val young = age in -TRAILER_GRACE_MILLIS until TRAILER_GRACE_MILLIS
        return when {
            meta.size > 0 && (!young || hasEofMarker(meta.relPath)) -> Verdict.Ready
            young -> Verdict.Wait(TRAILER_GRACE_MILLIS - age)
            else -> Verdict.Skip
        }
    }

    /** True when the size and mtime the listing saw still hold after hashing. */
    suspend fun unchangedSince(meta: FileMeta): Boolean {
        val after = fs.stat(meta.relPath) ?: return false
        return after.size == meta.size && after.mtimeMillis == meta.mtimeMillis
    }

    private suspend fun hasEofMarker(relPath: String): Boolean {
        val tail = runCatching { fs.readTail(relPath, TAIL_BYTES) }.getOrNull() ?: return false
        return tail.decodeToString().contains(EOF_MARKER)
    }

    companion object {
        /** No writes for this long: longer than the 2 s mtime granularity of FAT/exFAT cards. */
        const val QUIET_MILLIS = 3_000L

        /** A file without `%%EOF` is indexed anyway once it has been left alone this long. */
        const val TRAILER_GRACE_MILLIS = 120_000L

        /** Readers look for the end marker within the last 1024 bytes, as Acrobat does. */
        private const val TAIL_BYTES = 1024
        private const val EOF_MARKER = "%%EOF"
    }
}

/** The paths one rescan deferred, and how soon a rescan could index the first of them. */
internal class DeferredFiles {
    private val waits = LinkedHashMap<String, Long>()

    fun add(
        relPath: String,
        millis: Long,
    ) {
        waits[relPath] = millis
    }

    val paths: List<String> get() = waits.keys.toList()

    val retryAfterMillis: Long? get() = waits.values.minOrNull()
}
