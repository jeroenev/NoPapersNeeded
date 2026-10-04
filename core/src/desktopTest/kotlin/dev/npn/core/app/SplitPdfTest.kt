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

                val split = rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1))
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
    fun aPartIsNamedAfterItsOriginalWithoutTheExtensionInAnyCase() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("mixed case"), "Scan.Pdf")
                val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()

                val split = rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1))
                val done = split.shouldBeInstanceOf<SplitOutcome.Done>()

                val part =
                    checkNotNull(
                        rig.session.documents
                            .observeDoc(done.partDocIds[0])
                            .first(),
                    )
                part.doc.fileName shouldBe "Scan (part 1).pdf"
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

                val invalid = SplitOutcome.Failed(SplitFailure.INVALID_PARTS)
                rig.session.maintenance.splitPdf(imported.docId, listOf(0..0)) shouldBe invalid
                rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 0..1)) shouldBe invalid
                rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..2)) shouldBe invalid

                // Original untouched.
                rig.session.documents
                    .observeTrashed()
                    .first() shouldHaveSize 0
            } finally {
                rig.close()
            }
        }

    @Test
    fun aSplitOfADocumentThatIsGoneSaysSo() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("gone"), "doc.pdf")
                val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
                rig.session.docLifecycle.trashDoc(imported.docId)

                rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1)) shouldBe
                    SplitOutcome.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
                rig.session.maintenance.splitPdf("f".repeat(64), listOf(0..0, 1..1)) shouldBe
                    SplitOutcome.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
            } finally {
                rig.close()
            }
        }
}
