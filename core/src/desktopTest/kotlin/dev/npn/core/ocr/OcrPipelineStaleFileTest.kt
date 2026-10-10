package dev.npn.core.ocr

import dev.npn.core.app.AppSessionRig
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.queueState
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.index.MutableClock
import dev.npn.core.index.WriteSettle
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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
            // Not charged as a failure, and not taken again before the rescan it asked for.
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .attempts shouldBe 0
            pipeline.processNext() shouldBe false
        }

    /**
     * A re-run asked for while a pass found the file changing is a request that pass never saw: it
     * stays on the row, owed, rather than going with the pass's wait for the rescan.
     */
    @Test
    fun aRequestMadeWhileAPassFoundTheFileChangingSurvivesThatPass() =
        runTest {
            val docId = rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("the bytes that were indexed"))
            Files.write(rig.libRoot.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("the bytes there now"))
            val queue = rig.deviceDb.ocrQueueDao()
            val asking =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun readBytes(relPath: String): ByteArray {
                        if (relPath == "a.pdf") {
                            queue.upsert(OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = 2, rerun = true))
                        }
                        return rig.fs.readBytes(relPath)
                    }
                }

            rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"), files = asking).processDoc(docId)

            val row = queue.getByDocId(docId).shouldNotBeNull()
            row.queueState shouldBe QueueState.PENDING
            row.rerun shouldBe true
        }

    /**
     * A sync tool rewrote the file in place with the same bytes while OCR read it. The run is not
     * dropped with the document left pending: once the follow-up rescan has found the document's
     * bytes there again, OCR reads them.
     */
    @Test
    fun aFileRewrittenInPlaceWhileItWasReadIsReadAgainOnceItSettles() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("rewritten in place")
            val rewrite = MidRewrite("a.pdf", PdfFixtures.embeddedTextPdf("half written"))
            val app = AppSessionRig.create(backgroundScope, files = rewrite::over)
            try {
                app.writePdf("a.pdf", bytes)
                rewrite.armed = true
                app.session.rescan()
                app.session.work.drainOcr()
                rewrite.armed = false
                val id = app.docId("a.pdf")

                app.writePdf("a.pdf", bytes)
                app.session.rescan()
                app.clock.advance(WriteSettle.QUIET_MILLIS)
                app.session.work.drainOcr()

                app.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
                    .ocrState shouldBe OcrState.DONE.column
            } finally {
                app.close()
            }
        }

    /** What was asked of the run stays with it while it waits for the file to settle. */
    @Test
    fun aStaleReadLeavesTheRunOwedWithItsRequestsUntilTheFileHasSettled() =
        runTest {
            val bytes = PdfFixtures.embeddedTextPdf("the bytes that were indexed")
            val docId = rig.stageDoc("a.pdf", bytes)
            val queue = rig.deviceDb.ocrQueueDao()
            queue.upsert(
                OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = 1, rerun = true, aiRequested = true),
            )
            Files.write(rig.libRoot.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("half written"))
            val clock = MutableClock()
            val pipeline = rig.pipeline("devA", FakeOcrEngine(isAvailable = true, name = "mlkit"), clock = clock)

            pipeline.processNext() shouldBe true

            val owed = queue.getByDocId(docId).shouldNotBeNull()
            owed.queueState shouldBe QueueState.PENDING
            owed.attempts shouldBe 0
            owed.rerun shouldBe true
            owed.aiRequested shouldBe true
            owed.nextAttemptAt shouldBe clock.now + WriteSettle.QUIET_MILLIS
            pipeline.processNext() shouldBe false

            Files.write(rig.libRoot.resolve("a.pdf"), bytes)
            clock.advance(WriteSettle.QUIET_MILLIS)
            pipeline.processNext() shouldBe true

            OcrSidecarFiles(rig.fs).listForHash(docId) shouldHaveSize 1
            queue.getByDocId(docId).shouldNotBeNull().queueState shouldBe QueueState.DONE
        }

    /**
     * Bytes that stay another document's (a file no rescan looks at again, or an empty one) are
     * charged from the second read on, so the run is given up on at the cap rather than read again
     * every few seconds for good.
     */
    @Test
    fun aFileThatStaysStaleIsGivenUpOnAtTheAttemptCap() =
        runTest {
            val docId = rig.stageDoc("a.pdf", PdfFixtures.embeddedTextPdf("the bytes that were indexed"))
            Files.write(rig.libRoot.resolve("a.pdf"), PdfFixtures.embeddedTextPdf("another document"))
            val clock = MutableClock()
            val stale = mutableListOf<String>()
            val pipeline =
                rig.pipeline(
                    "devA",
                    FakeOcrEngine(isAvailable = true, name = "mlkit"),
                    clock = clock,
                    onStaleFile = { stale += it },
                )
            val queue = rig.deviceDb.ocrQueueDao()

            pipeline.processNext() shouldBe true
            queue.getByDocId(docId).shouldNotBeNull().attempts shouldBe 0
            repeat(OcrConfig().maxAttempts) {
                clock.advance(DAY_MILLIS)
                pipeline.processNext() shouldBe true
                queue.getByDocId(docId).shouldNotBeNull().attempts shouldBe it + 1
            }

            queue.getByDocId(docId).shouldNotBeNull().queueState shouldBe QueueState.FAILED
            rig.db
                .documentDao()
                .getByDocId(docId)
                .shouldNotBeNull()
                .ocrState shouldBe OcrState.FAILED.column
            stale shouldHaveSize OcrConfig().maxAttempts + 1
            clock.advance(DAY_MILLIS)
            pipeline.processNext() shouldBe false
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

    /**
     * A library filesystem over which, while [armed], a read of [path] gets [bytes]: what a reader
     * sees of a file another app is rewriting in place.
     */
    private class MidRewrite(
        private val path: String,
        private val bytes: ByteArray,
    ) {
        @Volatile var armed = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun readBytes(relPath: String): ByteArray =
                    if (armed && relPath == path) bytes else fs.readBytes(relPath)
            }
    }

    private companion object {
        const val EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
