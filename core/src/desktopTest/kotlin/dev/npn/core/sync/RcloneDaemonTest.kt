package dev.npn.core.sync

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
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
}
