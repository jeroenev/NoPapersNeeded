package dev.npn.core.app

import dev.npn.core.index.requireByDocId
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
     */
    @Test
    fun aFileWrittenOverTheDocumentIsNotReadAsIt() =
        appSessionTest { rig ->
            rig.writePdf("doc.pdf", PdfFixtures.embeddedTextPdf("the document"))
            rig.session.rescan()
            val id = rig.docId("doc.pdf")
            rig.db
                .documentDao()
                .requireByDocId(id)
                .pageCount
                .shouldBeNull()
            rig.writePdf("doc.pdf", PdfFixtures.embeddedTextPdf("another document"))

            val content = rig.session.content
            content.pageCount(id) shouldBe null
            content.bakedCopyBytes(id) shouldBe null

            rig.db
                .documentDao()
                .requireByDocId(id)
                .pageCount shouldBe null
        }
}
