package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FakeSseGateway
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.db.isLive
import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrSidecar
import dev.npn.core.ocr.OcrSidecarCodec
import dev.npn.core.ocr.OcrSidecarFiles
import dev.npn.core.ocr.OcrSidecarName
import dev.npn.core.ocr.OcrState
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.RecordingLog
import dev.npn.core.xmp.BakeRefusedException
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

class BakeInPlaceTest {
    @Test
    fun aBakedDocumentKeepsItsMetadataOnTheNewHash() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val before = rig.docId("a.pdf")
            rig.session.meta.applyEdits(
                listOf(before),
                addTags = listOf("filed"),
                fields = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin"),
            )

            rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

            val after = rig.docId("a.pdf")
            after shouldNotBe before
            rig.db
                .documentDao()
                .getByDocId(after)
                ?.relPath shouldBe "a.pdf"
            rig.db
                .documentFieldDao()
                .valuesOf(after) shouldBe
                mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")
            rig.db
                .tagDao()
                .tagsForDocument(after) shouldContainExactly listOf("filed")
            rig.db
                .documentDao()
                .getByDocId(before)
                ?.deleted shouldBe true
        }

    /**
     * An edit to a baked document projects it with what it inherited, as a rescan does: marking it
     * reviewed keeps its title, correspondent and tag in the index and in search.
     */
    @Test
    fun anEditToABakedDocumentKeepsWhatItInherited() =
        appSessionTest { rig ->
            val (before, after) = bakeFiled(rig)

            rig.session.meta.setReviewed(listOf(after), true)

            fieldsOf(rig, after) shouldBe FILED
            rig.db
                .tagDao()
                .tagsForDocument(after) shouldContainExactly listOf("filed")
            rig.db.ftsDao().searchDocIds("water") shouldContain after
            // Projected with it, the original stays superseded, as a rescan leaves it.
            val superseded = rig.db.documentDao().getByDocId(before)
            superseded?.deleted shouldBe true
            rig.session.rescan()
            rig.db.documentDao().getByDocId(before) shouldBe superseded
        }

    /** The storage-path rule after an edit to a baked document files it by what it inherited. */
    @Test
    fun anEditToABakedDocumentFilesItByWhatItInherited() =
        appSessionTest(storagePath = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)) { rig ->
            val filed = "Aquafin/Water bill.pdf"
            val (_, after) = bakeFiled(rig)
            rig.docId(filed) shouldBe after

            rig.session.meta.addTag(listOf(after), "x")

            rig.db
                .documentDao()
                .getByDocId(after)
                ?.relPath shouldBe filed
            rig.docId(filed) shouldBe after
        }

    /** What a baked document inherited is its own: a clear clears it and a removal removes it, for good. */
    @Test
    fun whatABakedDocumentInheritedCanBeCleared() =
        appSessionTest { rig ->
            val (_, after) = bakeFiled(rig)

            rig.session.meta.setField(after, FieldKeys.TITLE, null)
            rig.session.meta.removeTag(listOf(after), "filed")

            val cleared = mapOf(FieldKeys.CORRESPONDENT to "Aquafin")
            fieldsOf(rig, after) shouldBe cleared
            rig.db
                .tagDao()
                .tagsForDocument(after)
                .shouldBeEmpty()
            rig.session.rescan()
            fieldsOf(rig, after) shouldBe cleared
            rig.db
                .tagDao()
                .tagsForDocument(after)
                .shouldBeEmpty()
        }

    /** A document baked twice shows the title it was given between the bakes, not the first one. */
    @Test
    fun aDocumentBakedTwiceShowsItsLatestTitle() =
        appSessionTest { rig ->
            val (_, middle) = bakeFiled(rig)
            rig.session.meta.setField(middle, FieldKeys.TITLE, "Sewer bill")

            rig.session.content.bakeInPlace(middle) shouldBe BakeOutcome.Baked

            val last = rig.docId("a.pdf")
            val latest = FILED + (FieldKeys.TITLE to "Sewer bill")
            fieldsOf(rig, last) shouldBe latest
            rig.session.rescan()
            fieldsOf(rig, last) shouldBe latest
        }

    /**
     * A baked document's original bytes are an earlier version of it, which no journal event makes
     * live again: importing them adds no file and journals nothing, and finds the baked document.
     */
    @Test
    fun theOriginalBytesOfABakedDocumentAreADuplicateOfIt() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, after) = baked(rig, original)
            val superseded = rig.db.documentDao().getByDocId(before)
            val objects = rig.journalObjectCount()

            rig.session.ingest.importPdfBytes(original, "a.pdf") shouldBe ImportOutcome.Duplicate(after, "a.pdf")

            libraryPdfs(rig) shouldContainExactly listOf("a.pdf")
            rig.journalObjectCount() shouldBe objects
            rig.session.rescan().added shouldBe 0
            rig.db.documentDao().getByDocId(before) shouldBe superseded
            libraryPdfs(rig) shouldContainExactly listOf("a.pdf")
        }

    /** With the baked document in the trash, the library shows nothing those bytes stand for: the import is refused. */
    @Test
    fun theOriginalBytesOfABakedDocumentInTheTrashAreRefused() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, after) = baked(rig, original)
            rig.session.docLifecycle.trashDoc(after)
            val superseded = rig.db.documentDao().getByDocId(before)
            val objects = rig.journalObjectCount()

            rig.session.ingest.importPdfBytes(original, "a.pdf") shouldBe
                ImportOutcome.Failed(ImportFailure.SUPERSEDED)

            libraryPdfs(rig).shouldBeEmpty()
            rig.journalObjectCount() shouldBe objects
            rig.session.rescan().added shouldBe 0
            rig.db.documentDao().getByDocId(before) shouldBe superseded
            rig.db
                .documentDao()
                .getByDocId(after)
                ?.trashed shouldBe true
            libraryPdfs(rig).shouldBeEmpty()
        }

    /**
     * A file in the library holding a baked document's original bytes gets no row: no rescan counts
     * it as added, offers it for a relink, or rewrites the row of the document it was.
     */
    @Test
    fun aFileWithTheOriginalBytesOfABakedDocumentGetsNoRow() =
        appSessionTest { rig ->
            val original = PdfFixtures.embeddedTextPdf("alpha")
            val (before, _) = baked(rig, original)
            val superseded = rig.db.documentDao().getByDocId(before)
            rig.writePdf("copy.pdf", original)

            repeat(2) {
                val report = rig.session.rescan()
                report.added shouldBe 0
                report.relinkCandidates.shouldBeEmpty()
            }

            rig.db.documentDao().getByDocId(before) shouldBe superseded
        }

    @Test
    fun aDocumentThatIsGoneIsNotBaked() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)

            rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
        }

    /** The file at a document's path was replaced and the index has not looked yet: that file is not baked. */
    @Test
    fun aDocumentWhosePathHoldsAnotherFileIsNotBaked() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val replacement = PdfFixtures.embeddedTextPdf("replacement")
            rig.fs.delete("a.pdf")
            rig.writePdf("a.pdf", replacement)

            rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

            rig.fs.readBytes("a.pdf") shouldBe replacement
        }

    /**
     * Another document's bytes replace the file while the bake waits for the writer lock (a sync
     * tool): the bake reads them, finds they are not the document's and refuses, with nothing
     * written or relinked.
     */
    @Test
    fun aFileReplacedBeforeTheBakeReadsItIsNotBaked() = replacedDuringABake(Replacing.Moment.BEFORE_READ)

    /**
     * Another document's bytes replace the file while the bake rewrites its copy: the bake leaves
     * them where they are and removes its own rewrite, rather than deleting them to put it in place.
     */
    @Test
    fun aFileReplacedWhileItIsBakedIsLeftAlone() = replacedDuringABake(Replacing.Moment.AFTER_REWRITE)

    private fun replacedDuringABake(moment: Replacing.Moment) =
        runTest {
            val other = PdfFixtures.embeddedTextPdf("another document, written over the one being baked")
            val replacing = Replacing("a.pdf", other, moment)
            AppSessionRig.create(backgroundScope, files = replacing::over).use { rig ->
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                rig.session.work.drainOcr()
                val id = rig.docId("a.pdf")
                val objects = rig.journalObjectCount()
                replacing.armed = true

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)

                rig.fs.readBytes("a.pdf") shouldBe other
                rig.journalObjectCount() shouldBe objects
                rig.exists(NpnPaths.Temp.bakePath("a.pdf")) shouldBe false
            }
        }

    /**
     * Two bakes of one document at once, as the side panel and the full screen can each start one: the
     * second, after a change to the title, starts while the first holds its verified rewrite. They run
     * one after the other, so the second finds the document superseded, and the file at its path is
     * the one the relink names, with the document's metadata.
     */
    @Test
    fun twoBakesOfADocumentAtOnceRunOneAfterTheOther() =
        runTest {
            val readBacks = PausedReadBacks("a.pdf")
            val rig = AppSessionRig.create(backgroundScope, files = readBacks::over)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.setField(id, FieldKeys.TITLE, "Water bill")

                val first = async { rig.session.content.bakeInPlace(id) }
                readBacks.reached.receive()
                rig.session.meta.setField(id, FieldKeys.TITLE, "Sewer bill")
                val second = async { rig.session.content.bakeInPlace(id) }
                val overlapped = readBacks.nextWithin(QUIET_MILLIS)
                readBacks.release(0)
                val firstOutcome = first.await()
                readBacks.release(1)
                val secondOutcome = second.await()

                // The second waited for the first, rather than rewrite the same temporary file meanwhile.
                overlapped shouldBe false
                listOf(firstOutcome, secondOutcome).count { it == BakeOutcome.Baked } shouldBe 1
                rig.session.rescan()
                val baked = rig.docId("a.pdf")
                fieldsOf(rig, baked)[FieldKeys.TITLE] shouldBe "Sewer bill"
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.deleted shouldBe true
                libraryPdfs(rig) shouldContainExactly listOf("a.pdf")
            } finally {
                readBacks.releaseAll()
                rig.close()
            }
        }

    @Test
    fun aFileThatCannotCarryMetadataIsRefusedAndLeftAlone() =
        runTest {
            val log = RecordingLog()
            AppSessionRig.create(backgroundScope, log = log).use { rig ->
                val bytes = "not really a pdf".encodeToByteArray()
                rig.writePdf("broken.pdf", bytes)
                rig.session.rescan()
                val id = rig.docId("broken.pdf")

                rig.session.content.bakeInPlace(id) shouldBe BakeOutcome.Failed(BakeFailure.UNSUPPORTED_PDF)

                rig.docId("broken.pdf") shouldBe id
                log.entries.any { it.error is BakeRefusedException } shouldBe true
            }
        }

    /**
     * Give the document at `a.pdf` the [FILED] fields and the tag `filed`, then bake it where they
     * filed it; returns its id before the bake and after.
     */
    private suspend fun bakeFiled(rig: AppSessionRig): Pair<String, String> {
        rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.meta.applyEdits(listOf(before), addTags = listOf("filed"), fields = FILED)
        val path = checkNotNull(rig.db.documentDao().getByDocId(before)).relPath
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId(path)
    }

    private suspend fun fieldsOf(
        rig: AppSessionRig,
        docId: String,
    ) = rig.db
        .documentFieldDao()
        .valuesOf(docId)

    /** Bake the document [original] makes at `a.pdf`; returns its id before the bake and after. */
    private suspend fun baked(
        rig: AppSessionRig,
        original: ByteArray,
    ): Pair<String, String> {
        rig.writePdf("a.pdf", original)
        rig.session.rescan()
        val before = rig.docId("a.pdf")
        rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
        return before to rig.docId("a.pdf")
    }

    private suspend fun libraryPdfs(rig: AppSessionRig) =
        rig.fs
            .listAllIndexable()
            .filter { !it.isDirectory }
            .map { it.relPath }

    /**
     * A library filesystem over which, once [armed], [bytes] replace the file at [path] once, at
     * [moment] of a bake: as another app or a sync tool would write over it.
     */
    private class Replacing(
        private val path: String,
        private val bytes: ByteArray,
        private val moment: Moment,
    ) {
        enum class Moment {
            /** Right before the file at [path] is read. */
            BEFORE_READ,

            /** Right after the bake wrote its rewritten copy beside it. */
            AFTER_REWRITE,
        }

        @Volatile var armed = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray {
                    if (moment == Moment.BEFORE_READ && relPath == path) replace(fs)
                    return fs.readBytes(relPath)
                }

                override suspend fun writeBytesOnce(
                    relPath: String,
                    bytes: ByteArray,
                ) {
                    fs.writeBytesOnce(relPath, bytes)
                    if (moment == Moment.AFTER_REWRITE && relPath == NpnPaths.Temp.bakePath(path)) replace(fs)
                }
            }

        private suspend fun replace(fs: LibraryFileSystem) {
            if (!armed) return
            armed = false
            fs.delete(path)
            fs.writeBytesOnce(path, bytes)
        }
    }

    /**
     * A library filesystem over which each bake's read-back of its rewrite of [path] (the n-th) says so
     * on [reached], then waits until the n-th is [release]d: a bake held right after it verified its
     * temporary file, before it swaps it in. [realTime] runs [nextWithin]'s wait on the wall clock.
     */
    private class PausedReadBacks(
        private val path: String,
        private val realTime: CoroutineDispatcher = Dispatchers.Default,
    ) {
        val reached = Channel<Unit>(Channel.UNLIMITED)
        private val gates = List(2) { CompletableDeferred<Unit>() }
        private val reads = AtomicInteger()

        /** Whether another read-back is [reached] within [millis] of real time, not the test's virtual time. */
        suspend fun nextWithin(millis: Long): Boolean =
            withContext(realTime) { withTimeoutOrNull(millis) { reached.receive() } != null }

        fun release(n: Int) = gates[n].complete(Unit)

        fun releaseAll() = gates.forEach { it.complete(Unit) }

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray {
                    val bytes = fs.readBytes(relPath)
                    if (relPath == NpnPaths.Temp.bakePath(path)) {
                        val n = reads.getAndIncrement()
                        reached.send(Unit)
                        gates.getOrNull(n)?.await()
                    }
                    return bytes
                }
            }
    }

    private companion object {
        val FILED = mapOf(FieldKeys.TITLE to "Water bill", FieldKeys.CORRESPONDENT to "Aquafin")

        /** How long a bake that does not wait gets to reach its read-back. */
        const val QUIET_MILLIS = 1_000L
    }
}

