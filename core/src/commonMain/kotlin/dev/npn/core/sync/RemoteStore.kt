package dev.npn.core.sync

import dev.npn.core.fs.ExternalSource

/**
 * One file on the remote. [path] is remote-root-relative, '/'-separated, no leading slash, and
 * mirrors the library-relative layout. [sha256] is present only when the backend reports SHA-256
 * natively (rclone's local and sftp backends, some object stores); it is then trusted as document
 * identity, saving a download.
 */
data class RemoteEntry(
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val sha256: String? = null,
)

/**
 * The transfer seam between the library and one remote: a very small subset of what an rclone
 * backend can do. The store moves bytes between the remote and the device-local staging area
 * ([LocalStaging]) only; it never sees the library folder, so every write into the library still
 * goes through [dev.npn.core.fs.LibraryFileSystem]'s verified publication paths.
 *
 * Implementations are plain transports. Nothing here decides *what* to transfer — that is
 * [SyncPlanner] — and nothing here is idempotent by itself: the engine guards every destructive
 * call with a [stat] against the listing it planned from.
 */
interface RemoteStore {
    /** Recursive listing of every file under the remote root. Directories are omitted. */
    suspend fun list(): List<RemoteEntry>

    /** The entry at [path], or null when nothing is there. */
    suspend fun stat(path: String): RemoteEntry?

    /** Copy the remote file at [path] to the local absolute [toLocalPath], replacing it if present. */
    suspend fun download(
        path: String,
        toLocalPath: String,
    )

    /** Copy the local absolute [fromLocalPath] to the remote [path]. */
    suspend fun upload(
        fromLocalPath: String,
        path: String,
    )

    /** Server-side rename/move within the remote. Parent directories are created as needed. */
    suspend fun move(
        from: String,
        to: String,
    )

    suspend fun delete(path: String)
}

/** Whether the transfer engine can run at all on this device. */
sealed interface TransportAvailability {
    data class Available(
        val version: String,
    ) : TransportAvailability

    /** The rclone executable could not be found where this platform looks for it. */
    data class NotInstalled(
        val lookedFor: String,
    ) : TransportAvailability

    /** An executable was found but did not answer as rclone would. */
    data class Failed(
        val message: String,
    ) : TransportAvailability
}

/**
 * The device's transfer engine: answers whether it is usable, which remotes its configuration
 * names, and opens a [RemoteStore] over one of them. The production implementation shells out to
 * the rclone executable; tests substitute a directory-backed store.
 */
interface SyncTransport {
    suspend fun availability(): TransportAvailability

    /** Names of the remotes configured in rclone's config file, without the trailing colon. */
    suspend fun listRemotes(): List<String>

    fun open(remote: String): RemoteStore
}

/**
 * The device-local scratch directory transfers pass through. Downloads land here before they are
 * hashed and published into the library; uploads are copied out here so the transport only ever
 * sees plain files. Nothing in here is ever a library path, so an interrupted transfer can leave
 * at most a scratch file behind, which [clear] sweeps on the next pass.
 */
interface LocalStaging {
    /** An absolute path for a fresh scratch file that does not exist yet. */
    fun newPath(suffix: String): String

    suspend fun exists(path: String): Boolean

    suspend fun size(path: String): Long?

    /** Streaming SHA-256 of the scratch file at [path], lowercase hex. */
    suspend fun sha256(path: String): String

    suspend fun delete(path: String)

    /** Create the scratch file at [path] holding [bytes]: what an upload hands the transport. */
    suspend fun write(
        path: String,
        bytes: ByteArray,
    )

    /** The scratch file as the platform's [ExternalSource], for importing it into the library. */
    fun externalSource(path: String): ExternalSource

    /** Remove every scratch file left behind by an earlier, interrupted pass. */
    suspend fun clear()
}
