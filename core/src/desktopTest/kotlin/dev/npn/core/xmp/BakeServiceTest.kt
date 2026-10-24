package dev.npn.core.xmp

import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.NpnLog
import dev.npn.core.util.RecordingLog
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BakeServiceTest {
    private val root: Path = Files.createTempDirectory("npn-bake-test")
    private val fs = JvmLibraryFileSystem(root, PathExternalIo)
    private val io = PdfBoxXmpIO()

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
            val docId = sha256Bytes(before)
            val data = xmp(tags = listOf("t"), documentId = docId)

            val baked = BakeService(fs, io).bakeToBytes("doc.pdf", docId, data).shouldNotBeNull()

            // Library file unchanged.
            fs.readBytes("doc.pdf") shouldBe before
            // Baked copy carries metadata.
            val readXmp = io.readXmp(baked)
            readXmp.shouldNotBeNull()
            XmpParser.parse(readXmp).tags shouldBe listOf("t")
        }

    /** Another file at the document's path does not leave carrying the document's metadata. */
    @Test
    fun bakeToBytesOfAFileThatIsNotTheDocumentsGivesNothing() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val data = xmp(tags = listOf("t"), documentId = "a".repeat(64))

            BakeService(fs, io).bakeToBytes("doc.pdf", "a".repeat(64), data) shouldBe null
        }

    @Test
    fun bakeInPlaceRewritesFileAndEmitsRelink() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")

            var relinkOld: String? = null
            var relinkNew: String? = null
            val data = xmp(tags = listOf("filed", "acme"), reviewed = "true")

            val newSha =
                BakeService(fs, io)
                    .bakeInPlace("doc.pdf", oldSha, data) { old, new ->
                        relinkOld = old
                        relinkNew = new
                    }.shouldNotBeNull()

            // Relink called with correct shas.
            relinkOld shouldBe oldSha
            relinkNew shouldBe newSha
            (newSha != oldSha).shouldBeTrue()

            // File in place, at its own path, with new content parseable and carrying metadata +
            // documentId=oldSha.
            fs.sha256("doc.pdf") shouldBe newSha
            io.readXmp(fs.readBytes("doc.pdf")) shouldBe XmpSerializer.serialize(data.copy(documentId = oldSha))

            // No temp litter, and the original moved aside is gone once the relink stands.
            noTempLitter().shouldBeTrue()
            noRecoveryLitter().shouldBeTrue()
        }

    /** The bake reads the document's file once and hashes what it read, and the rewrite in memory. */
    @Test
    fun aBakeReadsTheDocumentsFileOnceAndHashesNoFile() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")
            val counting = Counting(fs)

            val result =
                BakeService(counting, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ -> }

            result.shouldNotBeNull() shouldBe fs.sha256("doc.pdf")
            counting.calls shouldBe listOf("read doc.pdf")
        }

    /** A file that is not the document's is not baked as it: nothing is written and no relink emitted. */
    @Test
    fun aFileThatDoesNotHoldTheDocumentIsLeftAlone() =
        runTest {
            val other = fixturePdf()
            fs.writeBytesOnce("doc.pdf", other)
            var relinkCalled = false

            BakeService(fs, io).bakeInPlace("doc.pdf", "0".repeat(64), xmp(tags = listOf("t"))) { _, _ ->
                relinkCalled = true
            } shouldBe null

            relinkCalled.shouldBeFalse()
            fs.readBytes("doc.pdf") shouldBe other
            noTempLitter().shouldBeTrue()
        }

    /**
     * Another file is written over the document's while the bake rewrites its copy: that file stays,
     * and the rewrite is removed instead of put in its place.
     */
    @Test
    fun aFileWrittenOverWhileItIsBakedIsLeftAlone() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")
            val other = "another app's file, written over the document".encodeToByteArray()
            val overwriting =
                object : LibraryFileSystem by fs {
                    override suspend fun writeBytesOnce(
                        relPath: String,
                        bytes: ByteArray,
                    ) {
                        fs.writeBytesOnce(relPath, bytes)
                        if (relPath == NpnPaths.Temp.bakePath("doc.pdf")) {
                            fs.delete("doc.pdf")
                            fs.writeBytesOnce("doc.pdf", other)
                        }
                    }
                }
            var relinkCalled = false

            BakeService(overwriting, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                relinkCalled = true
            } shouldBe null

            relinkCalled.shouldBeFalse()
            fs.readBytes("doc.pdf") shouldBe other
            noTempLitter().shouldBeTrue()
        }

    /**
     * Another process or a sync tool replaces the document's file between the bake's last look at it
     * and its swap: the file it moved aside is not the document's, so it goes back where it was and
     * nothing is baked. Deleted instead, it could be the only copy of another device's version.
     */
    @Test
    fun aFileReplacedRightBeforeTheSwapIsPutBack() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")
            val other = "a sync tool's newer version, delivered just now".encodeToByteArray()
            val racing =
                object : LibraryFileSystem by fs {
                    var raced = false

                    /** The other version lands at the document's path right before the bake's first change there. */
                    suspend fun race(relPath: String) {
                        if (relPath != "doc.pdf" || raced) return
                        raced = true
                        fs.delete("doc.pdf")
                        fs.writeBytesOnce("doc.pdf", other)
                    }

                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        race(fromRelPath)
                        fs.move(fromRelPath, toRelPath)
                    }

                    override suspend fun delete(relPath: String) {
                        race(relPath)
                        fs.delete(relPath)
                    }
                }
            var relinkCalled = false

            BakeService(racing, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                relinkCalled = true
            } shouldBe null

            relinkCalled.shouldBeFalse()
            fs.readBytes("doc.pdf") shouldBe other
            fs.listAllUnder("").none { it.name.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) }.shouldBeTrue()
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
                BakeService(fs, brokenIo).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
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
                BakeService(failingMove, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            fs.sha256("doc.pdf") shouldBe oldSha
            noTempLitter().shouldBeTrue()
        }

    @Test
    fun aRelinkThatCannotBePublishedRestoresTheOriginalBytes() =
        runTest {
            val original = fixturePdf()
            fs.writeBytesOnce("doc.pdf", original)
            val oldSha = fs.sha256("doc.pdf")

            assertFailsWith<IOException> {
                BakeService(fs, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                    throw IOException("journal disk full")
                }
            }

            fs.readBytes("doc.pdf") shouldBe original
            fs.sha256("doc.pdf") shouldBe oldSha
            noTempLitter().shouldBeTrue()
        }

    @Test
    fun aFailedRelinkLeavesAnExternalReplacementAloneAndKeepsTheOriginalForRecovery() =
        runTest {
            val original = fixturePdf()
            val other = "a sync writer's unrelated replacement".encodeToByteArray()
            fs.writeBytesOnce("doc.pdf", original)
            val oldSha = fs.sha256("doc.pdf")
            val log = RecordingLog()

            assertFailsWith<IOException> {
                BakeService(fs, io, log).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                    fs.delete("doc.pdf")
                    fs.writeBytesOnce("doc.pdf", other)
                    throw IOException("journal unavailable")
                }
            }

            fs.readBytes("doc.pdf") shouldBe other
            val recovery = fs.listAllUnder("").single { it.name.startsWith("npn-recovery-") }
            fs.readBytes(recovery.relPath) shouldBe original
            fs.sha256(recovery.relPath) shouldBe oldSha
            // Saved under a name the next rescan indexes: put right, so a warning that says where.
            log.entries.none { it.level == NpnLog.Level.ERROR }.shouldBeTrue()
            log.entries
                .any {
                    it.level == NpnLog.Level.WARN && recovery.relPath in it.message &&
                        it.error is BakeDisplacedException
                }.shouldBeTrue()
        }

    /** The rename landed, but the provider reported a failure: undone, the document is back as it was. */
    @Test
    fun aMoveThatLandsAndThenFailsEndsWithTheOriginalBytesAndNoRelink() =
        runTest {
            fs.writeBytesOnce("doc.pdf", fixturePdf())
            val oldSha = fs.sha256("doc.pdf")

            var relinkCalled = false
            assertFailsWith<IOException> {
                BakeService(LandingMoveFails(fs), io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
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
     * holds the original or the baked bytes, and the original is under a recovery name.
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
                BakeService(failing, io, log).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                    relinkCalled = true
                }
            }

            relinkCalled.shouldBeFalse()
            val kept = listOf("doc.pdf", NpnPaths.Temp.bakePath("doc.pdf")).filter { holdsAVersion(it, oldSha) }
            kept.shouldNotBeEmpty()
            // The log says where to find it; a warning, as the original is saved under a recovery
            // name the next rescan indexes.
            log.entries.any { it.level == NpnLog.Level.WARN && kept.all(it.message::contains) }.shouldBeTrue()
            log.entries.none { it.level == NpnLog.Level.ERROR }.shouldBeTrue()
            val recovery = fs.listAllUnder("").single { it.name.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) }
            fs.sha256(recovery.relPath) shouldBe oldSha
        }

    /**
     * The undo's parking move of the baked bytes reports failure without landing, so the document
     * path keeps them and the write of the original back cannot land either. The original is then
     * saved under a recovery name before the undo gives up: a double fault never loses it.
     */
    @Test
    fun aFailedParkOfTheBakedBytesSavesTheOriginalForRecovery() =
        runTest {
            val original = fixturePdf()
            fs.writeBytesOnce("doc.pdf", original)
            val oldSha = fs.sha256("doc.pdf")
            val failing = MoveRefused(fs, from = "doc.pdf", to = NpnPaths.Temp.bakePath("doc.pdf"))

            assertFailsWith<IOException> {
                BakeService(failing, io).bakeInPlace("doc.pdf", oldSha, xmp(tags = listOf("t"))) { _, _ ->
                    throw IOException("journal unavailable")
                }
            }

            // The path keeps the baked bytes (nothing moved away from it) ...
            fs.readBytes("doc.pdf") shouldNotBe original
            // ... and the original survives under a recovery name, as the branch that finds another
            // file there saves it.
            val recovery = fs.listAllUnder("").single { it.name.startsWith("npn-recovery-") }
            fs.readBytes(recovery.relPath) shouldBe original
            fs.sha256(recovery.relPath) shouldBe oldSha
        }

    /**
     * A bake cancelled while it swaps the files (right after the original went aside, or during the move)
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
                            BakeService(cancelling, io).bakeInPlace(doc, oldSha, xmp(tags = listOf("t"))) { old, new ->
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
                        noRecoveryLitter().shouldBeTrue()
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
                BakeService(fs, io).bakeInPlace("doc.pdf", sha256Bytes(before), xmp(tags = listOf("t"))) { _, _ -> }
            }
            fs.readBytes("doc.pdf") shouldBe before
            noTempLitter().shouldBeTrue()
        }

    /** Whether no file under a recovery name is left: the original a bake moved aside went back, or went. */
    private fun noRecoveryLitter(): Boolean =
        Files.walk(root).use { stream ->
            stream.noneMatch { p -> p.fileName?.toString()?.startsWith(NpnPaths.Temp.RECOVERY_PREFIX) == true }
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

    /** [fs] recording every read and hash of a library PDF: `read <path>`, `sha256 <path>`. */
    private class Counting(
        private val fs: LibraryFileSystem,
    ) : LibraryFileSystem by fs {
        val calls = ArrayList<String>()

        override suspend fun readBytes(relPath: String): ByteArray {
            if (NpnPaths.isPdf(relPath)) calls += "read $relPath"
            return fs.readBytes(relPath)
        }

        override suspend fun sha256(relPath: String): String {
            if (NpnPaths.isPdf(relPath)) calls += "sha256 $relPath"
            return fs.sha256(relPath)
        }
    }

    /**
     * [fs] whose move from [from] to [to] reports failure without landing, as a provider that never
     * started it.
     */
    private class MoveRefused(
        private val fs: LibraryFileSystem,
        private val from: String,
        private val to: String,
    ) : LibraryFileSystem by fs {
        override suspend fun move(
            fromRelPath: String,
            toRelPath: String,
        ) {
            if (fromRelPath == from && toRelPath == to) throw IOException("move refused")
            fs.move(fromRelPath, toRelPath)
        }
    }

    /**
     * [fs] whose every move lands and then reports failure, as a provider that renamed the file and
     * then threw; a write to [failWriteTo] fails, as on a full disk, and so does a move to it,
     * without landing.
     */
    private class LandingMoveFails(
        private val fs: LibraryFileSystem,
        private val failWriteTo: String? = null,
    ) : LibraryFileSystem by fs {
        override suspend fun move(
            fromRelPath: String,
            toRelPath: String,
        ) {
            if (toRelPath == failWriteTo) throw IOException("disk full")
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
        enum class Step { ASIDE, MOVE }

        lateinit var job: Job

        override suspend fun move(
            fromRelPath: String,
            toRelPath: String,
        ) {
            fs.move(fromRelPath, toRelPath)
            if (step == Step.ASIDE && fromRelPath == doc) cancel()
            if (step == Step.MOVE && toRelPath == doc) cancel()
        }

        private suspend fun cancel() {
            job.cancel()
            if (suspendAfterCancel) yield()
        }
    }
}
