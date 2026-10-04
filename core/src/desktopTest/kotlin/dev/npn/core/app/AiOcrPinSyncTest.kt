package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FakeSseGateway
import dev.npn.core.db.NpnDatabase
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.ocr.OcrEngines
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * "OCR with AI" is a request made on one device, and the engine pin it writes is synced library
 * data. Another replica with an endpoint of its own must take the pin as a selection preference,
 * not as a request to pay for a transcription of its own.
 */
class AiOcrPinSyncTest {
    @Test
    fun anAiPinFromAnotherDeviceSpendsNothingOnAReplicaWithAnEndpoint() =
        runTest {
            val gateway = FakeSseGateway("").start()
            val rig = AppSessionRig.create(backgroundScope)
            var secondDb: NpnDatabase? = null
            try {
                rig.writePdf("scan.pdf", PdfFixtures.imageOnlyPdf())
                rig.session.rescan()
                val docId = rig.docId("scan.pdf")

                // Device B has a vision endpoint configured, but not as its automatic engine.
                val endpoint =
                    AiConfig(enabled = true, provider = AiProvider.CUSTOM, baseUrl = gateway.baseUrl, model = "vision")
                val local = FakeOcrEngine(isAvailable = true, name = OcrEngines.MLKIT, canned = "local words")
                val (second, db) = rig.secondSession(backgroundScope, ocr = local, aiConfig = { endpoint })
                secondDb = db
                second.rescan()
                second.drainOcr()
                local.calls shouldBe 1

                // Device A pins the document to the AI engine; B ingests that event.
                rig.session.ocr.setOcrEngine(docId, OcrEngines.AI)
                second.rescan()
                second.drainOcr()

                gateway.requestedPaths.shouldBeEmpty()
                val doc = db.documentDao().getByDocId(docId).shouldNotBeNull()
                doc.ocrEngineOverride shouldBe OcrEngines.AI
                doc.ocrEngine shouldBe OcrEngines.MLKIT

                // The endpoint is live: asked for on B itself, the run does reach it.
                second.ocr.ocrWithAi(docId)
                second.drainOcr()
                gateway.requestedPaths.shouldNotBeEmpty()

                second.close()
            } finally {
                secondDb?.close()
                rig.close()
                gateway.stop()
            }
        }
}
