package dev.npn.core.sync

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The real rclone executable against a local directory used as the remote. The live test runs only
 * when rclone is reachable — `NPN_RCLONE=/path/to/rclone`, or `rclone` on PATH — and otherwise
 * passes after printing that it skipped, because CI machines need not have it; everything above
 * the adapter is covered by [SyncEngineTest] with a directory store. The two tests that need no
 * rclone always run.
 */
class RcloneTransportTest {
    private val binary: String = System.getenv("NPN_RCLONE")?.takeIf { it.isNotBlank() } ?: "rclone"

    private fun transport() = RcloneTransport(RcloneLaunch(binary))

    private suspend fun rcloneAvailable(): Boolean =
        transport().let { transport ->
            try {
                transport.availability() is TransportAvailability.Available
            } finally {
                transport.close()
            }
        }

    @Test
    fun aMissingExecutableReadsAsNotInstalled() =
        runTest {
            val availability = RcloneTransport(RcloneLaunch("/definitely/not/rclone")).availability()
            availability.shouldBeInstanceOf<TransportAvailability.NotInstalled>().lookedFor shouldBe
                "/definitely/not/rclone"
        }

    @Test
    fun aCallToAMissingExecutableFailsWithoutAStatus() =
        runTest {
            // Status null is how [RcloneTransport.availability] tells "not installed" from "rclone
            // answered with an error", so the process-start failure must surface exactly that way.
            val failure =
                assertFailsWith<RcloneException> { RcloneCli("/definitely/not/rclone").call("core/version") }
            failure.status.shouldBeNull()
            failure.message.shouldNotBeNull() shouldContain "/definitely/not/rclone"
            failure.cause.shouldNotBeNull()
        }

    @Test
    fun theStoreRoundTripsThroughRcloneWhenPresent() =
        // Real process I/O: runBlocking rather than runTest, whose virtual-time scheduler treats a
        // slow process as a stalled dispatch and can time the test out on a loaded CI machine.
        runBlocking {
            if (!rcloneAvailable()) {
                // Deliberately a pass, not a failure: the adapter is optional on the machine running
                // the suite. The line below is the only trace that nothing was exercised.
                println("SKIPPED RcloneTransportTest round-trip: rclone not found ($binary)")
                return@runBlocking
            }
            val remoteDir = Files.createTempDirectory("npn-rclone-remote")
            val localDir = Files.createTempDirectory("npn-rclone-local")
            val transport = transport()
            try {
                // A root that does not exist yet lists as empty (and exists afterwards) instead of
                // failing the pass with "directory not found" until someone creates it by hand.
                val freshRoot = remoteDir.resolve("not-yet-created")
                Files.exists(freshRoot) shouldBe false
                transport.open(freshRoot.toString()).list() shouldBe emptyList()
                Files.isDirectory(freshRoot) shouldBe true

                val store = transport.open(remoteDir.toString())
                val source = localDir.resolve("a.pdf")
                Files.write(source, "%PDF-1.4 hello".encodeToByteArray())

                store.upload(source.toString(), "inbox/a.pdf")
                val listed = store.list()
                listed.map { it.path } shouldContainExactly listOf("inbox/a.pdf")
                listed.single().size shouldBe Files.size(source)
                // The local backend computes SHA-256 on request.
                listed.single().sha256 shouldBe DirectoryStaging(localDir).sha256(source.toString())
                store.stat("inbox/a.pdf").shouldNotBeNull().mtimeMillis shouldBe listed.single().mtimeMillis
                store.stat("inbox/missing.pdf").shouldBeNull()

                store.move("inbox/a.pdf", "2026/b.pdf")
                store.stat("inbox/a.pdf").shouldBeNull()
                val target = localDir.resolve("back.pdf")
                store.download("2026/b.pdf", target.toString())
                Files.readAllBytes(target).decodeToString() shouldBe "%PDF-1.4 hello"

                store.delete("2026/b.pdf")
                store.list().shouldBe(emptyList())
                // rclone answered, with an error of its own: a status, unlike a process that never
                // started. Its value differs per engine (500 from `rc --loopback`, 404 from `rcd`).
                val failure = assertFailsWith<RcloneException> { store.delete("2026/b.pdf") }
                failure.status.shouldNotBeNull()
            } finally {
                transport.close()
                remoteDir.toFile().deleteRecursively()
                localDir.toFile().deleteRecursively()
            }
        }

    @Test
    fun aClosedTransportRefusesInsteadOfStartingAnEngine() =
        runTest {
            val transport = RcloneTransport(RcloneLaunch("/definitely/not/rclone"))
            transport.close()

            val availability = transport.availability().shouldBeInstanceOf<TransportAvailability.Failed>()
            availability.problem shouldBe EngineProblem.CLOSED
        }

    @Test
    fun failuresAreReadForTheReasonTheUserCanActOn() {
        val transport = RcloneTransport(RcloneLaunch("rclone"))

        fun reason(text: String) = transport.failureOf(RcloneException(text, status = 500))

        reason("couldn't fetch token: invalid_grant") shouldBe SyncFailure.SIGN_IN_EXPIRED
        reason("googleapi: Error 403: The user's Drive storage quota has been exceeded") shouldBe
            SyncFailure.REMOTE_FULL
        reason("dial tcp: lookup www.googleapis.com: no such host") shouldBe SyncFailure.REMOTE_UNREACHABLE
        reason("object not found") shouldBe SyncFailure.UNKNOWN
    }
}
