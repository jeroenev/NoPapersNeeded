package dev.npn.core.ocr

import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** How the pipeline reads stored sidecars when its callers are stopped or run at once. */
class OcrPipelineSidecarReadTest {
    private val rig = OcrPipelineRig()
    private val fs get() = rig.fs
    private val db get() = rig.db

    @AfterTest
    fun tearDown() = rig.close()

    /**
     * A sidecar read cut short by the drain stopping is not an unreadable sidecar: read that way,
     * the document was charged a failed attempt for an interrupted pass.
     */
    @Test
    fun aCancelledSidecarReadChargesNoAttempt() =
        runTest {
            val docId = rig.stageDoc("docs/a.pdf", PdfFixtures.embeddedTextPdf("some text"))
            rig.writeForeignSidecar(docId, dev = "olddev", engine = "mixed", text = "legacy words")
            val stopping =
                object : LibraryFileSystem by fs {
                    override suspend fun readBytes(relPath: String): ByteArray {
                        if (relPath.startsWith(NpnPaths.OCR_DIR)) throw CancellationException("drain stopped")
                        return fs.readBytes(relPath)
                    }
                }
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit"), files = stopping)

            assertFailsWith<CancellationException> { pipeline.processDoc(docId) }

            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .attempts shouldBe 0
        }

    /**
     * A sidecar cut short mid-write (SAF writes at the final name, so a kill leaves one) is listed
     * like any other, and an engine-qualified one ranks without being read. Selected over a version
     * that reads, it left the document unsearchable and charged it an attempt per pass, until it
     * failed for good.
     */
    @Test
    fun aTruncatedSidecarIsPassedOverForTheNextBest() =
        runTest {
            val docId = rig.stageDoc("docs/a.pdf", PdfFixtures.embeddedTextPdf("some text"))
            publish(docId, "devB.tesseract", sidecarJson(docId, "devB", "tesseract", "readable words"))
            publish(docId, "devC.ai", sidecarJson(docId, "devC", "ai", "cut short").take(TRUNCATED))

            rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit")).processDoc(docId)

            val doc = db.documentDao().requireByDocId(docId)
            doc.ocrSource shouldBe "devB.tesseract"
            doc.ocrState shouldBe OcrState.ADOPTED.column
            db.ftsDao().searchDocIds("readable") shouldContainExactly listOf(docId)
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .attempts shouldBe 0
        }

    /** A reconcile adopts the next best too, rather than leave the document without text. */
    @Test
    fun aReconcilePassesOverATruncatedSidecar() =
        runTest {
            val docId = rig.stageDoc("docs/a.pdf", PdfFixtures.embeddedTextPdf("some text"))
            publish(docId, "devB.tesseract", sidecarJson(docId, "devB", "tesseract", "readable words"))
            publish(docId, "devC.ai", sidecarJson(docId, "devC", "ai", "cut short").take(TRUNCATED))

            rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit")).reconcileSidecars()

            db.documentDao().requireByDocId(docId).ocrSource shouldBe "devB.tesseract"
        }

    /** This device's own run cut short counts as no run: the engine runs again, under a new key. */
    @Test
    fun aTruncatedSidecarOfThisDevicesEngineIsRunAgain() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            publish(docId, "devA.mlkit", sidecarJson(docId, "devA", "mlkit", "cut short").take(TRUNCATED))
            val engine = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "scanned words")

            rig.pipeline("devA", engine).processDoc(docId)

            engine.calls shouldBe 1
            val doc = db.documentDao().requireByDocId(docId)
            doc.ocrState shouldBe OcrState.DONE.column
            doc.ocrSource shouldNotBe "devA.mlkit"
            db.ftsDao().searchDocIds("scanned") shouldContainExactly listOf(docId)
        }

    private suspend fun publish(
        docId: String,
        writerKey: String,
        json: String,
    ) = OcrSidecarFiles(fs).writeOnce(docId, writerKey, json)

    private fun sidecarJson(
        docId: String,
        dev: String,
        engine: String,
        text: String,
    ): String =
        OcrSidecarCodec.encode(
            OcrSidecar(doc = docId, dev = dev, engine = engine, lang = "eng", createdAt = 1, pages = listOf(text)),
        )

    /**
     * The drain and the reconciles that rescans, watch events and engine pins start all run on
     * Dispatchers.Default and share the pipeline's caches of sidecar facts. A guard rather than a
     * reproduction: a plain HashMap raced like this fails only now and then.
     */
    @Test
    fun concurrentReconcilesAgreeOnEveryLegacySidecar() =
        runTest {
            val docIds =
                (1..40).map { i ->
                    rig.stageDoc("docs/doc$i.pdf", PdfFixtures.embeddedTextPdf("document number $i")).also {
                        rig.writeForeignSidecar(it, dev = "olddev$i", engine = "mixed", text = "legacy words $i")
                    }
                }
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = false, name = "mlkit"))

            Executors.newFixedThreadPool(8).asCoroutineDispatcher().use { threads ->
                withContext(threads) { (1..8).map { async { pipeline.reconcileSidecars() } }.awaitAll() }
            }

            for ((i, docId) in docIds.withIndex()) {
                db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
                    .ocrSource shouldBe "olddev${i + 1}"
            }
        }

    private companion object {
        /** Where a write cut short left the sidecar: inside its JSON. */
        const val TRUNCATED = 40
    }
}