/**
 * A trash of a document while it is baked in place: it waits for the bake, then acts on the document
 * the bake made.
 */
class TrashDuringABakeTest {
    /**
     * A trash of a document being baked that lands after the bake put its rewrite in place and before
     * its relink: it waits for the bake and trashes the document the bake made, which its Undo brings
     * back. Landing first, it was recorded against bytes no longer there, and the baked document came
     * back live with the relink.
     */
    @Test
    fun aTrashDuringABakeTrashesTheBakedDocument() = trashedDuringABake(PausedBake.Moment.AFTER_SWAP)

    /** The same for a trash that lands after the bake checked its file is unchanged and before it deletes it. */
    @Test
    fun aTrashBeforeABakeSwapsTrashesTheBakedDocument() = trashedDuringABake(PausedBake.Moment.BEFORE_DELETE)

    private fun trashedDuringABake(moment: PausedBake.Moment) =
        runTest {
            val paused = PausedBake("a.pdf", moment)
            val rig = AppSessionRig.create(backgroundScope, files = paused::over)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                paused.armed.set(true)

                val bake = async { rig.session.content.bakeInPlace(id) }
                paused.reached.await()
                val trash = async { rig.session.docLifecycle.trashDoc(id) }
                // Time for the trash to land in the bake's window, as it would if it did not wait.
                paused.finishesWithin(trash, QUIET_MILLIS) shouldBe false
                paused.release()
                bake.await() shouldBe BakeOutcome.Baked
                val receipt = trash.await().shouldNotBeNull()
                rig.session.rescan()

                rig.db
                    .documentDao()
                    .getByRelPath("a.pdf")
                    ?.isLive shouldNotBe true
                rig.exists("a.pdf") shouldBe false
                val baked = receipt.docId
                baked shouldNotBe id
                rig.db
                    .documentDao()
                    .getByDocId(baked)
                    ?.trashed shouldBe true

                rig.session.docLifecycle.undoTrash(listOf(receipt)) shouldBe 0
                rig.session.rescan()
                val restored =
                    rig.db
                        .documentDao()
                        .getByDocId(baked)
                        .shouldNotBeNull()
                restored.isLive shouldBe true
                restored.relPath shouldBe "a.pdf"
                rig.docId("a.pdf") shouldBe baked
            } finally {
                paused.release()
                rig.close()
            }
        }

    private companion object {
        /** How long a trash that does not wait gets to finish. */
        const val QUIET_MILLIS = 1_000L
    }
}

