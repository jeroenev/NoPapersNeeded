package dev.npn.core.sim

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.sync.RemoteEntry
import dev.npn.core.sync.RemoteStore
import dev.npn.core.sync.SyncTransport
import dev.npn.core.sync.TransportAvailability
import dev.npn.core.util.EpochClock
import kotlinx.coroutines.delay
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TreeMap
import kotlin.random.Random

/**
 * How a seed's cloud remote behaves, when the seed syncs its replicas through one ([SyncConfig.cloud])
 * instead of a folder sync tool: whether paths fold case (OneDrive, Dropbox, Box, SMB), whether the
 * listing reports SHA-256 (rclone's local and sftp backends do; most consumer clouds do not, so
 * every PDF is identified by a download), and how often a call fails.
 */
data class CloudOptions(
    val caseInsensitive: Boolean,
    val reportsHashes: Boolean,
    /** Percent of remote calls that fail before taking effect, while faults are on. */
    val failPercent: Int,
)

/**
 * The cloud remote every replica's session syncs through: one [RemoteStore] in memory, behind the
 * real sync engine ([dev.npn.core.sync.SyncEngine]) each session runs, as rclone is behind it in
 * production. Each call waits a seeded stretch of virtual time, so passes of several replicas and
 * their sessions' own work interleave, and fails now and then while [faultsOn]. Uploads take the
 * virtual clock's time as their mtime; a move keeps it, as the consumer clouds do. With
 * [CloudOptions.caseInsensitive] a path names whatever file its case-folded form does, and a write
 * to it replaces that file under its existing name.
 */
internal class SimRemote(
    private val clock: EpochClock,
    private val random: Random,
    val options: CloudOptions,
) : SyncTransport,
    RemoteStore {
    private class Stored(
        val bytes: ByteArray,
        val mtime: Long,
    )

    private val files = TreeMap<String, Stored>()

    /** Off while the simulation brings everything to rest: a full sync must be able to converge. */
    var faultsOn = true

    override suspend fun availability(): TransportAvailability = TransportAvailability.Available("sim")

    override fun open(remote: String): RemoteStore = this

    override suspend fun list(): List<RemoteEntry> {
        call("list")
        return files.map { (path, stored) -> entry(path, stored) }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        call("stat $path")
        return key(path)?.let { entry(it, files.getValue(it)) }
    }

    override suspend fun download(
        path: String,
        toLocalPath: String,
    ) {
        call("download $path")
        val stored = key(path)?.let(files::get) ?: throw IOException("Not found on the remote: $path")
        Files.write(Paths.get(toLocalPath), stored.bytes)
    }

    override suspend fun upload(
        fromLocalPath: String,
        path: String,
    ) {
        call("upload $path")
        val bytes = Files.readAllBytes(Paths.get(fromLocalPath))
        files[key(path) ?: path] = Stored(bytes, clock.nowMillis())
    }

    override suspend fun move(
        from: String,
        to: String,
    ) {
        call("move $from")
        val source = key(from) ?: throw IOException("Not found on the remote: $from")
        val stored = files.remove(source)!!
        // rclone's move replaces a file at the destination; a case-only rename lands on the new case.
        key(to)?.let(files::remove)
        files[to] = stored
    }

    override suspend fun delete(path: String) {
        call("delete $path")
        files.remove(key(path) ?: throw IOException("Not found on the remote: $path"))
    }

    /** Whether a file on the remote holds [hash]: the library's copy there, which a pass brings back. */
    fun holds(hash: String): Boolean = files.values.any { sha256Bytes(it.bytes) == hash }

    /** The remote's paths with a short hash each, for a failure report. */
    fun describe(): List<String> = files.map { (path, stored) -> "$path=${sha256Bytes(stored.bytes).take(HASH_SHOWN)}" }

    private suspend fun call(what: String) {
        delay(random.nextLong(1, MAX_LATENCY_MILLIS))
        if (faultsOn && random.chance(options.failPercent)) throw IOException("Simulated remote failure: $what")
    }

    private fun key(path: String): String? =
        when {
            path in files -> path
            options.caseInsensitive -> files.keys.firstOrNull { it.equals(path, ignoreCase = true) }
            else -> null
        }

    private fun entry(
        path: String,
        stored: Stored,
    ) = RemoteEntry(
        path = path,
        size = stored.bytes.size.toLong(),
        mtimeMillis = stored.mtime,
        sha256 = if (options.reportsHashes) sha256Bytes(stored.bytes) else null,
    )

    private companion object {
        const val MAX_LATENCY_MILLIS = 200L
        const val HASH_SHOWN = 8
    }
}
