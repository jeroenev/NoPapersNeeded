package dev.npn.core.fs

import dev.npn.core.journal.JournalPaths
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JvmLibraryFileSystemTest {
    private val root: Path = Files.createTempDirectory("npn-fs-test")
    private val fs = JvmLibraryFileSystem(root, PathExternalIo)

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    /** What does not exist has no metadata, as in the SAF implementation; nothing throws. */
    @Test
    fun statReturnsNullForWhatIsGone() =
        runTest {
            fs.stat("missing.pdf") shouldBe null
            fs.writeBytesOnce("inbox/gone.pdf", "bytes".encodeToByteArray())
            fs.delete("inbox/gone.pdf")
            fs.stat("inbox/gone.pdf") shouldBe null
        }

    /** A file another hand moves away while it is hashed is not proven to hold its bytes. */
    @Test
    fun holdsIsFalseWhenTheFileVanishesWhileItIsHashed() =
        runTest {
            fs.writeBytesOnce("inbox/doc.pdf", "content".encodeToByteArray())
            val id = fs.sha256("inbox/doc.pdf")
            val vanishing =
                object : LibraryFileSystem by fs {
                    override suspend fun sha256(relPath: String): String {
                        fs.delete(relPath)
                        return fs.sha256(relPath)
                    }
                }

            vanishing.holds("inbox/doc.pdf", id).shouldBeFalse()
        }

    @Test
    fun walkAndStat() =
        runTest {
            fs.writeBytesOnce("inbox/a.pdf", "hello".encodeToByteArray())
            fs.writeBytesOnce(".npn/journal/dev1/0.jsonl", "{}".encodeToByteArray())

            val all = fs.listAll()
            val relPaths = all.map { it.relPath }
            relPaths.shouldContainExactlyInAnyOrder(
                "inbox",
                "inbox/a.pdf",
                ".npn",
                ".npn/journal",
                ".npn/journal/dev1",
                ".npn/journal/dev1/0.jsonl",
            )
            fs.listAllUnder(".npn/journal").map { it.relPath }.shouldContainExactlyInAnyOrder(
                ".npn/journal/dev1",
                ".npn/journal/dev1/0.jsonl",
            )
            fs.listAllUnder("missing") shouldBe emptyList()

            val stat = fs.stat("inbox/a.pdf")
            stat.shouldNotBeNull()
            stat.name shouldBe "a.pdf"
            stat.size shouldBe 5L
            stat.isDirectory.shouldBeFalse()

            fs.stat("does/not/exist").shouldBe(null)
            fs.exists("inbox/a.pdf").shouldBeTrue()
            fs.exists("nope").shouldBeFalse()
        }

    /** Where `\` is no separator it is part of a name, listed as it is, so [NpnPaths.isSafe] refuses it. */
    @Test
    fun aBackslashInANameIsListedAsPartOfIt() =
        runTest {
            assumeTrue(File.separatorChar == '/')
            Files.createDirectories(root.resolve("dir"))
            Files.write(root.resolve("dir").resolve("x\\y.pdf"), "hello".encodeToByteArray())

            fs.listAll().map { it.relPath }.shouldContainExactlyInAnyOrder("dir", "dir/x\\y.pdf")
        }

    @Test
    fun indexableWalkSkipsNpnSubtree() =
        runTest {
            fs.writeBytesOnce("inbox/a.pdf", "hello".encodeToByteArray())
            fs.writeBytesOnce(".npn/journal/dev1/0.jsonl", "{}".encodeToByteArray())
            fs.writeBytesOnce(".npn/ocr/hash.dev1.json", "{}".encodeToByteArray())

            val relPaths = fs.listAllIndexable().map { it.relPath }
            relPaths.shouldContainExactlyInAnyOrder("inbox", "inbox/a.pdf")
        }

    @Test
    fun writeBytesOnceFailsOnExistingAndLeavesNoTempLitter() =
        runTest {
            fs.writeBytesOnce("dir/x.pdf", "one".encodeToByteArray())
            assertFailsWith<IllegalStateException> {
                fs.writeBytesOnce("dir/x.pdf", "two".encodeToByteArray())
            }
            // Content unchanged.
            fs.readBytes("dir/x.pdf").decodeToString() shouldBe "one"
            // No temp files left behind.
            val litter =
                Files.list(root.resolve("dir")).use { stream ->
                    stream.filter { it.fileName.toString().startsWith(".npn-tmp-") }.count()
                }
            litter shouldBe 0L
        }

    @Test
    fun refusesPathsThatEscapeTheLibrary() =
        runTest {
            val outside = root.parent.resolve("npn-fs-test-escape.pdf")
            try {
                assertFailsWith<IllegalArgumentException> {
                    fs.writeBytesOnce("../npn-fs-test-escape.pdf", "x".encodeToByteArray())
                }
                assertFailsWith<IllegalArgumentException> { fs.readBytes("inbox/../../etc/passwd") }
                assertFailsWith<IllegalArgumentException> { fs.exists("..") }
                Files.exists(outside).shouldBeFalse()
            } finally {
                Files.deleteIfExists(outside)
            }
        }

    @Test
    fun moveFailsOnExistingTarget() =
        runTest {
            fs.writeBytesOnce("a.pdf", "a".encodeToByteArray())
            fs.writeBytesOnce("b.pdf", "b".encodeToByteArray())
            assertFailsWith<IllegalStateException> {
                fs.move("a.pdf", "b.pdf")
            }
            // Both intact.
            fs.readBytes("a.pdf").decodeToString() shouldBe "a"
            fs.readBytes("b.pdf").decodeToString() shouldBe "b"
        }

    @Test
    fun moveSucceedsAndCreatesParents() =
        runTest {
            fs.writeBytesOnce("a.pdf", "a".encodeToByteArray())
            fs.move("a.pdf", "sub/dir/c.pdf")
            fs.exists("a.pdf").shouldBeFalse()
            fs.readBytes("sub/dir/c.pdf").decodeToString() shouldBe "a"
        }

    @Test
    fun sha256MatchesReference() =
        runTest {
            val content = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
            fs.writeBytesOnce("f.txt", content)
            val expected =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(content)
                    .joinToString("") { "%02x".format(it) }
            fs.sha256("f.txt") shouldBe expected
            // Known vector: sha256("")
            fs.writeBytesOnce("empty.txt", ByteArray(0))
            fs.sha256("empty.txt") shouldBe
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }

    @Test
    fun verifiedObjectPublicationIsAtomicIdempotentAndNeverReplaces() =
        runTest {
            val bytes = "complete transaction".encodeToByteArray()
            val hash = sha256Bytes(bytes)
            val canonical = JournalPaths.objectPath(hash)
            val staging = JournalPaths.stagingPath(hash, "installation", "one")
            fs.writeBytesOnce(staging, bytes)
            fs.publishVerifiedObjectOnce(staging, canonical, hash)
            fs.exists(staging).shouldBeFalse()
            fs.readBytes(canonical).contentEquals(bytes).shouldBeTrue()

            val retry = JournalPaths.stagingPath(hash, "installation", "two")
            fs.writeBytesOnce(retry, bytes)
            fs.publishVerifiedObjectOnce(retry, canonical, hash)
            fs.exists(retry).shouldBeFalse()
            fs.readBytes(canonical).contentEquals(bytes).shouldBeTrue()

            val other = "other".encodeToByteArray()
            val otherHash = sha256Bytes(other)
            val corruptTarget = JournalPaths.objectPath(otherHash)
            fs.writeBytesOnce(corruptTarget, "corrupt".encodeToByteArray())
            val otherStaging = JournalPaths.stagingPath(otherHash, "installation", "three")
            fs.writeBytesOnce(otherStaging, other)
            assertFailsWith<IllegalStateException> {
                fs.publishVerifiedObjectOnce(otherStaging, corruptTarget, otherHash)
            }
            fs.readBytes(corruptTarget).decodeToString() shouldBe "corrupt"
        }

    @Test
    fun deleteRecursive() =
        runTest {
            fs.writeBytesOnce("d/a.pdf", "a".encodeToByteArray())
            fs.writeBytesOnce("d/e/b.pdf", "b".encodeToByteArray())
            fs.delete("d")
            fs.exists("d").shouldBeFalse()
        }

    @Test
    fun importExternalRefusesAnExistingTarget() =
        runTest {
            val ext = Files.createTempFile("npn-ext", ".pdf")
            Files.write(ext, "external".encodeToByteArray())
            fs.importExternal(ExternalSource(ext), "imported/x.pdf")
            fs.readBytes("imported/x.pdf").decodeToString() shouldBe "external"
            assertFailsWith<IllegalStateException> {
                fs.importExternal(ExternalSource(ext), "imported/x.pdf")
            }
            Files.deleteIfExists(ext)
        }

    @Test
    fun watchEmitsOnFileCreate() =
        runBlocking {
            // A real WatchService on real time. Probe files are written until one is reported, so
            // the file under test is only written once the watch is registered.
            withTimeout(WATCH_TIMEOUT_MILLIS) {
                val changes = Channel<FsEvent>(Channel.UNLIMITED)
                val collector = launch { fs.watch().collect { changes.send(it) } }
                try {
                    var probe = 0
                    while (withTimeoutOrNull(PROBE_MILLIS) { changes.receive() } == null) {
                        Files.write(root.resolve("probe-${probe++}"), byteArrayOf(1))
                    }
                    Files.write(root.resolve("watched.pdf"), "x".encodeToByteArray())
                    while (changes.receive() != FsEvent("watched.pdf")) {
                        // skip the probes still being reported
                    }
                } finally {
                    collector.cancel()
                }
            }
        }

    private companion object {
        const val WATCH_TIMEOUT_MILLIS = 10_000L
        const val PROBE_MILLIS = 100L
    }
}
