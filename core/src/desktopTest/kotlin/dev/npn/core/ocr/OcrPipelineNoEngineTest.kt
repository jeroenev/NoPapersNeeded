package dev.npn.core.ocr

import dev.npn.core.db.QueueState
import dev.npn.core.db.documentEntity
import dev.npn.core.db.searchDocIds
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A device with no OCR engine (desktop before Tesseract is installed) publishes a document's text
 * layer as it is, blank pages and all. Once it has an engine, it reads those pages.
 */
class OcrPipelineNoEngineTest {
    private val rig = OcrPipelineRig()
    private val fs get() = rig.fs
    private val db get() = rig.db

    @AfterTest
    fun teardown() {
        rig.close()
    }

    /**
     * A scan imported before this device had an OCR engine (desktop before Tesseract is installed)
     * gets a sidecar of its blank text layer. That is no reason not to read it once an engine is
     * there: the next reconcile queues it, and its run indexes the engine's text, once.
     */
    @Test
    fun aScanReadWithNoEngineIsOcrdOnceOneIsAvailable() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val none = FakeOcrEngine(isAvailable = false, name = OcrEngineId.TESSERACT.column)
            rig.pipeline("devA", none).processDoc(docId)
            val queue = rig.deviceDb.ocrQueueDao()
            queue.getByDocId(docId)?.state shouldBe QueueState.DONE.column
            // Still no engine: nothing to queue.
            rig.pipeline("devA", none).reconcileSidecars()
            queue.getByDocId(docId)?.state shouldBe QueueState.DONE.column

            val tesseract =
                FakeOcrEngine(isAvailable = true, name = OcrEngineId.TESSERACT.column, canned = "scanned words")
            val pipe = rig.pipeline("devA", tesseract)
            pipe.reconcileSidecars()
            queue.getByDocId(docId)?.state shouldBe QueueState.PENDING.column
            pipe.processDoc(docId)

            tesseract.calls shouldBe 1
            db.documentDao().getByDocId(docId)?.ocrEngine shouldBe OcrEngineId.TESSERACT.column
            db.ftsDao().searchDocIds("scanned") shouldContainExactly listOf(docId)
            pipe.reconcileSidecars()
            queue.getByDocId(docId)?.state shouldBe QueueState.DONE.column
        }

    /**
     * A bake hands its original's text on as this device's own ([OcrPipeline.copyText]). A blank text
     * layer read with no engine is then read once an engine is there, as the original's would have
     * been; a full one is not read again.
     */
    @Test
    fun aBakedCopyOfTextReadWithNoEngineIsOcrdOnceOneIsAvailable() =
        runTest {
            val none = rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = OcrEngineId.TESSERACT.column))
            val scan = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val letter = rig.stageDoc("letter.pdf", PdfFixtures.embeddedTextPdf("a letter with a full text layer"))
            none.processDoc(scan)
            none.processDoc(letter)
            val bakedScan = bakedDoc("baked-scan.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            val bakedLetter = bakedDoc("baked-letter.pdf", PdfFixtures.embeddedTextPdf("the letter, baked"))
            none.copyText(scan, bakedScan)
            none.copyText(letter, bakedLetter)

            val tesseract =
                FakeOcrEngine(isAvailable = true, name = OcrEngineId.TESSERACT.column, canned = "scanned words")
            rig.pipeline("devA", tesseract).reconcileSidecars()

            val queue = rig.deviceDb.ocrQueueDao()
            queue.getByDocId(bakedScan)?.state shouldBe QueueState.PENDING.column
            queue.getByDocId(bakedLetter) shouldBe null
        }

    /** A rescan's row for [relPath], a new document with no OCR run queued yet. */
    private suspend fun bakedDoc(
        relPath: String,
        bytes: ByteArray,
    ): String {
        fs.writeBytesOnce(relPath, bytes)
        val docId = fs.sha256(relPath)
        db.documentDao().upsert(documentEntity(docId, relPath, size = bytes.size.toLong()))
        return docId
    }
}
