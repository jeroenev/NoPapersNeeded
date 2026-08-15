package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.work.QueueDrainer
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The `onTextIndexed` hook, which is how OCR hands a freshly indexed document to its follow-up
 * work (auto-match rules, then queueing automatic AI enrichment).
 */
class OcrPipelineHookTest {
    private lateinit var rig: OcrPipelineRig

    @BeforeTest
    fun setup() {
        rig = OcrPipelineRig()
    }

    @AfterTest
    fun teardown() {
        rig.close()
    }

    /**
     * Anything watching the queue empty out to decide "background work is finished" must not be
     * able to observe a document as done before the work its hook schedules has been recorded.
     */
    @Test
    fun textIndexedHookRunsBeforeTheQueueEntryIsMarkedDone() =
        runTest {
            val docId = rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            val pipeline = rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit"))
            var stateDuringHook: String? = null
            pipeline.onTextIndexed = { id, _ ->
                stateDuringHook =
                    rig.db
                        .ocrQueueDao()
                        .getByDocId(id)
                        ?.state
            }

            pipeline.processNext()

            stateDuringHook shouldBe OcrPipeline.STATE_RUNNING
            rig.db
                .ocrQueueDao()
                .getByDocId(docId)
                ?.state shouldBe OcrPipeline.STATE_DONE
        }

    /** New text on this device is what the paid follow-up is for, so both hooks fire. */
    @Test
    fun ownOcrFiresBothTheIndexedAndTheProducedHook() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val pipeline = rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit"))
            val indexed = mutableListOf<String>()
            val produced = mutableListOf<String>()
            pipeline.onTextIndexed = { id, _ -> indexed += id }
            pipeline.onOcrProduced = { id -> produced += id }

            pipeline.processDoc(docId)

            indexed shouldContainExactly listOf(docId)
            produced shouldContainExactly listOf(docId)
        }

    /**
     * The index is a disposable cache, so this is what a rebuild looks like: a fresh index over a
     * library whose sidecars already exist, with every document re-queued for OCR. Those runs
     * adopt stored text rather than producing any, and must not re-spend on the whole library.
     */
    @Test
    fun adoptingAnExistingSidecarFiresOnlyTheIndexedHook() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit")).processDoc(docId)

            val rebuilt = rig.openSecondIndex()
            try {
                rebuilt.documentDao().upsert(
                    rig.db
                        .documentDao()
                        .getByDocId(docId)!!
                        .copy(ocrState = OcrPipeline.OCR_NONE),
                )
                rebuilt.ocrQueueDao().upsert(OcrQueueEntity(docId, OcrPipeline.STATE_PENDING, updatedAt = 1))
                val pipeline =
                    rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit"), rebuilt)
                val indexed = mutableListOf<String>()
                val produced = mutableListOf<String>()
                pipeline.onTextIndexed = { id, _ -> indexed += id }
                pipeline.onOcrProduced = { id -> produced += id }

                pipeline.processDoc(docId)

                indexed shouldContainExactly listOf(docId)
                produced.shouldBeEmpty()
            } finally {
                rebuilt.close()
            }
        }

    /** A broken rule or a failing enrichment enqueue must not strand the entry or stop the drain. */
    @Test
    fun aThrowingTextIndexedHookStillCompletesTheQueueEntry() =
        runTest {
            rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            rig.stageDoc("b.pdf", PdfFixtures.embeddedTextPdf("another invoice document here"))
            val pipeline = rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit"))
            pipeline.onTextIndexed = { _, _ -> error("hook blew up") }
            val produced = mutableListOf<String>()
            pipeline.onOcrProduced = { id -> produced += id }

            val worker = QueueDrainer(this) { pipeline.processNext() }
            try {
                worker.drain() shouldBe 2
                val rows =
                    rig.db
                        .ocrQueueDao()
                        .observeQueue()
                        .first()
                rows.all { it.state == OcrPipeline.STATE_DONE } shouldBe true
                // The other hook still ran: containment is per hook, not per document.
                produced.size shouldBe 2
                // Nothing retries a hook, so the failure has to survive somewhere.
                rows.forEach { it.lastError shouldBe "onTextIndexed: hook blew up" }
            } finally {
                worker.close()
            }
        }

    /** A successful pass must not leave a stale diagnostic behind on the row. */
    @Test
    fun aSucceedingHookLeavesNoErrorOnTheQueueEntry() =
        runTest {
            rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("invoice 42 acme corporation"))
            val pipeline = rig.pipeline("devA", FakeOcrEngine(available = true, name = "mlkit"))
            pipeline.onTextIndexed = { _, _ -> }

            val worker = QueueDrainer(this) { pipeline.processNext() }
            try {
                worker.drain() shouldBe 1
                rig.db
                    .ocrQueueDao()
                    .observeQueue()
                    .first()
                    .single()
                    .lastError shouldBe null
            } finally {
                worker.close()
            }
        }
}
