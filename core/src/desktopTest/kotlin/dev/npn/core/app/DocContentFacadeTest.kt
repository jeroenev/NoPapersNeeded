package dev.npn.core.app

import dev.npn.core.index.requireByDocId
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.test.Test

/** What the content facade makes of a document's bytes, it makes of the document's own bytes only. */
class DocContentFacadeTest {
    /**
     * Printing streams the document's own bytes, as the file is read, and says when the file at its
     * path is another one written over it.
     */
    @Test
    fun aStreamedDocumentIsTheDocumentAndNeverAFileWrittenOverIt() =
        appSessionTest { rig ->
            val pdf = PdfFixtures.imageOnlyPdf(pages = 1)
            rig.writePdf("doc.pdf", pdf)
            rig.session.rescan()
            val id = rig.docId("doc.pdf")

            val streamed = ByteArrayOutputStream()
            rig.session.content.streamDocument(id) { bytes, length -> streamed.write(bytes, 0, length) } shouldBe true
            streamed.toByteArray() shouldBe pdf

            rig.writePdf("doc.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            rig.session.content.streamDocument(id) { _, _ -> } shouldBe false
            rig.session.content.streamDocument("no-such-document") { _, _ -> } shouldBe false
        }

    /**
     * Another file at the document's path, written over it since the last rescan, is not the
     * document: its page count is not recorded as the document's, nor a copy of it baked with the
     * document's metadata.
     *
     * The engine fails on every page, so the text extraction a scan queues leaves no sidecar (which
     * would record the document's own page count), and the number the file at the path would give is
     * never used. Draining that queue first keeps it from landing while this test runs.
     */
    @Test
    fun aFileWrittenOverTheDocumentIsNotReadAsIt() =
        appSessionTest(
            ocr = FakeOcrEngine(isAvailable = true, name = "failing").apply { failure = IllegalStateException("no") },
        ) { rig ->
            rig.writePdf("doc.pdf", PdfFixtures.imageOnlyPdf(pages = 2))
            rig.session.rescan()
            rig.session.work.drainOcr()
            val id = rig.docId("doc.pdf")
            rig.db
                .documentDao()
                .requireByDocId(id)
                .pageCount
                .shouldBeNull()
            rig.writePdf("doc.pdf", PdfFixtures.imageOnlyPdf(pages = 3))

            val content = rig.session.content
            content.pageCount(id) shouldBe null
            content.streamDocument(id) { _, _ -> } shouldBe false
            content.bakedCopyBytes(id) shouldBe null

            rig.db
                .documentDao()
                .requireByDocId(id)
                .pageCount shouldBe null
        }
}
