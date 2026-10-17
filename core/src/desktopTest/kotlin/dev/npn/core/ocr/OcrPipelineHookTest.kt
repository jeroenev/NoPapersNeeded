package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.work.QueueDrainer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The `onTextIndexed` hook, which is how OCR hands a freshly indexed document to its follow-up
 * work (auto-match rules, then queueing automatic AI enrichment).
 */
class OcrPipelineHookTest {
    private val rig = OcrPipelineRig()

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
            var stateDuringHook: String? = null
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    onTextIndexed = { id, _ ->
                        stateDuringHook =
                            rig.deviceDb
                                .ocrQueueDao()
                                .getByDocId(id)
                                ?.state
                    },
                )

            pipeline.processNext()

            // Still owed while the hook runs — the queue has no "being worked on" state, only the
            // in-process [OcrPipeline.inFlight] does.
            stateDuringHook shouldBe QueueState.PENDING.column
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                ?.state shouldBe QueueState.DONE.column
        }

    /** New text on this device is what the paid follow-up is for, so both hooks fire. */
    @Test
    fun ownOcrFiresBothTheIndexedAndTheProducedHook() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val indexed = mutableListOf<String>()
            val produced = mutableListOf<String>()
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    onTextIndexed = { id, _ -> indexed += id },
                    onOcrProduced = { id -> produced += id },
                )

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
            rig
                .pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"), clock = { 1_000 })
                .processDoc(docId)

            val rebuilt = rig.openSecondIndex()
            try {
                rebuilt.documentDao().upsert(
                    rig.db
                        .documentDao()
                        .requireByDocId(docId)
                        .copy(ocrState = OcrState.NONE.column),
                )
                // The queue is this device's and outlives the index; re-queued as a rebuild's rescan
                // would, after the sidecar was written.
                rig.deviceDb.ocrQueueDao().upsert(OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = 2_000))
                val indexed = mutableListOf<String>()
                val produced = mutableListOf<String>()
                val pipeline =
                    rig.pipeline(
                        "devA",
                        FakeOcrEngine(isAvailable = true, name = "mlkit"),
                        rebuilt,
                        onTextIndexed = { id, _ -> indexed += id },
                        onOcrProduced = { id -> produced += id },
                    )

                pipeline.processDoc(docId)

                indexed shouldContainExactly listOf(docId)
                produced.shouldBeEmpty()
            } finally {
                rebuilt.close()
            }
        }

    /**
     * A pass cut short after it published its sidecar (the session closed while the rules ran)
     * leaves its run owed. The pass that settles it finds the text this device wrote for that run
     * and hands it on as produced, once: nothing else would ever offer it to enrichment.
     */
    @Test
    fun aPassCutShortAfterPublishingHandsItsTextOnWhenItsRunSettles() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            var closing = true
            val produced = mutableListOf<String>()
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    onTextIndexed = { _, _ ->
                        if (closing) {
                            closing = false
                            throw CancellationException("session closed")
                        }
                    },
                    onOcrProduced = { id -> produced += id },
                )

            shouldThrow<CancellationException> { pipeline.processDoc(docId) }
            produced.shouldBeEmpty()
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                ?.state shouldBe QueueState.PENDING.column

            pipeline.processNext() shouldBe true
            pipeline.processNext() shouldBe false
            produced shouldContainExactly listOf(docId)
        }

    /**
     * The same cut-short pass, then a rebuilt index: the run is this device's and outlives the
     * index, so the reconcile that adopts the sidecar leaves it owed rather than dropping it, and the
     * drain hands the text on. A run that settled before the rebuild is dropped as before.
     */
    @Test
    fun aRebuiltIndexKeepsTheRunOfAPassCutShortAfterPublishing() =
        runTest {
            val cutShort = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val settled = rig.stageDoc("other.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            val engine = FakeOcrEngine(isAvailable = true, name = "mlkit")
            rig.pipeline("devA", engine).processDoc(settled)
            val closing =
                rig.pipeline(
                    "devA",
                    engine,
                    onTextIndexed = { _, _ -> throw CancellationException("session closed") },
                )
            shouldThrow<CancellationException> { closing.processDoc(cutShort) }

            val rebuilt = rig.openSecondIndex()
            try {
                for (docId in listOf(cutShort, settled)) {
                    rebuilt.documentDao().upsert(
                        rig.db
                            .documentDao()
                            .requireByDocId(docId)
                            .copy(ocrState = OcrState.NONE.column),
                    )
                }
                val produced = mutableListOf<String>()
                val pipeline = rig.pipeline("devA", engine, rebuilt, onOcrProduced = { id -> produced += id })

                pipeline.reconcileSidecars()
                while (pipeline.processNext()) {
                    // Each call settles one owed run.
                }

                produced shouldContainExactly listOf(cutShort)
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(settled) shouldBe null
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
            val produced = mutableListOf<String>()
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    onTextIndexed = { _, _ -> error("hook blew up") },
                    onOcrProduced = { id -> produced += id },
                )

            val worker = QueueDrainer(this) { pipeline.processNext() }
            try {
                worker.drain() shouldBe 2
                val rows =
                    rig.deviceDb
                        .ocrQueueDao()
                        .observeQueue()
                        .first()
                rows.all { it.state == QueueState.DONE.column } shouldBe true
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
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"))

            val worker = QueueDrainer(this) { pipeline.processNext() }
            try {
                worker.drain() shouldBe 1
                rig.deviceDb
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
