package dev.npn.core.ocr

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.pdf.PdfBoxEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class OcrPipelineTest {

    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var db: NpnDatabase

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-ocr-lib")
        dbDir = Files.createTempDirectory("npn-ocr-db").toFile()
        fs = JvmLibraryFileSystem(libRoot)
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun pipeline(
        deviceId: String,
        ocr: OcrEngine,
        database: NpnDatabase = db,
    ) = OcrPipeline(
        fs = fs,
        sidecars = OcrSidecarFiles(fs),
        pdfEngine = PdfBoxEngine(),
        ocrEngine = ocr,
        documentDao = database.documentDao(),
        ftsDao = database.ftsDao(),
        ocrQueueDao = database.ocrQueueDao(),
        tagDao = database.tagDao(),
        deviceId = deviceId,
    )

    private suspend fun stageDoc(relPath: String, bytes: ByteArray): String {
        fs.writeBytesOnce(relPath, bytes)
        val docId = fs.sha256(relPath)
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId,
                relPath = relPath,
                fileName = relPath.substringAfterLast('/'),
                size = bytes.size.toLong(),
                mtime = 1000,
                addedAt = 1000,
            ),
        )
        db.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))
        return docId
    }

    @Test
    fun embeddedTextDocUsesEmbeddedEngineWithNoOcrCalls() = runTest {
        val fake = FakeOcrEngine(available = true, name = "mlkit")
        val bytes = PdfFixtures.embeddedTextPdf("invoice 42 acme corporation")
        val docId = stageDoc("docs/inv.pdf", bytes)

        pipeline("devA", fake).processDoc(docId)

        fake.calls shouldBe 0
        db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
        db.ftsDao().searchDocIds("invoice") shouldContainExactly listOf(docId)

        // Sidecar written once for this device, engine=embedded.
        val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA"))
        sidecar.engine shouldBe OcrPipeline.ENGINE_EMBEDDED
        sidecar.pages.size shouldBe 2
    }

    @Test
    fun imageOnlyDocRunsOcrPerPage() = runTest {
        val fake = FakeOcrEngine(available = true, name = "mlkit", canned = "scanned words here")
        val bytes = PdfFixtures.imageOnlyPdf()
        val docId = stageDoc("scan.pdf", bytes)

        pipeline("devA", fake).processDoc(docId)

        fake.calls shouldBe 1 // one page
        val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA"))
        sidecar.engine shouldBe "mlkit"
        db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
        db.ftsDao().searchDocIds("scanned") shouldContainExactly listOf(docId)
    }

    @Test
    fun secondDeviceAdoptsSidecarWithoutOcr() = runTest {
        // Device A OCRs an image-only doc.
        val fakeA = FakeOcrEngine(available = true, name = "mlkit", canned = "alpha bravo")
        val bytes = PdfFixtures.imageOnlyPdf()
        val docId = stageDoc("scan.pdf", bytes)
        pipeline("devA", fakeA).processDoc(docId)
        fakeA.calls shouldBeGreaterThan 0

        // Device B: fresh DB, SAME library dir (so the sidecar is visible).
        val dbB = npnDatabase(File(dbDir, "indexB.db"))
        try {
            dbB.documentDao().upsert(
                DocumentEntity(
                    docId = docId,
                    relPath = "scan.pdf",
                    fileName = "scan.pdf",
                    size = bytes.size.toLong(),
                    mtime = 1000,
                    addedAt = 1000,
                ),
            )
            dbB.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))

            val fakeB = FakeOcrEngine(available = true, name = "mlkit", canned = "should-not-run")
            pipeline("devB", fakeB, dbB).processDoc(docId)

            fakeB.calls shouldBe 0
            dbB.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_ADOPTED
            dbB.ftsDao().searchDocIds("alpha") shouldContainExactly listOf(docId)
        } finally {
            dbB.close()
        }
    }

    @Test
    fun processNextDrivesQueueStateToDone() = runTest {
        val fake = FakeOcrEngine(available = true, name = "mlkit")
        val docId = stageDoc("docs/inv.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))

        pipeline("devA", fake).processNext() shouldBe true
        val entry = db.ocrQueueDao().observeQueue().first().first { it.docId == docId }
        entry.state shouldBe OcrPipeline.STATE_DONE
    }

    @Test
    fun failureIncrementsAttemptsAndFailsAfterMax() = runTest {
        val throwing = ThrowingOcrEngine()
        val bytes = PdfFixtures.imageOnlyPdf()
        val docId = stageDoc("scan.pdf", bytes)
        val pipe = pipeline("devA", throwing)

        // attempt 1 -> pending (attempts=1)
        pipe.processDoc(docId)
        var entry = db.ocrQueueDao().observeQueue().first().first { it.docId == docId }
        entry.attempts shouldBe 1
        entry.state shouldBe OcrPipeline.STATE_PENDING

        // reset to pending for the next pop (nextPending picks pending rows)
        pipe.processNext()
        entry = db.ocrQueueDao().observeQueue().first().first { it.docId == docId }
        entry.attempts shouldBe 2
        entry.state shouldBe OcrPipeline.STATE_PENDING

        pipe.processNext()
        entry = db.ocrQueueDao().observeQueue().first().first { it.docId == docId }
        entry.attempts shouldBe 3
        entry.state shouldBe OcrPipeline.STATE_FAILED
        db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_FAILED
    }

    @Test
    fun invalidPdfFailsGracefully() = runTest {
        val fake = FakeOcrEngine(available = true, name = "mlkit")
        fs.writeBytesOnce("broken.pdf", "not a pdf".encodeToByteArray())
        val docId = fs.sha256("broken.pdf")
        db.documentDao().upsert(
            DocumentEntity(
                docId = docId,
                relPath = "broken.pdf",
                fileName = "broken.pdf",
                size = 10,
                mtime = 1,
                addedAt = 1,
            ),
        )
        db.ocrQueueDao().upsert(OcrQueueEntity(docId, "pending", updatedAt = 1))

        pipeline("devA", fake).processDoc(docId)
        val entry = db.ocrQueueDao().observeQueue().first().first { it.docId == docId }
        entry.attempts shouldBe 1
        entry.state shouldBe OcrPipeline.STATE_PENDING // < maxAttempts
    }

    @Test
    fun adoptForeignSidecarsIndexesAndClearsQueue() = runTest {
        val bytes = PdfFixtures.imageOnlyPdf()
        val docId = stageDoc("scan.pdf", bytes)
        // Write a foreign sidecar as if produced by another device.
        val foreign = OcrSidecar(
            doc = docId, dev = "otherdev", engine = "tesseract", lang = "eng",
            createdAt = 1, pages = listOf("foreign charlie text"),
        )
        OcrSidecarFiles(fs).writeOnce(docId, "otherdev", OcrSidecarCodec.encode(foreign))

        pipeline("devA", FakeOcrEngine(available = true, name = "mlkit")).adoptForeignSidecars()

        db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_ADOPTED
        db.ftsDao().searchDocIds("charlie") shouldContainExactly listOf(docId)
        db.ocrQueueDao().observeQueue().first().none { it.docId == docId } shouldBe true
    }

    @Test
    fun workerDrainsQueue() = runTest {
        val fake = FakeOcrEngine(available = true, name = "mlkit")
        stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
        stageDoc("b.pdf", PdfFixtures.embeddedTextPdf("another invoice document here"))

        val worker = OcrWorker(pipeline("devA", fake), this)
        worker.drain() shouldBe 2
        db.ocrQueueDao().observeQueue().first().all { it.state == OcrPipeline.STATE_DONE } shouldBe true
    }
}

/** Records how many times OCR was invoked and returns canned text. */
private class FakeOcrEngine(
    override val available: Boolean,
    override val name: String,
    private val canned: String = "canned ocr text",
) : OcrEngine {
    var calls = 0
        private set

    override suspend fun recognizePng(png: ByteArray, lang: String): String {
        calls++
        return canned
    }
}

private class ThrowingOcrEngine : OcrEngine {
    override val available: Boolean = true
    override val name: String = "mlkit"
    override suspend fun recognizePng(png: ByteArray, lang: String): String =
        throw RuntimeException("boom")
}
