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

    private fun transport(remote: String? = null) =
        RcloneTransport(config = { CloudSyncConfig(remote = remote, rcloneBinary = binary) })

    private suspend fun rcloneAvailable(): Boolean = transport().availability() is TransportAvailability.Available

    @Test
    fun aMissingExecutableReadsAsNotInstalled() =
        runTest {
            val availability =
                RcloneTransport(config = { CloudSyncConfig(rcloneBinary = "/definitely/not/rclone") }).availability()
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
            try {
                // A root that does not exist yet lists as empty (and exists afterwards) instead of
                // failing the pass with "directory not found" until someone creates it by hand.
                val freshRoot = remoteDir.resolve("not-yet-created")
                Files.exists(freshRoot) shouldBe false
                transport(freshRoot.toString()).open(freshRoot.toString()).list() shouldBe emptyList()
                Files.isDirectory(freshRoot) shouldBe true

                val store = transport(remoteDir.toString()).open(remoteDir.toString())
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
                remoteDir.toFile().deleteRecursively()
                localDir.toFile().deleteRecursively()
            }
        }
}
