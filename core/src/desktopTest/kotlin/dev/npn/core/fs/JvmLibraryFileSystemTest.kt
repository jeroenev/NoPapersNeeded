package dev.npn.core.fs

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class JvmLibraryFileSystemTest {
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-fs-test")
        fs = JvmLibraryFileSystem(root, PathExternalIo)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
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
            val canonical = NpnPaths.journalObjectPath(hash)
            val staging = NpnPaths.journalStagingPath(hash, "installation", "one")
            fs.writeBytesOnce(staging, bytes)
            fs.publishVerifiedObjectOnce(staging, canonical, hash)
            fs.exists(staging).shouldBeFalse()
            fs.readBytes(canonical).contentEquals(bytes).shouldBeTrue()

            val retry = NpnPaths.journalStagingPath(hash, "installation", "two")
            fs.writeBytesOnce(retry, bytes)
            fs.publishVerifiedObjectOnce(retry, canonical, hash)
            fs.exists(retry).shouldBeFalse()
            fs.readBytes(canonical).contentEquals(bytes).shouldBeTrue()

            val other = "other".encodeToByteArray()
            val otherHash = sha256Bytes(other)
            val corruptTarget = NpnPaths.journalObjectPath(otherHash)
            fs.writeBytesOnce(corruptTarget, "corrupt".encodeToByteArray())
            val otherStaging = NpnPaths.journalStagingPath(otherHash, "installation", "three")
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
            // Real WatchService with real time (not virtual): expect an event within the timeout.
            withTimeout(5000) {
                val flow = fs.watch()
                val deferred = kotlinx.coroutines.CompletableDeferred<FsEvent>()
                val job =
                    launch(Dispatchers.IO) {
                        deferred.complete(flow.first())
                    }
                // Give the watcher a moment to register before creating the file.
                kotlinx.coroutines.delay(500)
                Files.write(root.resolve("watched.pdf"), "x".encodeToByteArray())
                val event = deferred.await()
                event.relPath shouldBe "watched.pdf"
                job.cancel()
            }
            Unit
        }
}
