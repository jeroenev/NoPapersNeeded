package dev.npn.core.ocr

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.requireByDocId
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A drain looks up every document's stored versions several times. Listing `.npn/ocr/` for each
 * lookup made a full drain cost documents × sidecars directory entries; a drain lists it once, and
 * the next drain lists it again to see what arrived.
 */
class OcrDrainListingTest {
    private val rig = OcrPipelineRig()

    @AfterTest
    fun tearDown() = rig.close()

    private class ListingCounter(
        private val inner: LibraryFileSystem,
    ) : LibraryFileSystem by inner {
        var ocrListings = 0

        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
            if (relPath == NpnPaths.OCR_DIR) ocrListings++
            return inner.listAllUnder(relPath)
        }
    }

    @Test
    fun aDrainListsTheSidecarsOnceAndTheNextSeesWhatArrived() =
        runTest {
            val docIds = (1..DOCS).map { rig.stageDoc("docs/doc$it.pdf", PdfFixtures.embeddedTextPdf("document $it")) }
            val counter = ListingCounter(rig.fs)
            val pipeline = rig.pipeline("devA", FakeOcrEngine.unavailable(), files = counter)

            drain(pipeline)

            counter.ocrListings shouldBe 1
            for (docId in docIds) {
                rig.db
                    .documentDao()
                    .requireByDocId(docId)
                    .ocrSource shouldBe "devA.embedded"
            }

            // Another device's sidecar, synced in with no reconcile announcing it, is adopted by the
            // next drain rather than produced here again.
            val late = rig.stageDoc("docs/late.pdf", PdfFixtures.embeddedTextPdf("arrived later"))
            rig.writeForeignSidecar(late, dev = "devB", engine = "mlkit", text = "devB's words")

            drain(pipeline)

            counter.ocrListings shouldBe 2
            rig.db
                .documentDao()
                .requireByDocId(late)
                .ocrSource shouldBe "devB"
        }

    private suspend fun drain(pipeline: OcrPipeline) {
        while (pipeline.processNext()) {
            // processNext does the work; the loop only runs it until the queue is empty.
        }
    }

    private companion object {
        const val DOCS = 10
    }
}
