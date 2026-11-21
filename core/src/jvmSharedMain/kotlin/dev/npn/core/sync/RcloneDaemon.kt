package dev.npn.core.sync

import dev.npn.core.util.NpnJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

/**
 * The long-lived rclone engine: one `rclone rcd` process answering the remote-control HTTP API on a
 * loopback port for as long as the app session, so the interactive setup flow (remote picker, OAuth,
 * config round trips) can span calls instead of restarting rclone per step. This is the production
 * [RcloneRpc]; [RcloneCli] is the stateless one-process-per-call variant.
 *
 * The process starts lazily on the first call, and concurrent first calls wait on the same start. It
 * carries per-instance random Basic-auth credentials so no other local process can drive it; they
 * reach rclone through its environment (`RCLONE_RC_USER`/`RCLONE_RC_PASS`), never its command line,
 * which every local user can read, and are never logged or echoed in an error. A daemon that dies
 * mid-session is restarted once before the failure is surfaced.
 *
 * A cancelled call ends at once: a socket read ignores thread interrupts, so the request's connection
 * is closed instead, and rclone cancels the operation whose request went away. [close] is final: it
 * stops the process, and every later call fails with [EngineProblem.CLOSED] instead of starting a new
 * one nothing would stop. A JVM that exits without closing still stops the process (a shutdown hook).
 */