/**
 * A bake rewrites the metadata a PDF carries, never a page, so the baked document has the text its
 * original had: nothing is OCR'd or enriched again.
 */
class BakedTextTest {
    /**
     * The original was read by the AI, pinned to that text and enriched: once baked it is searchable
     * by the same text at once, the local engine never runs and nothing is paid for again.
     */
    @Test
    fun aBakedDocumentKeepsTheTextOfItsOriginal() =
        runTest {
            val gateway = FakeSseGateway("").start()
            val endpoint =
                AiConfig(
                    enabled = true,
                    provider = AiProvider.CUSTOM,
                    baseUrl = gateway.baseUrl,
                    model = "vision",
                    autoOnOcr = true,
                )
            val local = FakeOcrEngine(isAvailable = true, name = OcrEngineId.TESSERACT.column, canned = "local words")
            val rig = AppSessionRig.create(backgroundScope, ocr = local, aiConfig = { endpoint })
            try {
                rig.writePdf("scan.pdf", PdfFixtures.imageOnlyPdf())
                val original = rig.docId("scan.pdf")
                // What "OCR with AI" left on this device: the model's transcription, and the pin to it.
                publishSidecar(rig, original, OcrEngineId.AI, TRANSCRIPTION)
                rig.session.rescan()
                rig.session.ocr.setOcrEngine(original, OcrEngineId.AI.column)
                rig.deviceDb.aiEnrichQueueDao().markEnriched(AiEnrichedEntity(original, ENRICHED_AT))

                rig.session.content.bakeInPlace(original) shouldBe BakeOutcome.Baked
                val baked = rig.docId("scan.pdf")
                rig.session.rescan()
                rig.session.work.drainOcr()
                rig.session.work.drainEnrichment()

                local.calls shouldBe 0
                rig.db.ftsDao().contentForDoc(baked) shouldBe TRANSCRIPTION
                val row =
                    rig.db
                        .documentDao()
                        .getByDocId(baked)
                        .shouldNotBeNull()
                row.ocrEngine shouldBe OcrEngineId.AI.column
                row.ocrState shouldBe OcrState.DONE.column
                rig.deviceDb.aiEnrichQueueDao().getByDocId(baked) shouldBe null
                rig.deviceDb.aiEnrichQueueDao().enrichedAt(baked) shouldBe ENRICHED_AT
                gateway.requestedPaths.shouldBeEmpty()
            } finally {
                rig.close()
                gateway.stop()
            }
        }

