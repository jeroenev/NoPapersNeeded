package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.documentEntity
import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.index.MutableClock
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** A re-OCR the user asked for: runs when this device's engine is at least as good as the indexed one. */
class OcrPipelineRerunTest {
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
    fun rerunOfTheSameEngineIsPublishedAndIndexed() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "first reading"),
                ).processDoc(docId)

            val second = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "second reading")
            requestRerun(docId)
            rig.pipeline("devA", second).processDoc(docId)

            second.calls shouldBe 1
            val keys = OcrSidecarFiles(rig.fs).listForHash(docId).sorted()
            keys shouldHaveSize 2
            keys[0] shouldBe "devA.mlkit"
            keys[1] shouldMatch Regex("""devA\.r\d+\.mlkit""")
            rig.db.ftsDao().searchDocIds("second") shouldContainExactly listOf(docId)
            rig.db.ftsDao().searchDocIds("first") shouldContainExactly emptyList()
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .rerun shouldBe false
        }

    @Test
    fun rerunThatReadsTheSameTextPublishesNothing() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "same words"),
                ).processDoc(docId)

            val again = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "same words")
            requestRerun(docId)
            rig.pipeline("devA", again).processDoc(docId)

            again.calls shouldBe 1
            OcrSidecarFiles(rig.fs).listForHash(docId) shouldContainExactly listOf("devA.mlkit")
        }

    @Test
    fun rerunWithAWeakerEngineDoesNotRun() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "deskdev",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "phone words"),
                ).processDoc(docId)

            val tesseract = FakeOcrEngine(isAvailable = true, name = "tesseract")
            requestRerun(docId)
            rig.pipeline("devA", tesseract).processDoc(docId)

            tesseract.calls shouldBe 0
            rig.db.ftsDao().searchDocIds("phone") shouldContainExactly listOf(docId)
        }

    /**
     * Under a pin to an engine this device does not run, a re-run's text would never be selected
     * over the pinned version, so the engine is not run for it.
     */
    @Test
    fun rerunUnderAPinToAnotherEngineDoesNotRun() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig.writeForeignSidecar(docId, "devB", "tesseract", "desk words")
            val doc =
                rig.db
                    .documentDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            rig.db.documentDao().upsert(doc.copy(ocrEngineOverride = "tesseract"))

            val mlkit = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "phone words")
            requestRerun(docId)
            rig.pipeline("devA", mlkit).processDoc(docId)

            mlkit.calls shouldBe 0
            OcrSidecarFiles(rig.fs).listForHash(docId) shouldContainExactly listOf("devB")
            rig.db.ftsDao().searchDocIds("desk") shouldContainExactly listOf(docId)
        }

    @Test
    fun aFailedRerunKeepsTheRequestAndItsError() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "first reading"),
                ).processDoc(docId)

            val failing =
                FakeOcrEngine(isAvailable = true, name = "mlkit").apply { failure = IOException("engine crashed") }
            requestRerun(docId)
            val pipe = rig.pipeline("devA", failing)
            pipe.processDoc(docId)

            failing.calls shouldBe 1
            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.rerun shouldBe true
            row.attempts shouldBe 1
            row.lastError shouldBe "ocr failed: engine crashed"
            // The version already stored stays indexed while the re-run is owed.
            rig.db.ftsDao().searchDocIds("first") shouldContainExactly listOf(docId)
            rig.db
                .documentDao()
                .getByDocId(docId)
                ?.ocrState shouldBe OcrState.DONE.column

            // Nor does the next reconcile take the owed re-run for an adoption and drop it.
            pipe.reconcileSidecars()
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                ?.rerun shouldBe true
        }

    /** A re-run stamped while the clock stood behind an earlier one still replaces its text. */
    @Test
    fun aRerunAfterTheClockWentBackIsIndexed() =
        runTest {
            val clock = MutableClock(start = 10_000)
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "first reading"),
                    clock = clock,
                ).processDoc(docId)
            requestRerun(docId)
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "second reading"),
                    clock = clock,
                ).processDoc(docId)

            clock.now = 5_000
            requestRerun(docId)
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "third reading"),
                    clock = clock,
                ).processDoc(docId)

            rig.db.ftsDao().searchDocIds("third") shouldContainExactly listOf(docId)
            rig.db.ftsDao().searchDocIds("second") shouldContainExactly emptyList()
        }

    @Test
    fun anotherDeviceSwitchesToTheNewestRun() =
        runTest {
            val bytes = PdfFixtures.imageOnlyPdf()
            val docId = rig.stageDoc("scan.pdf", bytes)
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "first reading"),
                ).processDoc(docId)

            val dbB = rig.openSecondIndex()
            val queueB = rig.openSecondDevice()
            try {
                dbB.documentDao().upsert(
                    documentEntity(docId, "scan.pdf", size = bytes.size.toLong()),
                )
                val deviceB =
                    rig.pipeline("devB", FakeOcrEngine(isAvailable = false, name = "mlkit"), dbB, queueB.ocrQueueDao())
                deviceB.reconcileSidecars()
                dbB.ftsDao().searchDocIds("first") shouldContainExactly listOf(docId)

                requestRerun(docId)
                rig
                    .pipeline(
                        "devA",
                        FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "second reading"),
                    ).processDoc(docId)
                deviceB.reconcileSidecars()

                dbB.ftsDao().searchDocIds("second") shouldContainExactly listOf(docId)
            } finally {
                dbB.close()
                queueB.close()
            }
        }

    /**
     * A re-run asked for while a pass reads the document is a request that pass never saw:
     * finishing, the pass leaves the row owed with it, and the next pass runs the engine again.
     */
    @Test
    fun aRerunRequestedWhileAPassRunsSurvivesThatPass() =
        runTest {
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "scanned words")
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            val asking =
                object : OcrEngine by fake {
                    override suspend fun recognizePng(
                        png: ByteArray,
                        lang: String,
                    ): String {
                        if (fake.calls == 0) requestRerun(docId)
                        return fake.recognizePng(png, lang)
                    }
                }
            val pipe = rig.pipeline("devA", asking)

            pipe.processDoc(docId)

            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.rerun shouldBe true
            pipe.processDoc(docId)
            fake.calls shouldBe 2
            val settled =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            settled.state shouldBe QueueState.DONE.column
            settled.rerun shouldBe false
        }

    /**
     * A re-run asked for again while the re-run asked before is being read (the OCR language
     * changed meanwhile) is a request of its own, though the row already carried the flag: the
     * pass leaves it owed, and the next pass reads the document again, in the new language.
     */
    @Test
    fun aRerunAskedAgainWhileARerunPassRunsSurvivesThatPass() =
        runTest {
            val docId = rig.stageDoc("scan.pdf", PdfFixtures.imageOnlyPdf())
            rig
                .pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "first reading"),
                ).processDoc(docId)
            requestRerun(docId)
            var language = "eng"
            val fake = FakeOcrEngine(isAvailable = true, name = "mlkit", canned = "second reading")
            val asking =
                object : OcrEngine by fake {
                    override suspend fun recognizePng(
                        png: ByteArray,
                        lang: String,
                    ): String {
                        if (fake.calls == 0) {
                            language = "nld"
                            requestRerun(docId)
                        }
                        return fake.recognizePng(png, lang)
                    }
                }
            val pipe = rig.pipeline("devA", asking, language = { language })

            pipe.processDoc(docId)

            val row =
                rig.deviceDb
                    .ocrQueueDao()
                    .getByDocId(docId)
                    .shouldNotBeNull()
            row.state shouldBe QueueState.PENDING.column
            row.rerun shouldBe true
            pipe.processDoc(docId)
            fake.calls shouldBe 2
            fake.lastLang shouldBe "nld"
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .state shouldBe QueueState.DONE.column
        }

    /**
     * What `OcrFacadeImpl.reOcr` leaves on the queue: the row owed with a re-run asked for, under a
     * stamp of its own, and whatever else was asked before kept.
     */
    private suspend fun requestRerun(docId: String) {
        val queue = rig.deviceDb.ocrQueueDao()
        val existing = queue.getByDocId(docId)
        queue.upsert(
            OcrQueueEntity(
                docId,
                QueueState.PENDING.column,
                updatedAt = (existing?.updatedAt ?: 0) + 1,
                rerun = true,
                aiRequested = existing?.aiRequested == true,
            ),
        )
    }
}
