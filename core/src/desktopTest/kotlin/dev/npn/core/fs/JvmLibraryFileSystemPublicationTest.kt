package dev.npn.core.fs

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Behaviour on a filesystem without `link(2)` — Android's FUSE-backed shared storage, exFAT —
 * simulated through the [NoReplaceLinker] seam, since every filesystem a desktop test can reach has
 * hard links. Publication then falls back to the exclusive rename, never to a replacing one.
 */
class JvmLibraryFileSystemPublicationTest {
    private lateinit var root: Path

    private val noLinks = NoReplaceLinker { _, _ -> false }

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-publication-test")
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun fs() = JvmLibraryFileSystem(root, PathExternalIo, noLinks)

    private fun stage(
        name: String,
        bytes: ByteArray,
    ): String {
        val dir = Files.createDirectories(root.resolve("bucket"))
        Files.write(dir.resolve(name), bytes)
        return "bucket/$name"
    }

    @Test
    fun renameFallbackPublishesVerifiedBytesAndReportsALaterDuplicate() {
        val fs = fs()
        val bytes = "tx".encodeToByteArray()
        val hash = sha256Bytes(bytes)

        val first = runBlocking { fs.publishVerifiedObjectOnce(stage("s1.part", bytes), "bucket/obj", hash) }
        first shouldBe VerifiedPublicationResult.PUBLISHED
        Files.readAllBytes(root.resolve("bucket/obj")) shouldBe bytes
        Files.exists(root.resolve("bucket/s1.part")) shouldBe false

        val second = runBlocking { fs.publishVerifiedObjectOnce(stage("s2.part", bytes), "bucket/obj", hash) }
        second shouldBe VerifiedPublicationResult.ALREADY_PRESENT
        Files.exists(root.resolve("bucket/s2.part")) shouldBe false
    }

    @Test
    fun renameFallbackNeverReplacesACanonicalObjectWithOtherBytes() {
        val fs = fs()
        Files.createDirectories(root.resolve("bucket"))
        Files.write(root.resolve("bucket/obj"), "other".encodeToByteArray())
        val bytes = "tx".encodeToByteArray()
        assertFailsWith<IllegalStateException> {
            runBlocking { fs.publishVerifiedObjectOnce(stage("s1.part", bytes), "bucket/obj", sha256Bytes(bytes)) }
        }
        Files.readAllBytes(root.resolve("bucket/obj")).decodeToString() shouldBe "other"
    }

    @Test
    fun exclusiveRenameAllowsOnlyOneConcurrentPublisher() {
        val contenders = 8
        val barrier = CyclicBarrier(contenders)
        val executor = Executors.newFixedThreadPool(contenders)
        try {
            val results =
                (0 until contenders).map { index ->
                    val source = root.resolve("source-$index")
                    Files.write(source, "$index".encodeToByteArray())
                    executor.submit(
                        Callable {
                            barrier.await(10, TimeUnit.SECONDS)
                            try {
                                NoReplaceMove.move(source, root.resolve("winner"))
                                index
                            } catch (_: java.nio.file.FileAlreadyExistsException) {
                                null
                            }
                        },
                    )
                }
            val winners = results.mapNotNull { it.get(10, TimeUnit.SECONDS) }
            winners.size shouldBe 1
            Files.readAllBytes(root.resolve("winner")).decodeToString() shouldBe "${winners.single()}"
            for (index in 0 until contenders) {
                Files.exists(root.resolve("source-$index")) shouldBe (index != winners.single())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun exclusiveRenameRefusesEvenADanglingSymlink() {
        val source = root.resolve("source")
        val target = root.resolve("target")
        Files.write(source, "ours".encodeToByteArray())
        Files.createSymbolicLink(target, root.resolve("absent"))
        assertFailsWith<java.nio.file.FileAlreadyExistsException> { NoReplaceMove.move(source, target) }
        Files.isSymbolicLink(target) shouldBe true
        Files.readAllBytes(source).decodeToString() shouldBe "ours"
    }

    @Test
    fun aLinkFailureDuringACrossDirectoryMoveDoesNotDisablePublication() {
        // Simulates an EXDEV on one cross-directory move (e.g. a bind-mounted subtree) on a
        // filesystem whose bucket-local hard links otherwise work fine: the failure must be
        // call-specific, never latched into "this filesystem has no link(2)" (see linksUnavailable).
        var crossDirectoryFailureUsed = false
        val flaky =
            NoReplaceLinker { from, to ->
                if (!crossDirectoryFailureUsed && from.parent != to.parent) {
                    crossDirectoryFailureUsed = true
                    false
                } else {
                    Files.createLink(to, from)
                    true
                }
            }
        val fs = JvmLibraryFileSystem(root, PathExternalIo, flaky)
        runBlocking {
            fs.writeBytesOnce("a/one.txt", "1".encodeToByteArray())
            fs.move("a/one.txt", "b/one.txt")
            fs.exists("a/one.txt") shouldBe false
            fs.readBytes("b/one.txt").decodeToString() shouldBe "1"

            // A same-bucket publish afterwards must still use link(2) rather than having been
            // permanently downgraded by the cross-directory move's failure above.
            val bytes = "tx".encodeToByteArray()
            val result = fs.publishVerifiedObjectOnce(stage("s1.part", bytes), "bucket/obj", sha256Bytes(bytes))
            result shouldBe VerifiedPublicationResult.PUBLISHED
        }
    }

    @Test
    fun ordinaryWritesAndMovesKeepWriteOnceWithoutLinks() {
        val fs = fs()
        runBlocking {
            fs.writeBytesOnce("a/one.txt", "1".encodeToByteArray())
            assertFailsWith<IllegalStateException> { fs.writeBytesOnce("a/one.txt", "2".encodeToByteArray()) }
            fs.readBytes("a/one.txt").decodeToString() shouldBe "1"

            fs.writeBytesOnce("a/two.txt", "2".encodeToByteArray())
            assertFailsWith<IllegalStateException> { fs.move("a/two.txt", "a/one.txt") }
            fs.move("a/two.txt", "b/two.txt")
            fs.exists("a/two.txt") shouldBe false
            fs.readBytes("b/two.txt").decodeToString() shouldBe "2"
        }
        leftoverTempFiles().shouldBeEmpty()
    }

    @Test
    fun importExternalStreamsBytesRefusesAnExistingTargetAndLeavesNoTempFiles() {
        val fs = fs()
        val external = Files.createTempFile("npn-external", ".pdf")
        try {
            val bytes = ByteArray(200_000) { (it % 251).toByte() }
            Files.write(external, bytes)
            runBlocking {
                fs.importExternal(ExternalSource(external), "in/doc.pdf")
                fs.readBytes("in/doc.pdf") shouldBe bytes
                assertFailsWith<IllegalStateException> { fs.importExternal(ExternalSource(external), "in/doc.pdf") }
            }
            leftoverTempFiles().shouldBeEmpty()
        } finally {
            Files.deleteIfExists(external)
        }
    }

    private fun leftoverTempFiles(): List<Path> =
        Files.walk(root).use { stream -> stream.filter { it.name.startsWith(".npn-tmp-") }.toList() }
}
