package dev.npn.core.sync

import dev.npn.core.fs.ExternalSource

/**
 * One file on the remote. [path] is remote-root-relative, '/'-separated, no leading slash, and
 * mirrors the library-relative layout. [sha256] is present only when the backend reports SHA-256
 * natively (rclone's local and sftp backends, some object stores); it is then trusted as document
 * identity, saving a download. [dropboxHash] is Dropbox's content hash ([DropboxContentHash]), which
 * names the document when a library file of the same size has it, also saving a download.
 */
data class RemoteEntry(
    val path: String,
    val size: Long,
    val mtimeMillis: Long,
    val sha256: String? = null,
    val dropboxHash: String? = null,
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

    /** How many transfers of each kind this remote takes well at once. */
    val limits: TransferLimits get() = TransferLimits.DEFAULT
}

/** Whether the transfer engine can run at all on this device. */
sealed interface TransportAvailability {
    data class Available(
        val version: String,
    ) : TransportAvailability

    /** The rclone executable could not be found (or started) where this platform looks for it. */
    data class NotInstalled(
        val lookedFor: String,
    ) : TransportAvailability

    /**
     * The executable is there but the engine is not usable: [problem] says how, for the message the
     * UI shows; [detail] is rclone's own diagnostic text, untranslated, for the curious.
     */
    data class Failed(
        val problem: EngineProblem,
        val detail: String? = null,
    ) : TransportAvailability
}

/** Why the transfer engine could not be used, beyond not being installed. */
enum class EngineProblem {
    /** The executable could not be started: it is missing, or not executable. */
    START_FAILED,

    /** The process started and exited before it answered. */
    EXITED,

    /** The process runs but did not answer in time. */
    NOT_READY,

    /** It answered before and stopped answering, a restart included. */
    UNREACHABLE,

    /** The engine was closed with its session; nothing more runs on it. */
    CLOSED,

    /** It answered, with an error of its own. */
    ERROR,
}

/**
 * The device's transfer engine: answers whether it is usable and opens a [RemoteStore] over a
 * remote. The production implementation shells out to
 * the rclone executable; tests substitute a directory-backed store.
 */
interface SyncTransport {
    suspend fun availability(): TransportAvailability

    fun open(remote: String): RemoteStore

    /**
     * What [failure], thrown by one of this engine's stores, means for the user: the reason the UI
     * names. An engine that cannot tell says [SyncFailure.UNKNOWN] and the failure's text is shown.
     */
    fun failureOf(failure: Throwable): SyncFailure = SyncFailure.UNKNOWN

    /**
     * Release any long-lived resources the engine holds (a spawned process). Defaults to nothing so
     * a stateless or test transport need not care; the session calls it on close.
     */
    fun close() {}
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

    /**
     * Create the scratch file at [path] from the chunks [fill] hands its sink, without holding the
     * whole file in memory: how a document is copied out of the library for an upload.
     */
    suspend fun writeStreaming(
        path: String,
        fill: suspend (sink: (ByteArray, Int) -> Unit) -> Unit,
    )

    /** The scratch file as the platform's [ExternalSource], for importing it into the library. */
    fun externalSource(path: String): ExternalSource

    /** Remove every scratch file left behind by an earlier, interrupted pass. */
    suspend fun clear()
}
