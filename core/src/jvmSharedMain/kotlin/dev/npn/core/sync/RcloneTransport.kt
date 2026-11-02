package dev.npn.core.sync

import dev.npn.core.util.reason
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Paths
import java.time.OffsetDateTime

/**
 * The rclone-backed [SyncTransport]. It owns one long-lived engine ([RcloneDaemon] by default) so
 * every transfer and every setup call shares the same process: a remote is small-talk, and a
 * persistent engine is what makes the interactive OAuth sign-in possible and keeps a pass from
 * spawning a process per file. The engine starts with the first call; [close] (the session's) is
 * final, so a caller that outlives the session gets [EngineProblem.CLOSED] rather than starting an
 * engine nothing would stop.
 */
class RcloneTransport(
    private val launch: RcloneLaunch,
    private val engineFactory: (RcloneLaunch) -> RcloneRpc = { RcloneDaemon(it) },
) : SyncTransport {
    private val lock = Any()
    private var engine: RcloneRpc? = null
    private var closed = false

    /** In-app provider setup, over the same engine the transfers use. */
    val setup: CloudSetup = RcloneCloudSetup { rpc() }

    override suspend fun availability(): TransportAvailability =
        try {
            val version = rpc().call("core/version")["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            TransportAvailability.Available(version)
        } catch (failure: RcloneException) {
            when (val problem = failure.engine) {
                EngineProblem.START_FAILED -> TransportAvailability.NotInstalled(launch.binary)
                null -> TransportAvailability.Failed(EngineProblem.ERROR, failure.reason())
                else -> TransportAvailability.Failed(problem, failure.reason())
            }
        }

    override fun open(remote: String): RemoteStore = RcloneRemoteStore(remote) { rpc() }

    /**
     * rclone reports a backend's failure as that backend's text, so the reason is read from it: the
     * markers below are the phrases rclone's backends and Go's network stack use. Whatever matches
     * none is [SyncFailure.UNKNOWN], and the UI shows the text itself.
     */
    override fun failureOf(failure: Throwable): SyncFailure {
        val text =
            generateSequence(failure) { it.cause }
                .mapNotNull { it.message }
                .joinToString(" ")
                .lowercase()
        return when {
            SIGN_IN_MARKERS.any(text::contains) -> SyncFailure.SIGN_IN_EXPIRED
            FULL_MARKERS.any(text::contains) -> SyncFailure.REMOTE_FULL
            NETWORK_MARKERS.any(text::contains) -> SyncFailure.REMOTE_UNREACHABLE
            else -> SyncFailure.UNKNOWN
        }
    }

    override fun close() {
        val current =
            synchronized(lock) {
                closed = true
                engine.also { engine = null }
            }
        current?.close()
    }

    private fun rpc(): RcloneRpc =
        synchronized(lock) {
            if (closed) throw RcloneException("rclone was closed", status = null, engine = EngineProblem.CLOSED)
            engine ?: engineFactory(launch).also { engine = it }
        }

    private companion object {
        val SIGN_IN_MARKERS =
            listOf(
                "invalid_grant",
                "token expired",
                "failed to refresh token",
                "couldn't fetch token",
                "cannot fetch token",
                "unauthorized",
                "401",
            )
        val FULL_MARKERS = listOf("quota", "insufficient storage", "insufficientstorage", "no space left", "507")
        val NETWORK_MARKERS =
            listOf(
                "no such host",
                "dial tcp",
                "connection refused",
                "connection reset",
                "network is unreachable",
                "i/o timeout",
                "tls handshake timeout",
                "context deadline exceeded",
                "temporary failure in name resolution",
            )
    }
}

/**
 * One rclone remote (`name:path` or a connection string) as a [RemoteStore]. Local sides of a
 * transfer are addressed the way rclone addresses them: the parent directory as the `fs`, the file
 * name as the `remote`.
 */
class RcloneRemoteStore(
    private val remote: String,
    private val rpc: suspend () -> RcloneRpc,
) : RemoteStore {
    override suspend fun list(): List<RemoteEntry> {
        // rclone answers "directory not found" for a root that does not exist yet, which would fail
        // every pass until someone created the folder by hand. Creating it is idempotent and cheap,
        // and an empty folder then lists as empty.
        rpc().call(
            "operations/mkdir",
            buildJsonObject {
                put("fs", remote)
                put("remote", "")
            },
        )
        val input =
            buildJsonObject {
                put("fs", remote)
                put("remote", "")
                putJsonObject("opt") {
                    put("recurse", true)
                    put("filesOnly", true)
                    put("noMimeType", true)
                    put("showHash", true)
                    putJsonArray("hashTypes") { add(JsonPrimitive("sha256")) }
                }
            }
        val list =
            rpc().call("operations/list", input)["list"]?.takeIf { it !is JsonNull }?.jsonArray
                ?: JsonArray(emptyList())
        return list.mapNotNull { it.jsonObject.toEntry() }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        val input =
            buildJsonObject {
                put("fs", remote)
                put("remote", path)
                putJsonObject("opt") {
                    put("showHash", true)
                    putJsonArray("hashTypes") { add(JsonPrimitive("sha256")) }
                }
            }
        val item = rpc().call("operations/stat", input)["item"]
        if (item == null || item is JsonNull) return null
        return item.jsonObject.toEntry()
    }

    override suspend fun download(
        path: String,
        toLocalPath: String,
    ) {
        val local = Paths.get(toLocalPath).toAbsolutePath()
        copyFile(
            srcFs = remote,
            srcRemote = path,
            dstFs = local.parent.toString(),
            dstRemote = local.fileName.toString(),
        )
    }

    override suspend fun upload(
        fromLocalPath: String,
        path: String,
    ) {
        val local = Paths.get(fromLocalPath).toAbsolutePath()
        copyFile(
            srcFs = local.parent.toString(),
            srcRemote = local.fileName.toString(),
            dstFs = remote,
            dstRemote = path,
        )
    }

    override suspend fun move(
        from: String,
        to: String,
    ) {
        rpc().call(
            "operations/movefile",
            buildJsonObject {
                put("srcFs", remote)
                put("srcRemote", from)
                put("dstFs", remote)
                put("dstRemote", to)
            },
        )
    }

    override suspend fun delete(path: String) {
        rpc().call(
            "operations/deletefile",
            buildJsonObject {
                put("fs", remote)
                put("remote", path)
            },
        )
    }

    private suspend fun copyFile(
        srcFs: String,
        srcRemote: String,
        dstFs: String,
        dstRemote: String,
    ) {
        rpc().call(
            "operations/copyfile",
            buildJsonObject {
                put("srcFs", srcFs)
                put("srcRemote", srcRemote)
                put("dstFs", dstFs)
                put("dstRemote", dstRemote)
            },
        )
    }

    private fun JsonObject.toEntry(): RemoteEntry? {
        if (this["IsDir"]?.jsonPrimitive?.contentOrNull == "true") return null
        val path = this["Path"]?.jsonPrimitive?.contentOrNull ?: return null
        val size = this["Size"]?.jsonPrimitive?.longOrNull ?: return null
        val modTime = this["ModTime"]?.jsonPrimitive?.contentOrNull
        val mtime =
            modTime?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L
        val hashes = this["Hashes"]?.takeIf { it !is JsonNull }?.jsonObject
        val sha256 =
            hashes?.get("sha256")?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf {
                it.length ==
                    SHA256_LENGTH
            }
        return RemoteEntry(path = path, size = size, mtimeMillis = mtime, sha256 = sha256)
    }

    private companion object {
        const val SHA256_LENGTH = 64
    }
}
