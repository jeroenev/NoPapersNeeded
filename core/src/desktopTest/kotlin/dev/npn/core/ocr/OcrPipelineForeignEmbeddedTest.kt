package dev.npn.core.ocr

import dev.npn.core.db.searchDocIds
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A library opened on a new device: another device already extracted a document's text layer.
 * Re-running this device's engine only pays when that extraction left a page without usable text.
 */
class OcrPipelineForeignEmbeddedTest {
    private lateinit var rig: OcrPipelineRig

    @BeforeTest
    fun setup() {
        rig = OcrPipelineRig()
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    @Test
    fun foreignFullTextLayerIsAdoptedNotReExtracted() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            writeEmbeddedSidecar(docId, dev = "otherdev", pages = listOf("a text layer that is long enough to count"))

            val pipe = rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"))
            pipe.reconcileSidecars()
            pipe.processDoc(docId)

            OcrSidecarFiles(rig.fs).listForHash(docId) shouldContainExactly listOf("otherdev.embedded")
            rig.db
                .documentDao()
                .getByDocId(docId)!!
                .ocrState shouldBe OcrState.ADOPTED.column
            rig.db.ftsDao().searchDocIds("layer") shouldContainExactly listOf(docId)
        }

    @Test
    fun foreignEmbeddedWithThinPageStillGetsOcr() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            // What a device without an OCR engine writes for a scanned page: no usable text.
            writeEmbeddedSidecar(docId, dev = "otherdev", pages = listOf(""))

            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "recognized words")
            rig.pipeline("devA", fake).processDoc(docId)

            fake.calls shouldBe 1
            rig.db
                .documentDao()
                .getByDocId(docId)!!
                .ocrEngine shouldBe "mlkit"
        }

    private suspend fun writeEmbeddedSidecar(
        docId: String,
        dev: String,
        pages: List<String>,
    ) {
        val sidecar =
            OcrSidecar(doc = docId, dev = dev, engine = "embedded", lang = "eng", createdAt = 1, pages = pages)
        OcrSidecarFiles(rig.fs).writeOnce(docId, "$dev.embedded", OcrSidecarCodec.encode(sidecar))
    }
}
