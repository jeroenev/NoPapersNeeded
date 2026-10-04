package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What the OCR queue looks like when a pass does not get to finish — a closing app, a cancelled
 * drain, a killed process — and what the next one makes of it.
 *
 * This is the behaviour the queue's shape exists to guarantee, so it is tested apart from the
 * pipeline's ordinary state machine ([OcrPipelineTest]): a row records only that a document is
 * owed a pass, never that one is under way, precisely so nothing can be left behind claiming to
 * be running with no one alive to take the claim back.
 */
class OcrQueueRecoveryTest {
    private lateinit var rig: OcrPipelineRig
    private val db get() = rig.db

    @BeforeTest
    fun setup() {
        rig = OcrPipelineRig()
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    private fun workingEngine() = FakeOcrEngine(isAvailable = true, name = "mlkit")

    @Test
    fun inFlightNamesTheDocumentBeingWorkedAndClearsWhenTheDrainGoesIdle() =
        runTest {
            val hanging = HangingOcrEngine()
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devA", hanging)
            pipe.inFlight.value shouldBe null

            val job = launch { pipe.processNext() }
            hanging.started.await()
            pipe.inFlight.value shouldBe docId

            hanging.release.complete(Unit)
            job.join()
            pipe.inFlight.value shouldBe null
        }

    @Test
    fun anInterruptedPassLeavesItsDocumentOwedAndTheNextDrainTakesIt() =
        runTest {
            val hanging = HangingOcrEngine()
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devA", hanging)

            val job = launch { pipe.processNext() }
            hanging.started.await()
            job.cancelAndJoin()

            val entry = rig.deviceDb.ocrQueueDao().getByDocId(docId)!!
            entry.state shouldBe OcrPipeline.STATE_PENDING
            // Cancellation is not the document's fault, so it costs it nothing: three interrupted
            // app sessions must not spend the attempt cap on a scan that was never even read.
            entry.attempts shouldBe 0
            pipe.inFlight.value shouldBe null

            rig.pipeline("devA", workingEngine()).processNext() shouldBe true

            rig.deviceDb.ocrQueueDao().getByDocId(docId)!!.state shouldBe OcrPipeline.STATE_DONE
            db.documentDao().getByDocId(docId)!!.ocrState shouldBe OcrPipeline.OCR_DONE
        }

    @Test
    fun aRowLeftRunningByAnOlderBuildIsDrainedRatherThanSkippedForever() =
        runTest {
            // The rows the previous design stranded: nothing survived to move them off "running",
            // and a drain matching on "pending" alone walked past them for good. They arrive as
            // ordinary outstanding work now.
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.deviceDb.ocrQueueDao().upsert(OcrQueueEntity(docId, "running", attempts = 1, updatedAt = 1))

            rig.pipeline("devA", workingEngine()).processNext() shouldBe true

            rig.deviceDb.ocrQueueDao().getByDocId(docId)!!.state shouldBe OcrPipeline.STATE_DONE
        }

    @Test
    fun aCancellationRaisedByTheEngineIsNotChargedToTheDocument() =
        runTest {
            // Distinct from a cancelled drain: this scope is alive, so a failure write here would
            // land. A cancellation still says nothing about the document, and recording one would
            // let an engine being torn down spend the attempts that decide "permanently failed".
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipe = rig.pipeline("devA", CancellingOcrEngine())

            assertFailsWith<CancellationException> { pipe.processNext() }

            val entry = rig.deviceDb.ocrQueueDao().getByDocId(docId)!!
            entry.state shouldBe OcrPipeline.STATE_PENDING
            entry.attempts shouldBe 0
            entry.lastError shouldBe null
        }

    @Test
    fun aDocumentGivenUpOnIsNotTakenAgainByTheNextDrain() =
        runTest {
            // The other side of "anything not terminal is owed": `failed` is a decision, and a
            // drain that reclaimed it too would retry a hopeless document on every start.
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.deviceDb.ocrQueueDao().upsert(
                OcrQueueEntity(docId, OcrPipeline.STATE_FAILED, attempts = 3, updatedAt = 1),
            )

            val engine = workingEngine()
            rig.pipeline("devA", engine).processNext() shouldBe false

            engine.calls shouldBe 0
            rig.deviceDb.ocrQueueDao().getByDocId(docId)!!.state shouldBe OcrPipeline.STATE_FAILED
        }
}

/** Raises a cancellation of its own, without the surrounding scope being cancelled. */
private class CancellingOcrEngine : OcrEngine {
    override val name: String = "mlkit"

    override suspend fun available(): Boolean = true

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String = throw CancellationException("engine torn down")
}

/**
 * Stops inside a page and stays there, so a test can act while a pass is genuinely mid-document:
 * [started] fires once it is stuck, [release] lets it finish. Cancelling the caller instead is how
 * a closing app or a torn-down drain reaches the pipeline.
 */
private class HangingOcrEngine : OcrEngine {
    override val name: String = "mlkit"

    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    override suspend fun available(): Boolean = true

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        started.complete(Unit)
        release.await()
        return "canned ocr text"
    }
}
