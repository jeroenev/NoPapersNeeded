package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.searchDocIds
import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())

            rig.pipeline("devA", local, engines = selector(local, ai, auto = false)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe "mlkit"
            db.ftsDao().searchDocIds("local") shouldContainExactly listOf(docId)
        }

    @Test
    fun aiEngineRunsWhenItIsTheDevicesAutomaticEngine() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "transcribed by model")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 1
            local.calls shouldBe 0
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.ai")).engine shouldBe OcrEngineId.AI.column
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.AI.column
            db.documentDao().requireByDocId(docId).ocrState shouldBe OcrState.DONE.column
            db.ftsDao().searchDocIds("transcribed") shouldContainExactly listOf(docId)
        }

    @Test
    fun askingForAiOnThisDeviceRunsItWithoutTheAutomaticSetting() =
        runTest {
            // The per-document request path: OcrFacade.ocrWithAi pins the engine and marks the run as
            // asked for here, which has to be enough to spend a call on this one document.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model read the scan")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.askForAi(docId)

            rig.pipeline("devA", local, engines = selector(local, ai, auto = false)).processDoc(docId)

            ai.calls shouldBe 1
            local.calls shouldBe 0
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.AI.column
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    @Test
    fun anAskedForAiRunUpgradesADocumentThisDeviceAlreadyOcrdLocally() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "blurry locel guess")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "accurate model reading")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.pipeline("devA", local, engines = selector(local, ai)).processDoc(docId)
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe "mlkit"

            rig.askForAi(docId)
            rig.pipeline("devA", local, engines = selector(local, ai)).processDoc(docId)

            ai.calls shouldBe 1
            // Both versions co-exist (sidecars are write-once); search follows the better one.
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.mlkit")).engine shouldBe "mlkit"
            OcrSidecarCodec.decode(OcrSidecarFiles(fs).read(docId, "devA.ai")).engine shouldBe OcrEngineId.AI.column
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.AI.column
            db.ftsDao().searchDocIds("accurate") shouldContainExactly listOf(docId)
            db.ftsDao().searchDocIds("locel") shouldBe emptyList()
        }

    @Test
    fun pinningALocalEngineOptsTheDocumentOutOfAutomaticAiOcr() =
        runTest {
            // The pin is the user naming an engine for this document. Running the model anyway
            // would bill a transcription that then loses the selection to the pinned version.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.setOverride(docId, "mlkit")

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe "mlkit"
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
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("big.pdf", PdfFixtures.imageOnlyPdf(pages = 3))
            val engines = selector(local, ai, auto = true, largeDocumentPages = 2)
            val pipe = rig.pipeline("devA", local, engines = engines)

            pipe.processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 3
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe "mlkit"
            db.documentDao().requireByDocId(docId).pageCount shouldBe 3

            pipe.reconcileSidecars()

            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .state shouldBe QueueState.DONE.column
        }

    @Test
    fun anAskedForAiRunIsNotRefusedForBeingLarge() =
        runTest {
            // The threshold stops unattended spending, not the user. A long handwritten document
            // is exactly what the vision model is wanted for, so asking runs it and the run
            // converges (the sidecar it publishes stops the next canImprove).
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model read it")
            val docId = rig.stageDoc("ledger.pdf", PdfFixtures.imageOnlyPdf(pages = 3))
            val engines = selector(local, ai, auto = false, largeDocumentPages = 2)
            val pipe = rig.pipeline("devA", local, engines = engines)
            rig.askForAi(docId)

            pipe.processDoc(docId)

            ai.calls shouldBe 3
            local.calls shouldBe 0
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.AI.column
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)

            pipe.reconcileSidecars()

            ai.calls shouldBe 3
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .state shouldBe QueueState.DONE.column
        }

    @Test
    fun aRunAskedForHereSurvivesAReconcileBeforeTheDrainTakesIt() =
        runTest {
            // Between "OCR with AI" and the drain, a rescan or a synced journal object reconciles
            // the sidecars. The document reads as pending then, so reconcile re-adopts its stored
            // text; the queue row is the only record of the request and must outlive that.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model words")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devA", local, engines = selector(local, ai, auto = false))
            pipe.processDoc(docId)

            rig.askForAi(docId)
            pipe.reconcileSidecars()
            pipe.drain()

            ai.calls shouldBe 1
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.AI.column
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    /**
     * "OCR with AI" asked for while a local pass reads the document is a request that pass never
     * saw: finishing, the pass leaves the row owed with it, and the next pass spends the call.
     */
    @Test
    fun anAiRequestMadeWhileALocalPassRunsSurvivesThatPass() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model words")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val asking = rig.askingForAiOnItsFirstPage(local, docId)
            val pipe = rig.pipeline("devA", asking, engines = selector(asking, ai, auto = false))

            pipe.processDoc(docId)

            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.aiRequested shouldBe true
            pipe.processDoc(docId)
            ai.calls shouldBe 1
            db
                .documentDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .ocrEngine shouldBe OcrEngineId.AI.column
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .state shouldBe QueueState.DONE.column
        }

    /** The same request made during a local pass that then fails is owed as asked, not charged that failure. */
    @Test
    fun anAiRequestMadeWhileALocalPassFailsIsNotChargedItsFailure() =
        runTest {
            val local =
                FakeOcrEngine(isAvailable = true, name = "mlkit").apply {
                    failure =
                        IllegalStateException("blurry")
                }
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model words")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val asking = rig.askingForAiOnItsFirstPage(local, docId)
            val pipe = rig.pipeline("devA", asking, engines = selector(asking, ai, auto = false))

            pipe.processDoc(docId)

            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.aiRequested shouldBe true
            row.attempts shouldBe 0
            pipe.drain()
            ai.calls shouldBe 1
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    /**
     * "OCR with AI" asked again while the AI pass asked before fails is a request of its own, though
     * the row already carried the flag: the pass leaves it owed as asked, not charged that failure
     * or held back by its backoff, and the next drain spends the call again at once.
     */
    @Test
    fun anAiRequestAskedAgainWhileTheAiPassFailsIsNotChargedItsFailure() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val ai =
                FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "model words").apply {
                    failure = IllegalStateException("endpoint hiccup")
                }
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.askForAi(docId)
            val asking = rig.askingForAiOnItsFirstPage(ai, docId)
            val pipe = rig.pipeline("devA", local, engines = selector(local, asking, auto = false))

            pipe.processDoc(docId)

            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.aiRequested shouldBe true
            row.attempts shouldBe 0
            ai.failure = null
            pipe.drain()
            ai.calls shouldBe 2
            local.calls shouldBe 0
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    @Test
    fun anAiPinFromAnotherDeviceDoesNotSpendHere() =
        runTest {
            // The pin is synced library data; the request behind it was made on another device,
            // which pays for its own transcription. This one only follows the pin for selection.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devB", local, engines = selector(local, ai, auto = false))
            pipe.processDoc(docId)

            rig.setOverride(docId, OcrEngineId.AI.column)
            pipe.reconcileSidecars()
            pipe.drain()

            ai.calls shouldBe 0
            db.documentDao().getByDocId(docId)?.ocrEngine shouldBe "mlkit"
        }

    @Test
    fun anAiPinFromAnotherDeviceAdoptsTheAiVersionThatDevicePublished() =
        runTest {
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devB", local, engines = selector(local, ai, auto = false))
            pipe.processDoc(docId)

            // Device A's transcription arrives along with its pin.
            val fromA =
                OcrSidecar(
                    doc = docId,
                    dev = "devA",
                    engine = OcrEngineId.AI.column,
                    lang = "eng",
                    createdAt = 1,
                    pages = listOf("model words"),
                )
            OcrSidecarFiles(fs).writeOnce(docId, "devA.ai", OcrSidecarCodec.encode(fromA))
            rig.setOverride(docId, OcrEngineId.AI.column)
            pipe.reconcileSidecars()
            pipe.drain()

            ai.calls shouldBe 0
            db.documentDao().getByDocId(docId)?.ocrEngine shouldBe OcrEngineId.AI.column
            db.ftsDao().searchDocIds("model") shouldContainExactly listOf(docId)
        }

    @Test
    fun anAiPinFromAnotherDeviceIsHeldToThePageCapOfTheAutomaticSetting() =
        runTest {
            // With AI as this device's automatic engine, a pinned document is unattended work like
            // any other: under the cap the policy may spend, over it the run stays local.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local words")
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("big.pdf", PdfFixtures.imageOnlyPdf(pages = 3))
            val pipe = rig.pipeline("devB", local, engines = selector(local, ai, auto = true, largeDocumentPages = 2))
            pipe.processDoc(docId)

            rig.setOverride(docId, OcrEngineId.AI.column)
            pipe.reconcileSidecars()
            pipe.drain()

            ai.calls shouldBe 0
            db.documentDao().getByDocId(docId)?.ocrEngine shouldBe "mlkit"
        }

    @Test
    fun unconfiguredAiEngineIsIgnoredEvenWhenAutomaticAndAskedFor() =
        runTest {
            // AI turned on in settings but not usable (no model/key yet). The document must still
            // get text from the local engine rather than sit unprocessed.
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "local fallback words")
            val ai = FakeOcrEngine(isAvailable = false, name = OcrEngineId.AI.column, canned = "should-not-run")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.askForAi(docId)
            val engines = selector(local, ai, auto = true)

            engines.aiAvailable() shouldBe false
            rig.pipeline("devA", local, engines = engines).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 1
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe "mlkit"
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
            val ai = FakeOcrEngine(isAvailable = true, name = OcrEngineId.AI.column)
            val docId = rig.stageDoc("inv.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            rig.askForAi(docId)

            rig.pipeline("devA", local, engines = selector(local, ai, auto = true)).processDoc(docId)

            ai.calls shouldBe 0
            local.calls shouldBe 0
            db.documentDao().requireByDocId(docId).ocrEngine shouldBe OcrEngineId.EMBEDDED.column
        }
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

