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
        fs = JvmLibraryFileSystem(root)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun walkAndStat() = runTest {
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
    fun writeBytesOnceFailsOnExistingAndLeavesNoTempLitter() = runTest {
        fs.writeBytesOnce("dir/x.pdf", "one".encodeToByteArray())
        assertFailsWith<IllegalStateException> {
            fs.writeBytesOnce("dir/x.pdf", "two".encodeToByteArray())
        }
        // Content unchanged.
        fs.readBytes("dir/x.pdf").decodeToString() shouldBe "one"
        // No temp files left behind.
        val litter = Files.list(root.resolve("dir")).use { stream ->
            stream.filter { it.fileName.toString().startsWith(".npn-tmp-") }.count()
        }
        litter shouldBe 0L
    }

    @Test
    fun moveFailsOnExistingTarget() = runTest {
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
    fun moveSucceedsAndCreatesParents() = runTest {
        fs.writeBytesOnce("a.pdf", "a".encodeToByteArray())
        fs.move("a.pdf", "sub/dir/c.pdf")
        fs.exists("a.pdf").shouldBeFalse()
        fs.readBytes("sub/dir/c.pdf").decodeToString() shouldBe "a"
    }

    @Test
    fun sha256MatchesReference() = runTest {
        val content = "The quick brown fox jumps over the lazy dog".encodeToByteArray()
        fs.writeBytesOnce("f.txt", content)
        val expected = MessageDigest.getInstance("SHA-256").digest(content)
            .joinToString("") { "%02x".format(it) }
        fs.sha256("f.txt") shouldBe expected
        // Known vector: sha256("") 
        fs.writeBytesOnce("empty.txt", ByteArray(0))
        fs.sha256("empty.txt") shouldBe
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    @Test
    fun deleteRecursive() = runTest {
        fs.writeBytesOnce("d/a.pdf", "a".encodeToByteArray())
        fs.writeBytesOnce("d/e/b.pdf", "b".encodeToByteArray())
        fs.delete("d")
        fs.exists("d").shouldBeFalse()
    }

    @Test
    fun importAndCopyOut() = runTest {
        val ext = Files.createTempFile("npn-ext", ".pdf")
        Files.write(ext, "external".encodeToByteArray())
        fs.importExternal(ExternalSource(ext), "imported/x.pdf")
        fs.readBytes("imported/x.pdf").decodeToString() shouldBe "external"
        assertFailsWith<IllegalStateException> {
            fs.importExternal(ExternalSource(ext), "imported/x.pdf")
        }

        val out = Files.createTempDirectory("npn-out").resolve("out.pdf")
        fs.copyOut("imported/x.pdf", ExternalSource(out))
        Files.readAllBytes(out).decodeToString() shouldBe "external"
        Files.deleteIfExists(ext)
    }

    @Test
    fun watchEmitsOnFileCreate() = runBlocking {
        // Real WatchService with real time (not virtual): expect an event within the timeout.
        withTimeout(5000) {
            val flow = fs.watch()
            val deferred = kotlinx.coroutines.CompletableDeferred<FsEvent>()
            val job = launch(Dispatchers.IO) {
                deferred.complete(flow.first())
            }
            // Give the watcher a moment to register before creating the file.
            kotlinx.coroutines.delay(500)
            Files.write(root.resolve("watched.pdf"), "x".encodeToByteArray())
            val event = deferred.await()
            (event is FsEvent.Changed).shouldBeTrue()
            job.cancel()
        }
        Unit
    }
}
