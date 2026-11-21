package dev.npn.core.sync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * [RcloneDaemon] against a real rclone: it starts lazily, answers calls, reuses the process across
 * calls, and starts over after [RcloneDaemon.close]. The live tests run only when rclone is
 * reachable — `NPN_RCLONE=/path/to/rclone`, or `rclone` on PATH — and otherwise pass after printing
 * that they skipped, because CI machines need not have it. The missing-executable test always runs.
 */
class RcloneDaemonTest {
    private val binary: String = System.getenv("NPN_RCLONE")?.takeIf { it.isNotBlank() } ?: "rclone"

    /** Proves rclone can actually be driven; a missing binary reads as "not available", not a bug. */
    private suspend fun rcloneAvailable(): Boolean =
        try {
            // The stateless CLI, deliberately not the daemon under test: a broken daemon must fail a
            // live test rather than make it silently skip.
            RcloneCli(binary).call("core/version")
            true
        } catch (expected: RcloneException) {
            false
        }

    private suspend fun requireRclone(): Boolean {
        if (rcloneAvailable()) return true
        println("SKIPPED RcloneDaemonTest: rclone not found ($binary)")
        return false
    }

    @Test
    fun aCallToAMissingExecutableFailsWithoutAStatus() =
        runTest {
            // Status null is how the transport tells "not installed" from "rclone answered with an
            // error", so a process-start failure must surface exactly that way.
            val failure =
                assertFailsWith<RcloneException> {
                    RcloneDaemon(RcloneLaunch("/definitely/not/rclone")).call("core/version")
                }
            failure.status.shouldBeNull()
            failure.message.shouldNotBeNull() shouldContain "/definitely/not/rclone"
            failure.cause.shouldNotBeNull()
        }

    @Test
    fun aStartedDaemonReportsItsVersion() =
        // Real process I/O: runBlocking rather than runTest, whose virtual-time scheduler treats a
        // slow process as a stalled dispatch and can time the test out on a loaded CI machine.
        runBlocking {
            if (!requireRclone()) return@runBlocking
            RcloneDaemon(RcloneLaunch(binary)).use { daemon ->
                val version = daemon.call("core/version")["version"]?.jsonPrimitive?.contentOrNull
                version.shouldNotBeNull()
                version.shouldNotBe("")
            }
            Unit
        }

    @Test
    fun twoCallsReuseTheSameProcess() =
        runBlocking {
            if (!requireRclone()) return@runBlocking
            RcloneDaemon(RcloneLaunch(binary)).use { daemon ->
                val first = daemon.call("core/pid")["pid"]?.jsonPrimitive?.contentOrNull
                val second = daemon.call("core/pid")["pid"]?.jsonPrimitive?.contentOrNull
                first.shouldNotBeNull()
                second shouldBe first
            }
            Unit
        }

    @Test
    fun closeIsFinalSoNoLaterCallStartsAnEngineNobodyStops() =
        runBlocking {
            if (!requireRclone()) return@runBlocking
            val daemon = RcloneDaemon(RcloneLaunch(binary))
            daemon
                .call("core/pid")["pid"]
                ?.jsonPrimitive
                ?.contentOrNull
                .shouldNotBeNull()
            // Idempotent: the second close must not throw.
            daemon.close()
            daemon.close()
            assertFailsWith<RcloneException> { daemon.call("core/pid") }.engine shouldBe EngineProblem.CLOSED
        }

    @Test
    fun theCredentialsAreNotOnTheCommandLine() =
        runBlocking {
            if (!requireRclone()) return@runBlocking
            RcloneDaemon(RcloneLaunch(binary)).use { daemon ->
                val pid =
                    daemon
                        .call("core/pid")["pid"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .shouldNotBeNull()
                val commandLine =
                    ProcessHandle
                        .of(pid.toLong())
                        .flatMap { it.info().arguments() }
                        .map { it.joinToString(" ") }
                        .orElse("")
                commandLine.contains("--rc-user") shouldBe false
                commandLine.contains("--rc-pass") shouldBe false
            }
            Unit
        }

    /**
     * A cancelled call ends by closing its connection, which fails the exchange with an IOException:
     * that must not read as a dead process and restart the one process every other call shares.
     */
    @Test
    fun aCancelledCallLeavesTheProcessRunning() =
        runBlocking {
            if (!requireRclone()) return@runBlocking
            // A server that takes the connection and never answers, so a listing on it hangs.
            ServerSocket(0).use { silent ->
                val held = AtomicReference<Socket?>()
                val acceptor = Thread { runCatching { held.set(silent.accept()) } }.apply { start() }
                RcloneDaemon(RcloneLaunch(binary)).use { daemon ->
                    val before =
                        daemon
                            .call("core/pid")["pid"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .shouldNotBeNull()
                    val hanging =
                        async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                            daemon.call(
                                "operations/list",
                                buildJsonObject {
                                    put("fs", JsonPrimitive(":http,url='http://127.0.0.1:${silent.localPort}/':"))
                                    put("remote", JsonPrimitive(""))
                                },
                            )
                        }
                    withTimeout(TimeUnit.SECONDS.toMillis(WAIT_SECONDS)) {
                        while (held.get() == null) delay(POLL_MS)
                    }
                    hanging.cancelAndJoin()

                    daemon.call("core/pid")["pid"]?.jsonPrimitive?.contentOrNull shouldBe before
                }
                held.get()?.close()
                acceptor.join()
            }
        }

    private companion object {
        const val WAIT_SECONDS = 20L
        const val POLL_MS = 20L
    }
}
