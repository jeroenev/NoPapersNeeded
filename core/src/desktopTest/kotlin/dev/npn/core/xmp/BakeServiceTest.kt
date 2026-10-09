package dev.npn.core.xmp

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.util.NpnLog
import dev.npn.core.util.RecordingLog
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
        fs = JvmLibraryFileSystem(root, PathExternalIo)
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
            io.readXmp(fs.readBytes("doc.pdf")) shouldBe XmpSerializer.serialize(data.copy(documentId = oldSha))

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
                    ): ByteArray = fixturePdf() // valid pdf but no npn metadata: does not verify
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

    /** The rename landed, but the provider reported a failure: undone, the document is back as it was. */
    @Test
    fun aMoveThatLandsAndThenFailsEndsWithTheOriginalBytesAndNoRelink() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")

            var relinkCalled = false
            assertFailsWith<IOException> {
                BakeService(LandingMoveFails(fs), io).bakeInPlace("doc.pdf", xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            fs.sha256("doc.pdf") shouldBe oldSha
            noTempLitter().shouldBeTrue()
        }

    /**
     * Two faults: the move reports failure after landing, and putting the original back fails too.
     * The undo never deletes the only copy: the document path or the bake's temporary file still
     * holds the original or the baked bytes.
     */
    @Test
    fun aFailedUndoOfAFailedMoveLeavesAVersionOfTheDocumentOnDisk() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")
            val failing = LandingMoveFails(fs, failWriteTo = "doc.pdf")
            val log = RecordingLog()

            var relinkCalled = false
            assertFailsWith<IOException> {
                BakeService(failing, io, log).bakeInPlace("doc.pdf", xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            val kept = listOf("doc.pdf", NpnPaths.Temp.bakePath("doc.pdf")).filter { holdsAVersion(it, oldSha) }
            kept.shouldNotBeEmpty()
            // The log says where to find it.
            log.entries.any { it.level == NpnLog.Level.ERROR && kept.all(it.message::contains) }.shouldBeTrue()
        }

    /**
     * A bake cancelled while it swaps the files (right after the original went, or during the move)
     * ends consistent either way: baked and relinked, or the original with no relink. Never the
     * baked bytes without their relink, nor the document path empty.
     */
    @Test
    fun aBakeCancelledDuringTheSwapEndsBakedAndRelinkedOrUntouched() =
        runTest {
            for (step in CancelledDuring.Step.entries) {
                for (suspendAfterCancel in listOf(false, true)) {
                    val doc = "doc-$step-$suspendAfterCancel.pdf"
                    fs.writeBytesOnce(doc, fixturePdf())
                    val oldSha = fs.sha256(doc)
                    val cancelling = CancelledDuring(fs, doc, step, suspendAfterCancel)
                    var relink: Pair<String, String>? = null

                    val bake =
                        launch {
                            BakeService(cancelling, io).bakeInPlace(doc, xmp(tags = listOf("t"))) { old, new ->
                                relink = old to new
                            }
                        }
                    cancelling.job = bake
                    bake.join()

                    withClue("$step, suspending after the cancel: $suspendAfterCancel") {
                        fs.exists(doc).shouldBeTrue()
                        val now = fs.sha256(doc)
                        if (relink == null) now shouldBe oldSha else relink shouldBe (oldSha to now)
                        noTempLitter().shouldBeTrue()
                    }
                }
            }
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

    /** Whether [path] holds the document [oldSha] names: its original bytes, or their baked version. */
    private suspend fun holdsAVersion(
        path: String,
        oldSha: String,
    ): Boolean {
        if (!fs.exists(path)) return false
        if (fs.sha256(path) == oldSha) return true
        val xmp = io.readXmp(fs.readBytes(path)) ?: return false
        return XmpParser.parse(xmp).documentId == oldSha
    }

    /**
     * [fs] whose every move lands and then reports failure, as a provider that renamed the file and
     * then threw; a write to [failWriteTo] fails, as on a full disk.
     */
    private class LandingMoveFails(
        private val fs: LibraryFileSystem,
        private val failWriteTo: String? = null,
    ) : LibraryFileSystem by fs {
        override suspend fun move(
            fromRelPath: String,
            toRelPath: String,
        ) {
            fs.move(fromRelPath, toRelPath)
            throw IOException("moved, then reported a failure")
        }

        override suspend fun writeBytesOnce(
            relPath: String,
            bytes: ByteArray,
        ) {
            if (relPath == failWriteTo) throw IOException("disk full")
            fs.writeBytesOnce(relPath, bytes)
        }
    }

    /**
     * [fs] that cancels [job] as [step] completes on [doc]: either the call still returns (the
     * cancellation arrived while a blocking call ran), or it then suspends, where a cancellable
     * caller sees the cancellation.
     */
    private class CancelledDuring(
        private val fs: LibraryFileSystem,
        private val doc: String,
        private val step: Step,
        private val suspendAfterCancel: Boolean,
    ) : LibraryFileSystem by fs {
        enum class Step { DELETE, MOVE }

        lateinit var job: Job

        override suspend fun delete(relPath: String) {
            fs.delete(relPath)
            if (step == Step.DELETE && relPath == doc) cancel()
        }

        override suspend fun move(
            fromRelPath: String,
            toRelPath: String,
        ) {
            fs.move(fromRelPath, toRelPath)
            if (step == Step.MOVE && toRelPath == doc) cancel()
        }

        private suspend fun cancel() {
            job.cancel()
            if (suspendAfterCancel) yield()
        }
    }
}
