package dev.npn.core.sync

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
 * The rclone-backed [SyncTransport]. The executable and config file are read from the live
 * [config] on every call, so a path typed into settings takes effect without reopening the
 * library; [defaultBinary] is the platform's fallback (`rclone` on PATH, or an executable the app
 * ships).
 */
class RcloneTransport(
    private val config: suspend () -> CloudSyncConfig,
    private val defaultBinary: () -> String = { "rclone" },
) : SyncTransport {
    private suspend fun cli(): RcloneCli {
        val cfg = config()
        return RcloneCli(
            binary = cfg.rcloneBinary?.trim()?.takeIf { it.isNotEmpty() } ?: defaultBinary(),
            configPath = cfg.rcloneConfig?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun availability(): TransportAvailability {
        val cli = cli()
        return try {
            val version = cli.call("core/version")["version"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            TransportAvailability.Available(version)
        } catch (failure: RcloneException) {
            if (failure.status == null) {
                TransportAvailability.NotInstalled(cli.binary)
            } else {
                TransportAvailability.Failed(failure.message ?: "rclone did not answer")
            }
        }
    }

    override suspend fun listRemotes(): List<String> =
        cli()
            .call("config/listremotes")["remotes"]
            ?.takeIf { it !is JsonNull }
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()

    override fun open(remote: String): RemoteStore = RcloneRemoteStore(remote, ::cli)
}

/**
 * One rclone remote (`name:path` or a connection string) as a [RemoteStore]. Local sides of a
 * transfer are addressed the way rclone addresses them: the parent directory as the `fs`, the file
 * name as the `remote`.
 */
class RcloneRemoteStore(
    private val remote: String,
    private val cli: suspend () -> RcloneCli,
) : RemoteStore {
    override suspend fun list(): List<RemoteEntry> {
        // rclone answers "directory not found" for a root that does not exist yet, which would fail
        // every pass until someone created the folder by hand. Creating it is idempotent and cheap,
        // and an empty folder then lists as empty.
        cli().call(
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
            cli().call("operations/list", input)["list"]?.takeIf { it !is JsonNull }?.jsonArray
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
        val item = cli().call("operations/stat", input)["item"]
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
        cli().call(
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
        cli().call(
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
        cli().call(
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
