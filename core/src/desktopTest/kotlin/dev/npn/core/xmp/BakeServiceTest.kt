package dev.npn.core.xmp

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BakeServiceTest {
    private lateinit var root: Path
    private lateinit var fs: JvmLibraryFileSystem
    private val io = PdfBoxXmpIO()

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("npn-bake-test")
        fs = JvmLibraryFileSystem(root)
    }

    @AfterTest
    fun teardown() {
        root.toFile().deleteRecursively()
    }

    private fun fixturePdf(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    @Test
    fun bakeToBytesDoesNotTouchLibraryFile() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val before = fs.readBytes("doc.pdf")
            val data = xmp(tags = listOf("t"), documentId = "a".repeat(64))

            val baked = BakeService(fs, io).bakeToBytes("doc.pdf", data)

            // Library file unchanged.
            fs.readBytes("doc.pdf") shouldBe before
            // Baked copy carries metadata.
            val readXmp = io.readXmp(baked)
            readXmp.shouldNotBeNull()
            XmpParser.parse(readXmp).tags shouldBe listOf("t")
        }

    @Test
    fun bakeInPlaceRewritesFileAndEmitsRelink() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")

            var relinkOld: String? = null
            var relinkNew: String? = null
            val data = xmp(tags = listOf("filed", "acme"), reviewed = "true")

            val result =
                BakeService(fs, io).bakeInPlace("doc.pdf", data) { old, new ->
                    relinkOld = old
                    relinkNew = new
                }

            // Relink called with correct shas.
            relinkOld shouldBe oldSha
            relinkNew shouldBe result.newSha256
            result.oldSha256 shouldBe oldSha
            (result.newSha256 != oldSha).shouldBeTrue()
            result.finalRelPath shouldBe "doc.pdf"

            // File in place with new content parseable and carrying metadata + documentId=oldSha.
            val nowSha = fs.sha256("doc.pdf")
            nowSha shouldBe result.newSha256
            val readXmp = io.readXmp(fs.readBytes("doc.pdf"))
            readXmp.shouldNotBeNull()
            val parsed = XmpParser.parse(readXmp)
            parsed.tags shouldBe listOf("filed", "acme")
            parsed.reviewed shouldBe "true"
            parsed.documentId shouldBe oldSha

            // No temp litter.
            noTempLitter().shouldBeTrue()
        }

    @Test
    fun bakeInPlaceVerifyFailureLeavesOriginalUntouched() =
        runTest {
            val original = fixturePdf()
            fs.writeBytesOnce("doc.pdf", original)
            val oldSha = fs.sha256("doc.pdf")

            // io that produces bytes that will not verify (strip metadata by returning a plain pdf).
            val brokenIo =
                object : PdfXmpIO {
                    override suspend fun readXmp(pdfBytes: ByteArray): String? = io.readXmp(pdfBytes)

                    override suspend fun writeBaked(
                        pdfBytes: ByteArray,
                        xmp: String,
                        keywords: List<String>,
                        title: String?,
                    ): ByteArray = fixturePdf() // valid pdf but no npn metadata -> verify() false

                    override suspend fun verify(pdfBytes: ByteArray): Boolean = io.verify(pdfBytes)
                }

            var relinkCalled = false
            assertFailsWith<BakeRefusedException> {
                BakeService(fs, brokenIo).bakeInPlace("doc.pdf", xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            // Original untouched.
            fs.readBytes("doc.pdf") shouldBe original
            fs.sha256("doc.pdf") shouldBe oldSha
            // Temp cleaned.
            noTempLitter().shouldBeTrue()
        }

    @Test
    fun aFailedFinalMovePutsTheOriginalBytesBackAndEmitsNoRelink() =
        runTest {
            val original = fixturePdf()
            fs.writeBytesOnce("doc.pdf", original)
            val oldSha = fs.sha256("doc.pdf")
            val failingMove =
                object : LibraryFileSystem by fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ) = throw IOException("disk full")
                }

            var relinkCalled = false
            assertFailsWith<IOException> {
                BakeService(failingMove, io).bakeInPlace("doc.pdf", xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            fs.sha256("doc.pdf") shouldBe oldSha
            noTempLitter().shouldBeTrue()
        }

    @Test
    fun bakeInPlaceInvalidBytesThrowsAndLeavesOriginal() =
        runTest {
            // Not a valid PDF: writeBaked itself throws before any file rewrite.
            fs.writeBytesOnce("doc.pdf", "not a pdf".encodeToByteArray())
            val before = fs.readBytes("doc.pdf")

            assertFailsWith<BakeRefusedException> {
                BakeService(fs, io).bakeInPlace("doc.pdf", xmp(tags = listOf("t"))) { _, _ -> }
            }
            fs.readBytes("doc.pdf") shouldBe before
            noTempLitter().shouldBeTrue()
        }

    private fun noTempLitter(): Boolean {
        var litter = 0
        Files.walk(root).use { stream ->
            stream.forEach { p ->
                if (p.fileName?.toString()?.endsWith(".npnbake.tmp") == true) litter++
            }
        }
        return litter == 0
    }
}
