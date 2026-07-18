package dev.npn.core.fs

import dev.npn.core.index.FsSegmentStore
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AdaptersTest {

    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-adapters-test")
        fs = JvmLibraryFileSystem(root)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun segmentStoreWriteOnceAndListingGroupedByDevice() = runTest {
        val store = FsSegmentStore(fs)
        store.writeJournalFileOnce("devA", "0001.jsonl", "{\"seq\":1}")
        store.writeJournalFileOnce("devA", "0002.jsonl", "{\"seq\":2}")
        store.writeJournalFileOnce("devB", "0001.jsonl", "{\"seq\":1}")

        val listing = store.listJournalFiles()
        listing.shouldContainExactlyInAnyOrder(
            "devA" to "0001.jsonl",
            "devA" to "0002.jsonl",
            "devB" to "0001.jsonl",
        )

        val byDevice = listing.groupBy { it.first }
        byDevice["devA"]!! shouldHaveSize 2
        byDevice["devB"]!! shouldHaveSize 1

        store.readJournalFile("devA", "0002.jsonl") shouldBe "{\"seq\":2}"

        store.deleteJournalFile("devA", "0001.jsonl")
        store.listJournalFiles().shouldContainExactlyInAnyOrder(
            "devA" to "0002.jsonl",
            "devB" to "0001.jsonl",
        )
    }

    @Test
    fun segmentStoreWriteOnceRejectsDuplicate() = runTest {
        val store = FsSegmentStore(fs)
        store.writeJournalFileOnce("devA", "0001.jsonl", "a")
        kotlin.test.assertFailsWith<IllegalStateException> {
            store.writeJournalFileOnce("devA", "0001.jsonl", "b")
        }
    }

    @Test
    fun ocrSidecarRoundtripAndListForHash() = runTest {
        val ocr = OcrSidecarFiles(fs)
        val hash = "abc123def456"
        ocr.writeOnce(hash, "devA", "{\"pages\":[\"a\"]}")
        ocr.writeOnce(hash, "devB", "{\"pages\":[\"b\"]}")
        ocr.writeOnce("otherhash", "devA", "{}")

        ocr.read(hash, "devA") shouldBe "{\"pages\":[\"a\"]}"
        ocr.listForHash(hash).shouldContainExactlyInAnyOrder("devA", "devB")
        ocr.listForHash("otherhash").shouldContainExactlyInAnyOrder("devA")

        ocr.list() shouldHaveSize 3
    }
}
