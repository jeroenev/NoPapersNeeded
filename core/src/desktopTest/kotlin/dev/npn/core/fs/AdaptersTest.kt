package dev.npn.core.fs

import dev.npn.core.ocr.OcrSidecarFiles
import dev.npn.core.ocr.writerKeysOf
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

class AdaptersTest {
    private val root: Path = Files.createTempDirectory("npn-adapters-test")
    private val fs = JvmLibraryFileSystem(root, PathExternalIo)

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
            ocr.writerKeysOf(hash).shouldContainExactlyInAnyOrder("devA", "devB")
            ocr.writerKeysOf("otherhash").shouldContainExactlyInAnyOrder("devA")

            ocr.list() shouldHaveSize 3
        }
}