class RcloneDaemon(
    private val launch: RcloneLaunch,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RcloneRpc {
    private val lifecycle = Mutex()
    private val lock = Any()

    @Volatile
    private var running: Running? = null

    /** Set by [close], under [lock]: a start straddling it stops its process instead of publishing it. */
    @Volatile
    private var closed = false

    private val user = "npn-${Uuid.random().toHexString()}"
    private val password = Uuid.random().toHexString()
    private val authorization: String =
        "Basic ${Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))}"

    override suspend fun call(
        method: String,
        input: JsonObject,
    ): JsonObject {
        val session = ensureRunning()
        return try {
            post(session.port, method, input)
        } catch (failure: RcloneException) {
            throw failure
        } catch (expected: IOException) {
            // A cancelled call ends by closing its own connection, which fails the exchange with an
            // IOException too: that is no sign of a dead process, and restarting the one process
            // every other call shares would cut those calls off. Cancelled, it ends here.
            currentCoroutineContext().ensureActive()
            // The process may have died between calls; one restart is allowed before giving up.
            retryAfterRestart(session, method, input)
        }
    }

    override fun close() {
        val session =
            synchronized(lock) {
                closed = true
                running.also { running = null }
            }
        session?.stop()
    }

    private suspend fun ensureRunning(): Running {
        running?.let { return it }
        return lifecycle.withLock {
            running?.let { return it }
            startSession(stale = null)
        }
    }

    /**
     * Starts a fresh process, replacing [stale], and publishes it as the live session. Runs under
     * [lifecycle], so concurrent calls share a single start. If [close] came first, nothing starts;
     * if it overtook the start, the new process is stopped instead of published.
     */
    private suspend fun startSession(stale: Running?): Running {
        if (closed) throw closedFailure()
        stale?.stop()
        val session = launchProcess()
        val published =
            synchronized(lock) {
                if (closed) return@synchronized false
                running = session
                true
            }
        if (!published) {
            session.stop()
            throw closedFailure()
        }
        return session
    }

    private fun closedFailure() = RcloneException("rclone was closed", status = null, engine = EngineProblem.CLOSED)

    private suspend fun restart(stale: Running): Running =
        lifecycle.withLock {
            val current = running
            if (current !== stale) {
                return current ?: throw closedFailure()
            }
            // The lock's fast path does not check for cancellation, and stopping [stale] is for good.
            currentCoroutineContext().ensureActive()
            startSession(stale)
        }

    private suspend fun retryAfterRestart(
        stale: Running,
        method: String,
        input: JsonObject,
    ): JsonObject {
        val restarted = restart(stale)
        return try {
            post(restarted.port, method, input)
        } catch (retry: RcloneException) {
            throw retry
        } catch (retry: IOException) {
            currentCoroutineContext().ensureActive()
            throw RcloneException(
                "rclone is not reachable after a restart: ${retry.message}",
                status = null,
                cause = retry,
                engine = EngineProblem.UNREACHABLE,
            )
        }
    }

    private suspend fun launchProcess(): Running {
        val port = withContext(io) { ServerSocket(0).use { it.localPort } }
        val process =
            withContext(io) {
                try {
                    ProcessBuilder(command(port))
                        .redirectErrorStream(false)
                        .apply {
                            environment()["RCLONE_RC_USER"] = user
                            environment()["RCLONE_RC_PASS"] = password
                        }.start()
                } catch (failure: IOException) {
                    throw RcloneException(
                        "Could not start rclone at '${launch.binary}': ${failure.message}",
                        status = null,
                        cause = failure,
                        engine = EngineProblem.START_FAILED,
                    )
                }
            }
        val session =
            Running(
                process = process,
                port = port,
                stderr = StreamDrain(process.errorStream, DIAGNOSTICS_LIMIT),
                stdout = StreamDrain(process.inputStream, NO_CAPTURE),
            )
        // A process that never became ready (or whose wait was cancelled) is stopped, not orphaned.
        var ready = false
        try {
            awaitReady(session)
            ready = true
        } finally {
            if (!ready) session.stop()
        }
        return session
    }

    private fun command(port: Int): List<String> =
        buildList {
            add(launch.binary)
            add("rcd")
            add("--rc-addr=127.0.0.1:$port")
            add("--log-level=ERROR")
            // Sizes a Dropbox upload batch (its default `sync` batch mode takes `--transfers`), so
            // the window of uploads a pass keeps in flight commits as one.
            add("--transfers=${TransferLimits.MOST}")
            launch.configPath?.let {
                add("--config")
                add(it)
            }
            launch.cacheDir?.let {
                add("--cache-dir")
                add(it)
            }
        }

    /** Polls `core/version` until rclone answers, the process dies, or the deadline passes. */
    private suspend fun awaitReady(session: Running) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!session.process.isAlive) {
                throw RcloneException(
                    "rclone exited before it was ready: ${session.diagnostics()}",
                    status = null,
                    engine = EngineProblem.EXITED,
                )
            }
            if (probe(session.port)) return
            if (System.nanoTime() >= deadline) {
                throw RcloneException(
                    "rclone was not ready within $READY_TIMEOUT_MS ms: ${session.diagnostics()}",
                    status = null,
                    engine = EngineProblem.NOT_READY,
                )
            }
            delay(READY_POLL_MS)
        }
    }

    private suspend fun probe(port: Int): Boolean =
        try {
            // A non-2xx answer is RcloneException (an IOException), just a "not ready yet" here.
            post(port, VERSION_METHOD, JsonObject(emptyMap()), READY_CONNECT_TIMEOUT_MS, READY_READ_TIMEOUT_MS)
            true
        } catch (expected: IOException) {
            false
        }

    /**
     * One request. Cancelling the caller closes the connection from [io], which is what unblocks a
     * read waiting on a long transfer: interrupting the thread would not, and the call would hold the
     * session's close until rclone finished on its own.
     */
    private suspend fun post(
        port: Int,
        method: String,
        input: JsonObject,
        connectTimeoutMillis: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMillis: Int = READ_TIMEOUT_MS,
    ): JsonObject =
        coroutineScope {
            val connection = URL("http://127.0.0.1:$port/$method").openConnection() as HttpURLConnection
            val abort =
                launch(io, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        connection.disconnect()
                    }
                }
            try {
                withContext(io) { exchange(connection, input, connectTimeoutMillis, readTimeoutMillis) }
            } finally {
                abort.cancel()
            }
        }

    private fun exchange(
        connection: HttpURLConnection,
        input: JsonObject,
        connectTimeoutMillis: Int,
        readTimeoutMillis: Int,
    ): JsonObject {
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", authorization)
            val body = input.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in OK_FIRST..OK_LAST) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in OK_FIRST..OK_LAST) throw failure(text, status)
            return parseObject(text) ?: JsonObject(emptyMap())
        } finally {
            connection.disconnect()
        }
    }

    /** Turns rclone's `{"error":...,"status":...}` body into the failure callers expect. */
    private fun failure(
        body: String,
        httpStatus: Int,
    ): RcloneException {
        val parsed = parseObject(body)
        val message =
            (parsed?.get("error") as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: body.trim().takeIf { it.isNotEmpty() }
                ?: "rclone responded with status $httpStatus"
        val status = (parsed?.get("status") as? JsonPrimitive)?.intOrNull ?: httpStatus
        return RcloneException(message, status)
    }

    private fun parseObject(text: String): JsonObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { NpnJson.Lenient.parseToJsonElement(trimmed).jsonObject }.getOrNull()
    }

    /**
     * One live `rclone rcd` process and the drains that keep its pipes from filling. A shutdown hook
     * stops it should the JVM exit without closing the daemon (a session close that timed out, a
     * quit from the window manager), so no engine outlives the app holding the signed-in remotes.
     */
    private class Running(
        val process: Process,
        val port: Int,
        private val stderr: StreamDrain,
        private val stdout: StreamDrain,
    ) {
        private val shutdownHook =
            Thread { process.destroyForcibly() }.also { hook ->
                hook.name = "rclone-rcd-shutdown"
                Runtime.getRuntime().addShutdownHook(hook)
            }

        fun diagnostics(): String = stderr.text()

        /** Stops the process, escalating to SIGKILL if it lingers, then joins its drains. */
        fun stop() {
            // Throws while the JVM is already shutting down, when the hook is about to run anyway.
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
            process.destroy()
            if (!process.waitFor(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            stderr.join()
            stdout.join()
        }
    }

    /**
     * Reads one process pipe on its own daemon thread, keeping at most [captureLimit] characters, so a
     * chatty engine can neither fill the OS pipe nor grow the buffer without bound. A limit of zero
     * drains the pipe and keeps nothing (stdout).
     */
    private class StreamDrain(
        stream: InputStream,
        private val captureLimit: Int,
    ) {
        private val captured = StringBuilder()
        private val thread =
            Thread {
                try {
                    stream.bufferedReader().use { reader ->
                        val chunk = CharArray(CHUNK_SIZE)
                        while (true) {
                            val read = reader.read(chunk)
                            if (read < 0) break
                            record(chunk, read)
                        }
                    }
                } catch (expected: IOException) {
                    // The pipe closes when the process is stopped; there is nothing to report.
                }
            }.apply {
                name = "rclone-rcd-io"
                isDaemon = true
                start()
            }

        @Synchronized
        private fun record(
            chunk: CharArray,
            length: Int,
        ) {
            if (captureLimit <= 0) return
            captured.append(chunk, 0, length)
            if (captured.length > captureLimit) captured.delete(0, captured.length - captureLimit)
        }

        @Synchronized
        fun text(): String = captured.toString().trim()

        fun join() = thread.join(JOIN_TIMEOUT_MS)
    }

    private companion object {
        const val VERSION_METHOD = "core/version"
        const val OK_FIRST = 200
        const val OK_LAST = 299
        const val READY_TIMEOUT_MS = 15_000L
        const val READY_POLL_MS = 100L
        const val READY_CONNECT_TIMEOUT_MS = 500
        const val READY_READ_TIMEOUT_MS = 1_000
        const val CONNECT_TIMEOUT_MS = 2_000

        /** No read timeout: a real transfer may legitimately take minutes; cancellation disconnects. */
        const val READ_TIMEOUT_MS = 0
        const val SHUTDOWN_TIMEOUT_MS = 2_000L
        const val JOIN_TIMEOUT_MS = 1_000L
        const val DIAGNOSTICS_LIMIT = 4 * 1024
        const val NO_CAPTURE = 0
        const val CHUNK_SIZE = 1_024
    }
}
