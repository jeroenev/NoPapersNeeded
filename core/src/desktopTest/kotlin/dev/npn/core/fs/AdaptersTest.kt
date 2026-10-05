package dev.npn.core.fs

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
        fs = JvmLibraryFileSystem(root, PathExternalIo)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun ocrSidecarRoundtripAndListForHash() =
        runTest {
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
