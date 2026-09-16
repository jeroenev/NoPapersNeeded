package dev.npn.core.sync

import dev.npn.core.util.NpnJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.io.InputStream

/**
 * Drives the rclone executable through its remote-control API in loopback mode: one process per
 * call, `rclone rc --loopback <method> --json <input>`, JSON in and JSON out. This is the stateless
 * [RcloneRpc]; [RcloneDaemon] is the long-lived variant the app normally uses. Following the
 * existing Tesseract precedent, no native binding is involved and a missing binary degrades to
 * "unavailable".
 */
class RcloneCli(
    val binary: String,
    private val configPath: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RcloneRpc {
    /** Nothing outlives a call: each one owns and reaps its own process. */
    override fun close() = Unit

    override suspend fun call(
        method: String,
        input: JsonObject,
    ): JsonObject =
        withContext(io) {
            val command = ArrayList<String>()
            command += listOf(binary, "rc", "--loopback", method, "--json", input.toString(), "--log-level", "ERROR")
            if (configPath != null) command += listOf("--config", configPath)
            val process =
                try {
                    ProcessBuilder(command).redirectErrorStream(false).start()
                } catch (failure: IOException) {
                    throw RcloneException(
                        "Could not start rclone at '$binary': ${failure.message}",
                        status = null,
                        cause = failure,
                    )
                }
            try {
                val stdout = drain(process.inputStream)
                val stderr = drain(process.errorStream)
                val exit = runInterruptible { process.waitFor() }
                stdout.join()
                stderr.join()
                val body = parseObject(stdout.text)
                if (exit != 0) {
                    val message =
                        body?.get("error")?.jsonPrimitive?.contentOrNull
                            ?: stderr.text.trim().ifEmpty { "rclone exited with status $exit" }
                    throw RcloneException(message, body?.get("status")?.jsonPrimitive?.intOrNull ?: exit)
                }
                body ?: JsonObject(emptyMap())
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }

    private fun parseObject(text: String): JsonObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { NpnJson.Lenient.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    /** Reads a process stream on its own thread so neither pipe can fill up and block the other. */
    private class Drain(
        stream: InputStream,
    ) {
        private val buffer = StringBuilder()
        private val thread =
            Thread {
                try {
                    stream.bufferedReader().use { buffer.append(it.readText()) }
                } catch (_: IOException) {
                }
            }.apply {
                isDaemon = true
                start()
            }

        val text: String get() = buffer.toString()

        fun join() = thread.join(JOIN_TIMEOUT_MS)
    }

    private fun drain(stream: InputStream) = Drain(stream)

    private companion object {
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
