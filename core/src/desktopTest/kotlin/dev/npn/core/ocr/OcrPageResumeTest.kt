package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.searchDocIds
import dev.npn.core.index.MutableClock
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.work.backoffMs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A vision engine bills per page, so a run that fails on page k of a long scan must not pay for
 * pages 0 until k again when the queue retries it.
 */
class OcrPageResumeTest {
    private val rig = OcrPipelineRig()
    private val db get() = rig.db

    @AfterTest
    fun teardown() {
        rig.close()
    }

    @Test
    fun aRetryReadsOnlyThePagesTheFailedRunDidNotGetTo() =
        runTest {
            val ai = PageCountingEngine(OcrEngineId.AI.column).apply { failOnCall = 3 }
            val local = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "should-not-run")
            val docId = rig.stageDoc("ledger.pdf", PdfFixtures.imageOnlyPdf(pages = 4))
            askForAi(docId)
            val clock = MutableClock()
            val pipe = rig.pipeline("devA", local, engines = OcrEngineSelector(local, ai), clock = clock)

            // Pages 0 and 1 are read; page 2 fails the run.
            pipe.processDoc(docId)
            ai.calls shouldBe 3
            OcrSidecarFiles(rig.fs).listForHash(docId).shouldBeEmpty()

            clock.advance(backoffMs(1))
            pipe.processNext() shouldBe true

            // The retry reads pages 2 and 3 only; 0 and 1 are the first attempt's readings.
            ai.calls shouldBe 5
            local.calls shouldBe 0
            val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(rig.fs).read(docId, "devA.ai"))
            sidecar.pages shouldContainExactly listOf("read 1", "read 2", "read 4", "read 5")
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .state shouldBe QueueState.DONE.column
            // Published, so nothing is kept for a run that is no longer owed.
            rig.deviceDb
                .ocrQueueDao()
                .cachedPages(docId, OcrEngineId.AI.column, "eng")
                .shouldBeEmpty()
        }

    @Test
    fun anotherDevicesVersionArrivingDoesNotRestartAFailedRun() =
        runTest {
            // AI is this device's automatic engine, and its run fails on page 2 of 4. Another
            // device's local reading arrives meanwhile and is adopted for search, but the paid
            // run is still owed here: with its backoff, and the pages it already bought.
            val ai = PageCountingEngine(OcrEngineId.AI.column).apply { failOnCall = 3 }
            val local = FakeOcrEngine(isAvailable = true, name = OcrEngineId.MLKIT.column, canned = "should-not-run")
            val docId = rig.stageDoc("ledger.pdf", PdfFixtures.imageOnlyPdf(pages = 4))
            val clock = MutableClock()
            val engines = OcrEngineSelector(local, ai, aiAuto = { true })
            val pipe = rig.pipeline("devA", local, engines = engines, clock = clock)
            pipe.processDoc(docId)
            ai.calls shouldBe 3

            val fromB =
                OcrSidecar(
                    doc = docId,
                    dev = "devB",
                    engine = OcrEngineId.MLKIT.column,
                    lang = "eng",
                    createdAt = 1,
                    pages = List(4) { "local words" },
                )
            OcrSidecarFiles(rig.fs).writeOnce(docId, "devB.mlkit", OcrSidecarCodec.encode(fromB))
            pipe.reconcileSidecars()
            db.ftsDao().searchDocIds("local") shouldContainExactly listOf(docId)

            // Still waiting out the failure's backoff, then resumed where it stopped.
            pipe.processNext() shouldBe false
            clock.advance(backoffMs(1))
            pipe.processNext() shouldBe true

            ai.calls shouldBe 5
            local.calls shouldBe 0
            val sidecar = OcrSidecarCodec.decode(OcrSidecarFiles(rig.fs).read(docId, "devA.ai"))
            sidecar.pages shouldContainExactly listOf("read 1", "read 2", "read 4", "read 5")
        }

    @Test
    fun pagesReadByAnotherEngineOrLanguageAreNotReused() =
        runTest {
            val first = PageCountingEngine("mlkit").apply { failOnCall = 2 }
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            rig.pipeline("devA", first).processDoc(docId)
            rig.deviceDb
                .ocrQueueDao()
                .cachedPages(docId, "mlkit", "eng")
                .map { it.page } shouldContainExactly listOf(0)

            // The retry reads in other languages: the cached page says nothing about those.
            val second = PageCountingEngine("mlkit")
            rig.pipeline("devA", second, language = { "nld" }).processDoc(docId)

            second.calls shouldBe 2
        }

    @Test
    fun aDocumentLeavingTheQueueTakesItsPagesWithIt() =
        runTest {
            val engine = PageCountingEngine("mlkit").apply { failOnCall = 2 }
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            rig.pipeline("devA", engine).processDoc(docId)
            rig.deviceDb
                .ocrQueueDao()
                .cachedPages(docId, "mlkit", "eng")
                .shouldNotBeNull()
                .size shouldBe 1

            rig.deviceDb.ocrQueueDao().delete(docId)

            rig.deviceDb
                .ocrQueueDao()
                .cachedPages(docId, "mlkit", "eng")
                .shouldBeEmpty()
        }

    /** What `OcrFacade.ocrWithAi` leaves behind on this device: the folded pin and the request. */
    private suspend fun askForAi(docId: String) {
        val doc = db.documentDao().getByDocId(docId).shouldNotBeNull()
        db.documentDao().upsert(doc.copy(ocrEngineOverride = OcrEngineId.AI.column))
        rig.deviceDb
            .ocrQueueDao()
            .upsert(OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = 2, aiRequested = true))
    }
}

/**
 * Answers each page with the number of the call that read it, so a test can tell which run read
 * which page; the [failOnCall]th call throws, the way an endpoint answers a 429.
 */
private class PageCountingEngine(
    override val name: String,
) : OcrEngine {
    var calls = 0
        private set

    var failOnCall: Int? = null

    override suspend fun available(): Boolean = true

    override suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String {
        calls++
        if (calls == failOnCall) throw IOException("429 Too Many Requests")
        return "read $calls"
    }
}
