package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.queueState
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.requireByDocId
import dev.npn.core.ocr.OcrState
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test

/**
 * What [OcrFacadeImpl] offers before an AI run, and how a rescan settles the OCR state of a
 * document left without any against this device's queue.
 *
 * Documents come in by import, which queues them without draining: the session's drains wait for
 * its first rescan.
 */
class OcrFacadeTest {
    private val endpoint =
        AiConfig(enabled = true, provider = AiProvider.CUSTOM, baseUrl = "http://127.0.0.1:9", model = "m")

    /**
     * Once set, a read of [HELD_PATH] waits for good: OCR's drain cannot settle that document's run
     * under the test. Closing the session cancels the wait.
     */
    @Volatile private var holding = false

    private fun holding(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun readBytes(relPath: String): ByteArray {
                if (holding && relPath == HELD_PATH) awaitCancellation()
                return fs.readBytes(relPath)
            }
        }

    private suspend fun AppSessionRig.import(
        bytes: ByteArray,
        name: String = "a.pdf",
    ): String =
        session.ingest
            .importPdfBytes(bytes, name)
            .shouldBeInstanceOf<ImportOutcome.Imported>()
            .docId

    @Test
    fun noEndpointMeansNoAiOffer() =
        appSessionTest { rig ->
            val docId = rig.import(PdfFixtures.imageOnlyPdf())

            rig.session.ocr.aiOcrOffer(docId) shouldBe AiOcrOffer.Unavailable
        }

    @Test
    fun aShortDocumentIsOfferedPlainly() =
        appSessionTest(aiConfig = { endpoint }) { rig ->
            val docId = rig.import(PdfFixtures.imageOnlyPdf(pages = 2))

            rig.session.ocr.aiOcrOffer(docId) shouldBe AiOcrOffer.Ready
        }

    /** The page count is read off the PDF itself when the row has none yet, and kept there. */
    @Test
    fun aLongDocumentIsOfferedWithItsPageCountReadFromThePdf() =
        appSessionTest(aiConfig = { endpoint }) { rig ->
            val docId = rig.import(PdfFixtures.imageOnlyPdf(pages = LONG_PAGES))
            rig.db
                .documentDao()
                .requireByDocId(docId)
                .pageCount
                .shouldBeNull()

            rig.session.ocr.aiOcrOffer(docId) shouldBe AiOcrOffer.Large(LONG_PAGES)

            rig.db
                .documentDao()
                .requireByDocId(docId)
                .pageCount shouldBe LONG_PAGES
        }

    @Test
    fun aRunThisDeviceGaveUpOnMarksTheDocumentFailed() =
        appSessionTest(files = ::holding) { rig ->
            val docId = rig.withoutOcr(queued = QueueState.FAILED)

            rig.session.rescan()

            rig.ocrState(docId) shouldBe OcrState.FAILED
            rig.queued(docId).queueState shouldBe QueueState.FAILED
        }

    @Test
    fun aRunStillOwedMarksTheDocumentPending() =
        appSessionTest(files = ::holding) { rig ->
            val docId = rig.withoutOcr(queued = QueueState.PENDING)

            rig.session.rescan()

            rig.ocrState(docId) shouldBe OcrState.PENDING
            // Left as it was: the run keeps its stamp, attempts and requests.
            rig.queued(docId).updatedAt shouldBe QUEUED_AT
        }

    @Test
    fun aSettledRunIsQueuedAgain() =
        appSessionTest(files = ::holding) { rig ->
            val docId = rig.withoutOcr(queued = QueueState.DONE)

            rig.session.rescan()

            rig.ocrState(docId) shouldBe OcrState.PENDING
            rig.queued(docId).queueState shouldBe QueueState.PENDING
            rig.queued(docId).updatedAt shouldNotBe QUEUED_AT
        }

    @Test
    fun aDocumentThisDeviceNeverQueuedIsQueued() =
        appSessionTest(files = ::holding) { rig ->
            val docId = rig.withoutOcr(queued = null)

            rig.session.rescan()

            rig.ocrState(docId) shouldBe OcrState.PENDING
            rig.queued(docId).queueState shouldBe QueueState.PENDING
        }

    /**
     * An imported document whose row says it has no OCR, with this device's queue holding a run in
     * [queued] for it (none when null).
     */
    private suspend fun AppSessionRig.withoutOcr(queued: QueueState?): String {
        val docId = import(PdfFixtures.imageOnlyPdf(), HELD_PATH)
        db.documentDao().updateOcrState(docId, OcrState.NONE.column, null, null)
        val queue = deviceDb.ocrQueueDao()
        queue.delete(docId)
        if (queued != null) queue.upsert(OcrQueueEntity(docId, queued.column, updatedAt = QUEUED_AT))
        holding = true
        return docId
    }

    private suspend fun AppSessionRig.ocrState(docId: String): OcrState =
        OcrState.fromColumn(db.documentDao().requireByDocId(docId).ocrState)

    private suspend fun AppSessionRig.queued(docId: String): OcrQueueEntity =
        deviceDb.ocrQueueDao().getByDocId(docId).shouldNotBeNull()

    private companion object {
        const val HELD_PATH = "held.pdf"
        const val QUEUED_AT = 7L

        /** Past `OcrEngineSelector.DEFAULT_LARGE_DOCUMENT_PAGES`. */
        const val LONG_PAGES = 21
    }
}
