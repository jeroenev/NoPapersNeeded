package dev.npn.core.ocr

import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Engine selection when a paid AI engine is in play. The pipeline's other behaviour (ranking,
 * adoption, attempts) is covered by [OcrPipelineTest]; what matters here is the one thing the AI
 * engine adds: it outranks everything, so *nothing but a deliberate choice* may start it.
 */
class AiOcrPipelineTest {
    private lateinit var rig: OcrPipelineRig
    private val fs get() = rig.fs
    private val db get() = rig.db

    @BeforeTest
    fun setup() {
        rig = OcrPipelineRig()
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    @Test
    fun configuredAiEngineStaysUnusedUntilItIsAskedFor() =
        runTest {
            // The trap this guards: "ai" ranks above every local engine, so a configured endpoint
            // alone would silently make the whole library a paid job.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())

            rig.pipeline("devA", local, engines = selector(local, ai, auto = false)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("local") shouldContainExactly listOf(docId)
        }

    @Test
    fun aiEngineRunsWhenItIsTheDevicesAutomaticEngine() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "transcribed by model")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 1
            local.calls shouldBe 0
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.ai")).engine shouldBe OcrEngines.AI
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrEngines.AI
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
            db.ftsDao().searchDocIds("transcribed") shouldContainExactly listOf(docId)
        }

    @Test
    fun pinningADocumentToAiRunsItWithoutTheAutomaticSetting() =
        runTest {
            // The per-document request path: OcrFacade.ocrWithAi pins the engine, and the pin alone
            // has to be enough to spend a call on this one document.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "model read the scan")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            setOverride(docId, OcrEngines.AI)

            rig.pipeline("devA", local, engines = selector(local, ai, auto = false)).processDoc(docId)

            ai.calls shouldBe 1
            local.calls shouldBe 0
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrEngines.AI
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    @Test
    fun pinnedAiRunUpgradesADocumentThisDeviceAlreadyOcrdLocally() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "blurry locel guess")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "accurate model reading")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.pipeline("devA", local, engines = selector(local, ai)).processDoc(docId)
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"

            setOverride(docId, OcrEngines.AI)
            rig.pipeline("devA", local, engines = selector(local, ai)).processDoc(docId)

            ai.calls shouldBe 1
            // Both versions co-exist (sidecars are write-once); search follows the better one.
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.mlkit")).engine shouldBe "mlkit"
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.ai")).engine shouldBe OcrEngines.AI
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrEngines.AI
            db.ftsDao().searchDocIds("accurate") shouldContainExactly listOf(docId)
            db.ftsDao().searchDocIds("locel") shouldBe emptyList()
        }

    @Test
    fun pinningALocalEngineOptsTheDocumentOutOfAutomaticAiOcr() =
        runTest {
            // The pin is the user naming an engine for this document. Running the model anyway
            // would bill a transcription that then loses the selection to the pinned version.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            setOverride(docId, "mlkit")

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("local") shouldContainExactly listOf(docId)
        }

    @Test
    fun largeDocumentIsLeftToTheLocalEngineByTheAutomaticSettingAndNotRequeuedForever() =
        runTest {
            // The threshold has to be applied where the run is queued as well as where it happens.
            // Otherwise this document is queued because "ai" outranks its stored version, produces
            // a local sidecar instead, and is queued again on every reconcile — a paid-looking
            // upgrade loop that never converges.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "should-not-run")
            val docId = rig.stageDoc("big.pdf", PdfFixtures.imageOnlyPdf(pages = 3))
            val engines = selector(local, ai, auto = true, largeDocumentPages = 2)
            val pipe = rig.pipeline("devA", local, engines = engines)

            pipe.processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 3
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.documentDao().getByDocId(docId)!!.pageCount shouldBe 3

            pipe.reconcileSidecars()

            db.ocrQueueDao().getByDocId(docId)!!.state shouldBe OcrPipeline.STATE_DONE
        }

    @Test
    fun anAskedForAiRunIsNotRefusedForBeingLarge() =
        runTest {
            // The threshold stops unattended spending, not the user. A long handwritten document
            // is exactly what the vision model is wanted for, so a pin runs it and the run
            // converges (the sidecar it publishes stops the next canImprove).
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI, canned = "model read it")
            val docId = rig.stageDoc("ledger.pdf", PdfFixtures.imageOnlyPdf(pages = 3))
            val engines = selector(local, ai, auto = false, largeDocumentPages = 2)
            val pipe = rig.pipeline("devA", local, engines = engines)
            setOverride(docId, OcrEngines.AI)

            pipe.processDoc(docId)

            ai.calls shouldBe 3
            local.calls shouldBe 0
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrEngines.AI
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)

            pipe.reconcileSidecars()

            ai.calls shouldBe 3
            db.ocrQueueDao().getByDocId(docId)!!.state shouldBe OcrPipeline.STATE_DONE
        }

    @Test
    fun unconfiguredAiEngineIsIgnoredEvenWhenAutomaticAndPinned() =
        runTest {
            // AI turned on in settings but not usable (no model/key yet). The document must still
            // get text from the local engine rather than sit unprocessed.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local fallback words")
            val ai = FakeOcrEngine(isAvailable = false, name = OcrEngines.AI, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            setOverride(docId, OcrEngines.AI)
            val engines = selector(local, ai, auto = true)

            engines.aiAvailable() shouldBe false
            rig.pipeline("devA", local, engines = engines).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("fallback") shouldContainExactly listOf(docId)
        }

    @Test
    fun aiOcrIsUnavailableWhenNoAiEngineIsWiredAtAll() =
        runTest {
            OcrEngineSelector(local = FakeOcrEngine(isAvailable = true, name = "mlkit")).aiAvailable() shouldBe false
        }

    @Test
    fun aiEngineNeverRunsOnPagesThatAlreadyHaveATextLayer() =
        runTest {
            // Embedded text short-circuits before any engine is asked, so asking for the expensive
            // engine on a born-digital PDF costs nothing. OcrFacade.ocrWithAi documents this.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngines.AI)
            val docId = rig.stageDoc("inv.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            setOverride(docId, OcrEngines.AI)

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 0
            db.documentDao().getByDocId(docId)!!.ocrEngine shouldBe OcrPipeline.ENGINE_EMBEDDED
        }

    private fun selector(
        local: OcrEngine,
        ai: OcrEngine,
        auto: Boolean = false,
        largeDocumentPages: Int = OcrEngineSelector.DEFAULT_LARGE_DOCUMENT_PAGES,
    ) = OcrEngineSelector(
        local = local,
        ai = ai,
        largeDocumentPages = largeDocumentPages,
        aiAuto = { auto },
    )

    /** Simulate the journal fold projecting a SystemProperty.OCR_ENGINE override onto the row. */
    private suspend fun setOverride(
        docId: String,
        engine: String?,
    ) {
        val doc = db.documentDao().getByDocId(docId)!!
        db.documentDao().upsert(doc.copy(ocrEngineOverride = engine))
    }
}
