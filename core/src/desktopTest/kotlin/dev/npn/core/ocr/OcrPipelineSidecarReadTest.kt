package dev.npn.core.ocr

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
}
