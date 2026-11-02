package dev.npn.core.sync

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/**
 * The launch parameters of the rclone engine the transport drives, the host's to choose: [binary]
 * is the bundled executable (`rclone` on PATH when the build ships none); [configPath] is the
 * app-private `rclone.conf`, so credentials never land in the user's own rclone setup; [cacheDir]
 * keeps rclone's cache inside the app's storage on a host whose default location may not exist.
 */
data class RcloneLaunch(
    val binary: String,
    val configPath: String? = null,
    val cacheDir: String? = null,
)

/**
 * One request against rclone's remote-control API. Implementations throw [RcloneException] on a
 * failure, with a null [RcloneException.status] when the engine itself could not be reached.
 *
 * The production implementation is [RcloneDaemon], one long-lived `rclone rcd` process per app
 * session, so the interactive config/OAuth flow can span calls; [RcloneCli] is the stateless
 * `rclone rc --loopback` variant, one process per call. Everything above the seam — the transport,
 * the setup wizard, the folder picker — is written against this interface.
 */
interface RcloneRpc : AutoCloseable {
    suspend fun call(
        method: String,
        input: JsonObject = JsonObject(emptyMap()),
    ): JsonObject
}

/**
 * A failed rclone call. [status] is rclone's RC status code when the call reached rclone (500 for
 * an operation error, 404 for an unknown method); null means the engine itself could not be used,
 * and [engine] then says how: a missing executable shows up as [EngineProblem.START_FAILED].
 */
class RcloneException(
    message: String,
    val status: Int?,
    cause: Throwable? = null,
    val engine: EngineProblem? = if (status == null) EngineProblem.UNREACHABLE else null,
) : IOException(message, cause)
