package dev.npn.core.fs

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * Metadata for a single filesystem entry inside the library.
 *
 * [relPath] is always relative to the library root, uses '/' as separator, and never has a
 * leading '/'. The root itself is never represented as a [FileMeta].
 */
data class FileMeta(
    val relPath: String,
    val name: String,
    val size: Long,
    val mtimeMillis: Long,
    val isDirectory: Boolean,
)

enum class VerifiedPublicationCapability {
    ATOMIC_CREATE_NO_REPLACE,
    UNSUPPORTED,
}

enum class VerifiedPublicationResult {
    PUBLISHED,
    ALREADY_PRESENT,
}

class VerifiedPublicationUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * A coarse change signal from the platform watcher. Coarse granularity is intentional: the
 * indexer reacts by rescanning (fast-path mtime+size), so exact per-file precision is not
 * required.
 */
sealed interface FsEvent {
    /** Something changed at or under [relPath]; `null` means the watcher lost track and a full rescan is advised. */
    data class Changed(val relPath: String?) : FsEvent
}

/**
 * Debounce a coarse [FsEvent] flow: collapse bursts into a single trailing emission roughly
 * every [windowMillis]. Uses conflate + a sampling delay so rapid filesystem churn (a big sync
 * batch) produces one event instead of hundreds.
 */
fun Flow<FsEvent>.debounced(windowMillis: Long = 500L): Flow<FsEvent> {
    val upstream = this.conflate()
    return flow {
        upstream.collect { value ->
            emit(value)
            delay(windowMillis)
        }
    }
}

/**
 * An external location outside the library (a file to import from or export to).
 *
 * The desktop actual wraps a [java.nio.file.Path]; the android actual wraps an
 * [android.net.Uri]. The android [LibraryFileSystem] implementation carries the [Context]
 * needed to resolve the uri, so [ExternalSource] itself only holds the uri.
 */
expect class ExternalSource

/**
 * Filesystem abstraction over a paperless library folder.
 *
 * Design invariants (see PLAN sections 1-4):
 * - The library is a plain folder of immutable PDFs plus a `.npn/` metadata directory.
 * - The app only ever creates new, write-once files (journal segments, OCR sidecars) and
 *   moves/renames PDFs. It never appends to or rewrites an existing file through this API
 *   (the explicit bake-in-place action is handled elsewhere, later).
 * - All paths are library-relative, '/'-separated, no leading slash.
 */
interface LibraryFileSystem {
    /** Whether this provider can atomically publish a complete staged object without replacement. */
    val verifiedPublicationCapability: VerifiedPublicationCapability

    /** Recursive walk of the whole library, including `.npn/` contents. Directories are included. */
    suspend fun listAll(): List<FileMeta>

    /** Recursive walk below [relPath], excluding [relPath] itself. */
    suspend fun listAllUnder(relPath: String): List<FileMeta>

    suspend fun exists(relPath: String): Boolean

    /** Metadata for [relPath], or `null` if it does not exist. */
    suspend fun stat(relPath: String): FileMeta?

    suspend fun readBytes(relPath: String): ByteArray

    /**
     * Create [relPath] with [bytes], creating parent directories as needed. Fails with an
     * [IllegalStateException] if the target already exists. Atomic where the platform allows
     * (temp file + rename on desktop). Never appends or overwrites.
     */
    suspend fun writeBytesOnce(relPath: String, bytes: ByteArray)

    /**
     * Atomically expose [stagingPath] as [canonicalPath], refusing replacement. Both paths must be
     * in the same directory and the bytes at both boundaries must hash to [expectedHash].
     */
    suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ): VerifiedPublicationResult

    /** Move/rename, creating parent dirs of the target. Fails if the target already exists. */
    suspend fun move(fromRelPath: String, toRelPath: String)

    suspend fun delete(relPath: String)

    suspend fun mkdirs(relPath: String)

    /** Streaming SHA-256 of the file's bytes, lowercase hex. */
    suspend fun sha256(relPath: String): String

    /** Copy an external file into the library at [toRelPath]. Fails if the target already exists. */
    suspend fun importExternal(source: ExternalSource, toRelPath: String)

    /** Export a library file to an external location. */
    suspend fun copyOut(relPath: String, target: ExternalSource)

    /**
     * Live change events. Desktop uses a [java.nio.file.WatchService]; the android SAF impl
     * returns an empty flow (indexing there is rescan-driven).
     */
    fun watch(): Flow<FsEvent>
}
