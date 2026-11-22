package dev.npn.core.app

import dev.npn.core.index.requireByDocId
import dev.npn.core.ocr.FakeOcrEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What the content facade makes of a document's bytes, it makes of the document's own bytes only. */
class DocContentFacadeTest {
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
            content.bakedCopyBytes(id) shouldBe null

            rig.db
                .documentDao()
                .requireByDocId(id)
                .pageCount shouldBe null
        }
}
