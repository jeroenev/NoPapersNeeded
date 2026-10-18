package dev.npn.core.app

import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.ocr.OcrState
import dev.npn.core.pdf.PdfDocumentHandle
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ImportQueueRollbackTest {
    @Test
    fun aFailedReturningImportKeepsAnOcrRequestMadeAfterItsOptimisticRevival() =
        runTest {
            val publication = PausedPublication()
            val pdf = PausedPdf()
            val rig =
                AppSessionRig.create(
                    backgroundScope,
                    ocr = FakeOcrEngine(isAvailable = true, name = "tesseract"),
                    files = publication::over,
                    pdfEngine = pdf::over,
                )
            try {
                val bytes = PdfFixtures.embeddedTextPdf("alpha")
                rig.writePdf("doc.pdf", bytes)
                rig.session.rescan()
                rig.session.work.drainOcr()
                val id = rig.docId("doc.pdf")
                rig.session.docLifecycle.trashDoc(id)
                rig.db.documentDao().updateOcrState(id, OcrState.NONE.column, null, null)
                val previous = OcrQueueEntity(id, QueueState.FAILED.column, attempts = 3, updatedAt = 1)
                val queue = rig.deviceDb.ocrQueueDao()
                queue.upsert(previous)
                publication.armed = true
                pdf.armed = true

                val importing = async { rig.session.ingest.importPdfBytes(bytes, "returned.pdf") }
                publication.reached.await()
                // reOcr does not take the journal lock; it can act on the import's optimistic row.
                rig.session.ocr.reOcr(id)
                pdf.reached.await()
                val requested = queue.getByDocId(id)
                requested?.rerun shouldBe true
                publication.release()

                importing.await() shouldBe ImportOutcome.Failed(ImportFailure.COPY_FAILED)

                queue.getByDocId(id) shouldBe requested
                queue.getByDocId(id)?.attempts shouldBe 0
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    ?.trashed shouldBe true
            } finally {
                publication.release()
                pdf.release()
                rig.close()
            }
        }

    private class PausedPublication : FsGate() {
        var armed = false

        fun over(fs: LibraryFileSystem): LibraryFileSystem =
            object : LibraryFileSystem by fs {
                override suspend fun publishVerifiedObjectOnce(
                    stagingPath: String,
                    canonicalPath: String,
                    expectedHash: String,
                ) {
                    if (armed && JournalPaths.classify(canonicalPath) is JournalPath.ObjectV2) {
                        armed = false
                        pause()
                        throw VerifiedPublicationFailedException("restore publication failed")
                    }
                    fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                }
            }
    }

    private class PausedPdf : FsGate() {
        var armed = false

        fun over(pdf: PdfEngine): PdfEngine =
            object : PdfEngine by pdf {
                override suspend fun open(bytes: ByteArray): PdfDocumentHandle {
                    if (armed) pause()
                    return pdf.open(bytes)
                }
            }
    }
}
