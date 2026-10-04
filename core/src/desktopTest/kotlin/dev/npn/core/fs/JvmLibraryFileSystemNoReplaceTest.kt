package dev.npn.core.fs

import dev.npn.core.index.FsSegmentStore
import dev.npn.core.journal.SegmentRef
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Write-once discipline under concurrency: no app-written object in `.npn/` may ever be
 * replaced by a second writer racing the first.
 */
class JvmLibraryFileSystemNoReplaceTest {
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-noreplace-test")
        fs = JvmLibraryFileSystem(root)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun platformPrimitivesDifferOnWhetherARenameCanReplace() {
        val a = Files.write(root.resolve("a"), "AAA".encodeToByteArray())
        val b = Files.write(root.resolve("b"), "BBB".encodeToByteArray())
        // ATOMIC_MOVE is rename(2): atomic, but silently replaces an existing target.
        Files.move(a, b, StandardCopyOption.ATOMIC_MOVE)
        Files.readAllBytes(b).decodeToString() shouldBe "AAA"

        // link(2) is the primitive that atomically refuses an existing target.
        val c = Files.write(root.resolve("c"), "CCC".encodeToByteArray())
        runCatching { Files.createLink(b, c) }.exceptionOrNull()!!::class shouldBe
            FileAlreadyExistsException::class
        Files.readAllBytes(b).decodeToString() shouldBe "AAA"
    }

    @Test
    fun concurrentSidecarWritersNeverReplaceThePublishedSidecar() {
        val sidecars = OcrSidecarFiles(fs)
        val hash = "a".repeat(64)
        val path = NpnPaths.ocrSidecarPath(hash, "device1")
        val winners =
            raceWriters(WRITERS) { i ->
                sidecars.writeOnce(hash, "device1", payload(i))
            }
        winners.size shouldBe 1
        runBlocking { fs.readBytes(path).decodeToString() } shouldBe payload(winners.single())
    }

    @Test
    fun concurrentSegmentWritersNeverReplaceThePublishedSegment() {
        val store = FsSegmentStore(fs)
        val winners =
            raceWriters(WRITERS) { i ->
                store.writeSegment("device1", "000001.jsonl", payload(i))
            }
        winners.size shouldBe 1
        runBlocking { store.readSegment(SegmentRef("device1", "000001.jsonl")) } shouldBe payload(winners.single())
    }

    @Test
    fun concurrentMovesOntoOneTargetLoseNoDocument() {
        // Two path rules can compute the same target name for different documents; only one may
        // land there, and the losers must keep their bytes.
        val target = "inbox/target.pdf"
        val sources = (0 until WRITERS).map { i -> "staging/$i.pdf" }
        runBlocking {
            sources.forEachIndexed { i, p -> fs.writeBytesOnce(p, payload(i).encodeToByteArray()) }
        }
        val winners = raceWriters(WRITERS) { i -> fs.move(sources[i], target) }
        winners.size shouldBe 1
        runBlocking {
            fs.readBytes(target).decodeToString() shouldBe payload(winners.single())
            val losers = (0 until WRITERS).filter { it != winners.single() }
            losers.map { fs.exists(sources[it]) } shouldBe List(losers.size) { true }
        }
    }

    @Test
    fun concurrentVerifiedPublicationsNeverReplaceTheCanonicalObject() {
        val bytes = "complete transaction".encodeToByteArray()
        val hash = sha256Bytes(bytes)
        val canonical = NpnPaths.journalObjectPath(hash)
        val stagings = (0 until WRITERS).map { i -> NpnPaths.journalStagingPath(hash, "installation", "s$i") }
        runBlocking { stagings.forEach { fs.writeBytesOnce(it, bytes) } }
        val published = AtomicInteger()
        val winners =
            raceWriters(WRITERS) { i ->
                if (fs.publishVerifiedObjectOnce(stagings[i], canonical, hash) == VerifiedPublicationResult.PUBLISHED) {
                    published.incrementAndGet()
                }
            }
        // Exactly one publisher installs the object; the rest observe it as already present.
        winners.size shouldBe WRITERS
        published.get() shouldBe 1
        runBlocking { fs.readBytes(canonical).decodeToString() } shouldBe bytes.decodeToString()
    }

    /** Run [block] for indices `0 until n` simultaneously; return the indices that did not throw. */
    private fun raceWriters(
        n: Int,
        block: suspend (Int) -> Unit,
    ): List<Int> =
        runBlocking(Dispatchers.IO) {
            val barrier = CyclicBarrier(n)
            (0 until n)
                .map { i ->
                    async {
                        barrier.await()
                        runCatchingCancellable { block(i) }.fold(onSuccess = { i }, onFailure = { null })
                    }
                }.awaitAll()
                .filterNotNull()
        }

    private fun payload(i: Int) = "writer-$i".repeat(PAYLOAD_REPEATS)

    private companion object {
        const val WRITERS = 16
        const val PAYLOAD_REPEATS = 4096
    }
}
