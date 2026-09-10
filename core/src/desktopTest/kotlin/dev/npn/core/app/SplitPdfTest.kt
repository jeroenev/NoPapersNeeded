package dev.npn.core.app

import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SplitPdfTest {
    @Test
    fun splitTwoPagePdfIntoTwoDocsTrashesOriginalAndCopiesMetadata() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                // Fixture PDFs have two pages.
                val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("splittable"), "contract.pdf")
                val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
                rig.session.meta.addTag(listOf(imported.docId), "invoice")
                rig.session.meta.setField(listOf(imported.docId), "correspondent", "acme")
                rig.session.meta.setField(imported.docId, "docDate", "2026-01-15")

                val split = rig.session.splitPdf(imported.docId, listOf(0..0, 1..1))
                val done = split.shouldBeInstanceOf<SplitOutcome.Done>()
                done.partDocIds shouldHaveSize 2

                // Parts are live single-page documents next to the original, with metadata carried over.
                for (partId in done.partDocIds) {
                    val part =
                        rig.session.documents
                            .observeDoc(partId)
                            .first()
                    checkNotNull(part)
                    part.tags shouldBe listOf("invoice")
                    part.doc.correspondent shouldBe "acme"
                    part.doc.docDate shouldBe "2026-01-15"
                    rig.session.content.pageCount(partId) shouldBe 1
                }
                rig.session.documents
                    .observeDoc(done.partDocIds[0])
                    .first()!!
                    .doc.fileName shouldBe "contract (part 1).pdf"

                // Original is trashed, not deleted.
                val trashed =
                    rig.session.documents
                        .observeTrashed()
                        .first()
                trashed.map { it.doc.docId } shouldBe listOf(imported.docId)
            } finally {
                rig.close()
            }
        }

    @Test
    fun splitRejectsNonCoveringSegments() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("bad split"), "doc.pdf")
                val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()

                rig.session.splitPdf(imported.docId, listOf(0..0)).shouldBeInstanceOf<SplitOutcome.Failed>()
                rig.session.splitPdf(imported.docId, listOf(0..0, 0..1)).shouldBeInstanceOf<SplitOutcome.Failed>()
                rig.session.splitPdf(imported.docId, listOf(0..0, 1..2)).shouldBeInstanceOf<SplitOutcome.Failed>()

                // Original untouched.
                rig.session.documents
                    .observeTrashed()
                    .first() shouldHaveSize 0
            } finally {
                rig.close()
            }
        }
}