    /** A relink to other bytes, as the user makes one for an edited document, hands on no text. */
    @Test
    fun aRelinkToOtherBytesHandsOnNoText() =
        appSessionTest { rig ->
            rig.writePdf("scan.pdf", PdfFixtures.imageOnlyPdf())
            val original = rig.docId("scan.pdf")
            publishSidecar(rig, original, OcrEngineId.AI, TRANSCRIPTION)
            rig.session.rescan()
            rig.fs.delete("scan.pdf")
            rig.writePdf("edited.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            rig.session.rescan()
            val edited = rig.docId("edited.pdf")

            rig.session.maintenance.applyRelink(original, edited)

            rig.session.ocr
                .ocrVersions(edited)
                .map { it.engine } shouldNotContain OcrEngineId.AI.column
            rig.db.ftsDao().contentForDoc(edited) shouldNotBe TRANSCRIPTION
        }

    /** Publish [text] as [docId]'s one-page sidecar of [engine], written by this device. */
    private suspend fun publishSidecar(
        rig: AppSessionRig,
        docId: String,
        engine: OcrEngineId,
        text: String,
    ) {
        val device = rig.session.deviceId
        val sidecar =
            OcrSidecar(
                doc = docId,
                dev = device,
                engine = engine.column,
                lang = "eng",
                createdAt = 1L,
                pages = listOf(text),
            )
        OcrSidecarFiles(
            rig.fs,
        ).writeOnce(docId, OcrSidecarName.writerKey(device, engine.column), OcrSidecarCodec.encode(sidecar))
    }

    private companion object {
        const val TRANSCRIPTION = "the paid transcription"
        const val ENRICHED_AT = 1_000L
    }
}

/**
 * A library filesystem over which, once [armed], a bake of [path] says so on [reached] at [moment]
 * of its swap, then waits until [release]. [realTime] runs [finishesWithin]'s wait on the wall clock.
 */
private class PausedBake(
    private val path: String,
    private val moment: Moment,
    private val realTime: CoroutineDispatcher = Dispatchers.Default,
) {
    enum class Moment {
        /** Once the bake checked the file is the one it read, right before it deletes it. */
        BEFORE_DELETE,

        /** Right after the bake moved its rewrite into place, before its relink. */
        AFTER_SWAP,
    }

    val armed = AtomicBoolean(false)
    val reached = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    /** Whether [job] finishes within [millis] of real time, not the test's virtual time. */
    suspend fun finishesWithin(
        job: Job,
        millis: Long,
    ): Boolean = withContext(realTime) { withTimeoutOrNull(millis) { job.join() } != null }

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun delete(relPath: String) {
                if (moment == Moment.BEFORE_DELETE && relPath == path) pause()
                fs.delete(relPath)
            }

            override suspend fun move(
                fromRelPath: String,
                toRelPath: String,
            ) {
                fs.move(fromRelPath, toRelPath)
                val swap = fromRelPath == NpnPaths.Temp.bakePath(path) && toRelPath == path
                if (moment == Moment.AFTER_SWAP && swap) pause()
            }
        }

    private suspend fun pause() {
        if (!armed.getAndSet(false)) return
        reached.complete(Unit)
        gate.await()
    }
}
