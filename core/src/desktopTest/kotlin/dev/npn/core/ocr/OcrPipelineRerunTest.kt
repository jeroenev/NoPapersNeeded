package dev.npn.core.ocr

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.OcrSidecarFiles
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
                    DocumentEntity(
                        docId = docId,
                        relPath = "scan.pdf",
                        fileName = "scan.pdf",
                        size = bytes.size.toLong(),
                        mtime = 1000,
                        addedAt = 1000,
                    ),
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

    private suspend fun requestRerun(docId: String) {
        rig.deviceDb.ocrQueueDao().upsert(OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = 2, rerun = true))
    }
}
