package dev.npn.core.fs

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Behaviour on a filesystem without `link(2)` — Android's FUSE-backed shared storage — simulated
 * through the [NoReplaceLinker] seam, since every filesystem a desktop test can reach has hard
 * links. [ObjectPublication] decides whether that is a hard stop or a verified rename.
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

    private fun fs(publication: ObjectPublication) = JvmLibraryFileSystem(root, PathExternalIo, publication, noLinks)

    private fun stage(
        name: String,
        bytes: ByteArray,
    ): String {
        val dir = Files.createDirectories(root.resolve("bucket"))
        Files.write(dir.resolve(name), bytes)
        return "bucket/$name"
    }

    @Test
    fun hardLinkOnlyWithoutLinksIsUnavailableAndKeepsTheStagingFile() {
        val bytes = "tx".encodeToByteArray()
        val staging = stage("s1.part", bytes)
        assertFailsWith<VerifiedPublicationUnavailableException> {
            runBlocking {
                fs(
                    ObjectPublication.HARD_LINK_ONLY,
                ).publishVerifiedObjectOnce(staging, "bucket/obj", sha256Bytes(bytes))
            }
        }
        Files.exists(root.resolve(staging)) shouldBe true
        Files.exists(root.resolve("bucket/obj")) shouldBe false
    }

    @Test
    fun renameFallbackPublishesVerifiedBytesAndReportsALaterDuplicate() {
        val fs = fs(ObjectPublication.HARD_LINK_OR_RENAME)
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
        val fs = fs(ObjectPublication.HARD_LINK_OR_RENAME)
        Files.createDirectories(root.resolve("bucket"))
        Files.write(root.resolve("bucket/obj"), "other".encodeToByteArray())
        val bytes = "tx".encodeToByteArray()
        assertFailsWith<IllegalStateException> {
            runBlocking { fs.publishVerifiedObjectOnce(stage("s1.part", bytes), "bucket/obj", sha256Bytes(bytes)) }
        }
        Files.readAllBytes(root.resolve("bucket/obj")).decodeToString() shouldBe "other"
    }

    @Test
    fun ordinaryWritesAndMovesKeepWriteOnceWithoutLinks() {
        val fs = fs(ObjectPublication.HARD_LINK_OR_RENAME)
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
        val fs = fs(ObjectPublication.HARD_LINK_ONLY)
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

    @Test
    fun copyOutOverwritesTheCallerOwnedTarget() {
        val fs = fs(ObjectPublication.HARD_LINK_ONLY)
        val target = Files.createTempFile("npn-export", ".pdf")
        try {
            Files.write(target, "stale and longer than the new content".encodeToByteArray())
            runBlocking {
                fs.writeBytesOnce("doc.pdf", "fresh".encodeToByteArray())
                fs.copyOut("doc.pdf", ExternalSource(target))
            }
            Files.readAllBytes(target).decodeToString() shouldBe "fresh"
        } finally {
            Files.deleteIfExists(target)
        }
    }

    private fun leftoverTempFiles(): List<Path> =
        Files.walk(root).use { stream -> stream.filter { it.name.startsWith(".npn-tmp-") }.toList() }
}
