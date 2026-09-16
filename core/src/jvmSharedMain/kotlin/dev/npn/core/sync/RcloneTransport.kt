package dev.npn.core.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The rclone-backed [SyncTransport]. It owns one long-lived engine per launch configuration
 * ([RcloneDaemon] by default) so every transfer and every setup call shares the same process: a
 * remote is small-talk, and a persistent engine is what makes the interactive OAuth sign-in
 * possible and keeps a pass from spawning a process per file.
 *
 * The launch parameters are read from the live [config] on every call, so a path typed into
 * settings takes effect without reopening the library; [defaults] is what the host supplies when
 * the settings name nothing (the bundled executable, the app-private config file). A change to
 * either closes the old engine and starts a fresh one on the next call.
 */
class RcloneTransport(
    private val config: suspend () -> CloudSyncConfig,
    private val defaults: RcloneDefaults = RcloneDefaults(),
    private val engineFactory: (RcloneLaunch) -> RcloneRpc = { RcloneDaemon(it) },
) : SyncTransport {
    private val gate = Mutex()
    private var engine: RcloneRpc? = null
    private var launched: RcloneLaunch? = null

    /** In-app provider setup, over the same engine the transfers use. */
    val setup: CloudSetup = RcloneCloudSetup(::rpc)

    override suspend fun availability(): TransportAvailability {
        val spec = launch()
        return try {
            val version = rpc().call("core/version")["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            TransportAvailability.Available(version)
        } catch (failure: RcloneException) {
            if (failure.status == null) {
                TransportAvailability.NotInstalled(spec.binary)
            } else {
                TransportAvailability.Failed(failure.message ?: "rclone did not answer")
            }
        }
    }

    override suspend fun listRemotes(): List<String> =
        rpc()
            .call("config/listremotes")["remotes"]
            ?.takeIf { it !is JsonNull }
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

    override fun open(remote: String): RemoteStore = RcloneRemoteStore(remote, ::rpc)

    override fun close() {
        val current = engine
        engine = null
        launched = null
        current?.close()
    }

    private suspend fun launch(): RcloneLaunch {
        val cfg = config()
        return RcloneLaunch(
            binary = cfg.rcloneBinary?.trim()?.takeIf { it.isNotEmpty() } ?: defaults.binary,
            configPath = cfg.rcloneConfig?.trim()?.takeIf { it.isNotEmpty() } ?: defaults.configPath,
            cacheDir = defaults.cacheDir,
        )
    }

    /** The current engine, starting a fresh one when the launch parameters changed. */
    private suspend fun rpc(): RcloneRpc {
        val spec = launch()
        return gate.withLock {
            val current = engine
            if (current != null && launched == spec) return@withLock current
            current?.close()
            engineFactory(spec).also {
                engine = it
                launched = spec
            }
        }
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
