package dev.npn.core.fs

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

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

/**
 * Whether the file [this] describes still has the [size] and [mtime] stored for it earlier: the
 * evidence that the bytes there are still the ones known then, without hashing them. A rescan and an
 * import (`ImportService`) take a document row's stat as that evidence for a document that is not
 * hidden (a hidden one gave its path up, so a file there is hashed); the trash and duplicate removal
 * take the stat they saw when they hashed the file.
 */
internal fun FileMeta.hasStoredStat(
    size: Long,
    mtime: Long,
): Boolean = this.size == size && mtimeMillis == mtime

/**
 * This filesystem can never publish without replacement: the platform or mount has no primitive
 * for it. A capability, not an incident, so the library refuses to open rather than write.
 */
class VerifiedPublicationUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * One publication attempt failed on a filesystem that can publish: a provider rename that threw or
 * landed under another name, a staging file that vanished. Only this write fails; the next one
 * tries again the same way.
 */
class VerifiedPublicationFailedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * A coarse change signal from the platform watcher: something changed at or under [relPath]. Coarse
 * granularity is intentional: the indexer reacts by rescanning (fast-path mtime+size), so exact
 * per-file precision is not required.
 */
data class FsEvent(
    /** Where the change happened; `null` means the watcher lost track and a full rescan is advised. */
    val relPath: String?,
)

/**
 * An external location outside the library: a file to import from.
 *
 * The desktop actual wraps a `java.nio.file.Path`; the android actual wraps an `android.net.Uri`,
 * which the library filesystem resolves through a `ContentResolver`, so the source itself only
 * holds the uri.
 */
expect class ExternalSource

/**
 * Filesystem abstraction over a paperless library folder.
 *
 * Design invariants:
 * - The library is a plain folder of immutable PDFs plus a `.npn/` metadata directory.
 * - The app only ever creates new, write-once files (journal objects, OCR sidecars) and
 *   moves/renames PDFs. It never appends to or rewrites an existing file through this API; the
 *   explicit bake-in-place (`BakeService`) replaces a PDF by a delete and a move.
 * - All paths are library-relative, '/'-separated, no leading slash.
 */
interface LibraryFileSystem {
    /**
     * Whether [publishVerifiedObjectOnce] rests on what the storage under the library offers (a hard
     * link, an exclusive rename), which a remount can take away, so every open tries a publication
     * ([dev.npn.core.index.DeviceIdentity]). False for a provider that publishes with a rename of its
     * own, which no open can lose; it is spared the round trips.
     */
    val publicationMayBeLost: Boolean get() = true

    /** Supports verified publication to arbitrary PDF names without replacing a concurrent file. */
    val supportsDocumentRelocation: Boolean get() = false

    /** Atomically consume the source name without replacing the destination; no link/unlink fallback. */
    suspend fun claimDocument(
        fromRelPath: String,
        toRelPath: String,
    ): Unit = throw VerifiedPublicationUnavailableException("Atomic document claiming is unsupported")

    /**
     * Recursive walk of the whole library excluding the `.npn/` metadata subtree (journal, OCR
     * sidecars, trash), for indexing/rescan walks. Each platform prunes the subtree in the walk
     * itself rather than traversing `.npn/`.
     */
    suspend fun listAllIndexable(): List<FileMeta>

    /**
     * Recursive walk below [relPath], excluding [relPath] itself. Directories are included; `""`
     * walks the whole library, `.npn/` contents included.
     */
    suspend fun listAllUnder(relPath: String): List<FileMeta>

    suspend fun exists(relPath: String): Boolean

    /** Metadata for [relPath], or `null` if it does not exist. */
    suspend fun stat(relPath: String): FileMeta?

    suspend fun readBytes(relPath: String): ByteArray

    /** Up to the last [maxBytes] bytes of [relPath], seeking rather than reading the whole file. */
    suspend fun readTail(
        relPath: String,
        maxBytes: Int,
    ): ByteArray

    /**
     * Create [relPath] with [bytes], creating parent directories as needed. Fails with an
     * [IllegalStateException] if the target already exists. Atomic where the platform allows
     * (temp file + rename on desktop). Never appends or overwrites.
     */
    suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    )

    /**
     * Atomically expose [stagingPath] as [canonicalPath], refusing replacement. Both paths must be
     * in the same directory and the bytes at both boundaries must hash to [expectedHash]. A
     * canonical object already holding those bytes counts as published; the staging file is
     * removed either way.
     */
    suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    )

    /** Move/rename, creating parent dirs of the target. Fails if the target already exists. */
    suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    )

    /** Copy to an unpublished staging file without replacement. Platforms can stream large PDFs. */
    suspend fun copyOnce(
        fromRelPath: String,
        toRelPath: String,
    ) {
        writeBytesOnce(toRelPath, readBytes(fromRelPath))
    }

    /** True if two existing paths address the same file (including case aliases and hard links). */
    suspend fun sameFile(
        first: String,
        second: String,
    ): Boolean = first == second

    suspend fun delete(relPath: String)

    /** Streaming SHA-256 of the file's bytes, lowercase hex. */
    suspend fun sha256(relPath: String): String

    /** Copy an external file into the library at [toRelPath]. Fails if the target already exists. */
    suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    )

    /**
     * Live change events. Desktop uses a [java.nio.file.WatchService]; the android SAF impl
     * returns an empty flow (indexing there is rescan-driven).
     */
    fun watch(): Flow<FsEvent>
}

/**
 * Whether [relPath] is a file whose bytes hash to [hash], the SHA-256 that is a document's id. A
 * file moved away or written over while it is looked at ([stat] or the read) is not one: the
 * question is whether the bytes are proven to be [hash]'s, and a failure to read them is not.
 */
internal suspend fun LibraryFileSystem.holds(
    relPath: String,
    hash: String,
): Boolean =
    stat(relPath)?.isDirectory == false &&
        runCatchingCancellable { sha256(relPath) }.getOrNull() == hash

/**
 * Write a staging file at [staging] with [stage], then publish it as [target] without replacement,
 * its bytes verified against [hash] ([LibraryFileSystem.publishVerifiedObjectOnce]). Unless that
 * publishes, the staging file is deleted, best effort and cancelled or not, and the failure
 * rethrown, so nothing staged lingers. With [targetHeldCounts], a failure after which [target]
 * holds the bytes all the same published them: a reader takes such an object, so the write must
 * not report that it did not.
 */
internal suspend fun LibraryFileSystem.publishStagedOnce(
    staging: String,
    target: String,
    hash: String,
    targetHeldCounts: Boolean = false,
    stage: suspend () -> Unit,
) {
    var published = false
    try {
        stage()
        runCatchingCancellable { publishVerifiedObjectOnce(staging, target, hash) }.onFailure { failure ->
            if (!targetHeldCounts || !runCatchingCancellable { holds(target, hash) }.getOrDefault(false)) throw failure
        }
        published = true
    } finally {
        if (!published) withContext(NonCancellable) { runCatchingCancellable { delete(staging) } }
    }
}
