package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FakeSseGateway
import dev.npn.core.db.AiEnrichedEntity
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrSidecar
import dev.npn.core.ocr.OcrSidecarCodec
import dev.npn.core.ocr.OcrSidecarFiles
import dev.npn.core.ocr.OcrSidecarName
import dev.npn.core.ocr.OcrState
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

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
                rig.session.ocr.setOcrEngine(original, OcrEngineRef.Known(OcrEngineId.AI))
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
                .map { it.engine } shouldNotContain OcrEngineRef.Known(OcrEngineId.AI)
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
