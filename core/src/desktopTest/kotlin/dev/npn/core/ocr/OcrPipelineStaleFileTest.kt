package dev.npn.core.ocr

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * OCR text is published under the docId, write-once and synced, so it must only ever come from
 * bytes that hash to that docId.
 */
class OcrPipelineStaleFileTest {
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
    fun fileReplacedSinceIndexingPublishesNothingAndAsksForARescan() =
        runTest {
            val docId = rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("the bytes that were indexed"))
            Files.write(rig.libRoot.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("the bytes there now"))
            val stale = mutableListOf<String>()
            val indexed = mutableListOf<String>()
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    onTextIndexed = { id, _ -> indexed += id },
                    onStaleFile = { stale += it },
                )

            pipeline.processNext()

            OcrSidecarFiles(rig.fs).listForHash(docId).shouldBeEmpty()
            stale shouldContainExactly listOf(docId)
            indexed.shouldBeEmpty()
            // Not charged as a failure, and not left for the drain to spin on.
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldBeNull()
        }

    @Test
    fun missingDocumentIsDroppedFromTheQueueAndUnmarkedPending() =
        runTest {
            val docId = rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("moved away"))
            val dao = rig.db.documentDao()
            dao.upsert(dao.getByDocId(docId).shouldNotBeNull().copy(missing = true, ocrState = OcrState.PENDING.column))
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"))

            pipeline.processNext()

            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldBeNull()
            dao.getByDocId(docId)?.ocrState shouldBe OcrState.NONE.column
        }

    @Test
    fun sidecarUnderTheEmptyHashIsNeverAdopted() =
        runTest {
            // What an older build left behind: a full PDF's text filed under the empty-file hash.
            val pdf = PdfFixtures.embeddedTextPdf("text of the real file")
            val realId = rig.stageDoc("real.pdf", pdf)
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"))
            pipeline.processDoc(realId)
            val sidecars = OcrSidecarFiles(rig.fs)
            val key = sidecars.listForHash(realId).single()
            sidecars.writeOnce(EMPTY, key, sidecars.read(realId, key))
            Files.write(rig.libRoot.resolve("empty.pdf"), ByteArray(0))
            rig.db.documentDao().upsert(
                rig.db
                    .documentDao()
                    .getByDocId(realId)!!
                    .copy(
                        docId = EMPTY,
                        relPath = "empty.pdf",
                        fileName = "empty.pdf",
                        ocrState = OcrState.NONE.column,
                    ),
            )
            rig.deviceDb.ocrQueueDao().upsert(OcrQueueEntity(EMPTY, "pending", updatedAt = 1))

            pipeline.reconcileSidecars()
            pipeline.processDoc(EMPTY)

            rig.db
                .documentDao()
                .getByDocId(EMPTY)
                ?.ocrState shouldBe OcrState.NONE.column
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(EMPTY)
                .shouldBeNull()
        }

    private companion object {
        const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