/**
 * What `OcrFacade.ocrWithAi` leaves behind on this device: the pin, as the journal fold projects
 * it onto the row, a queue row marking the run as asked for here under a stamp of its own (keeping
 * whatever else was asked before), and the document pending.
 */
private suspend fun OcrPipelineRig.askForAi(docId: String) {
    setOverride(docId, OcrEngineId.AI.column)
    val queue = deviceDb.ocrQueueDao()
    val existing = queue.getByDocId(docId)
    queue.upsert(
        OcrQueueEntity(
            docId,
            QueueState.PENDING.column,
            updatedAt = (existing?.updatedAt ?: 0) + 1,
            rerun = existing?.rerun == true,
            aiRequested = true,
        ),
    )
    db.documentDao().updateOcrState(docId, OcrState.PENDING.column, null, null)
}

/** [engine], with the user asking for AI on [docId] ([askForAi]) as it reads its first page. */
private fun OcrPipelineRig.askingForAiOnItsFirstPage(
    engine: FakeOcrEngine,
    docId: String,
): OcrEngine =
    object : OcrEngine by engine {
        override suspend fun recognizePng(
            png: ByteArray,
            lang: String,
        ): String {
            if (engine.calls == 0) askForAi(docId)
            return engine.recognizePng(png, lang)
        }
    }

/** Work the queue the way the session's drainer does. */
private suspend fun OcrPipeline.drain() {
    while (processNext()) {
        // Each call processes one document.
    }
}

/** Simulate the journal fold projecting a SystemProperty.OCR_ENGINE override onto the row. */
private suspend fun OcrPipelineRig.setOverride(
    docId: String,
    engine: String?,
) {
    val doc = db.documentDao().getByDocId(docId).shouldNotBeNull()
    db.documentDao().upsert(doc.copy(ocrEngineOverride = engine))
}
