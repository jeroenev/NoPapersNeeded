package dev.npn.core.app

import dev.npn.core.model.DocProperty
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * [MetadataWriter.applyEdits]: several values, and several documents, written as one change.
 * The single-value setters are covered by [MetadataWriterTest].
 */
class MetadataWriterBatchTest {
    /**
     * One call, one journal transaction, one move. Writing the same values setter-by-setter
     * publishes an object and re-runs the storage path rules per value, so a synced library folder
     * sees a rename for each half-finished name.
     */
    @Test
    fun applyEditsCommitsOnceAndMovesOnce() =
        runTest {
            val rule = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePathRule = { rule })
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                val objectsBefore = rig.journalObjectCount()

                rig.session.meta.applyEdits(
                    docIds = listOf(id),
                    // Blank and duplicate tags are dropped rather than committed.
                    addTags = listOf("invoice", "invoice", "  "),
                    properties =
                        mapOf(
                            DocProperty.TITLE to "Annual report",
                            DocProperty.CORRESPONDENT to "Acme",
                            DocProperty.DOC_DATE to "2026-01-31",
                        ),
                )

                rig.journalObjectCount() shouldBe objectsBefore + 1
                val doc = rig.db.documentDao().getByDocId(id)!!
                doc.title shouldBe "Annual report"
                doc.correspondent shouldBe "Acme"
                doc.docDate shouldBe "2026-01-31"
                doc.relPath shouldBe "Acme/Annual report.pdf"
                rig.exists(doc.relPath) shouldBe true

                val items =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(items, id) shouldContainExactlyInAnyOrder listOf("invoice")
            } finally {
                rig.close()
            }
        }

    /**
     * Many documents, one transaction — what carrying metadata onto a split's parts needs. The
     * per-document follow-ups still run per document, so each one lands at its own rule path.
     */
    @Test
    fun applyEditsWritesManyDocumentsInOneTransaction() =
        runTest {
            val rule = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePathRule = { rule })
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("beta"))
                rig.session.rescan()
                val a = rig.docId("a.pdf")
                val b = rig.docId("b.pdf")
                val objectsBefore = rig.journalObjectCount()

                rig.session.meta.applyEdits(
                    docIds = listOf(a, b),
                    addTags = listOf("scan"),
                    properties = mapOf(DocProperty.CORRESPONDENT to "Acme"),
                )

                // One object for both documents, not one each.
                rig.journalObjectCount() shouldBe objectsBefore + 1

                val docA = rig.db.documentDao().getByDocId(a)!!
                val docB = rig.db.documentDao().getByDocId(b)!!
                docA.correspondent shouldBe "Acme"
                docB.correspondent shouldBe "Acme"
                // Titles are untouched, so each keeps its own rule path under the shared folder.
                docA.relPath shouldBe "Acme/a.pdf"
                docB.relPath shouldBe "Acme/b.pdf"
                rig.exists(docA.relPath) shouldBe true
                rig.exists(docB.relPath) shouldBe true

                val items =
                    rig.session.documents
                        .observeDocuments(LibraryFilter(), LibrarySort())
                        .first()
                tagsOf(items, a) shouldContainExactlyInAnyOrder listOf("scan")
                tagsOf(items, b) shouldContainExactlyInAnyOrder listOf("scan")
            } finally {
                rig.close()
            }
        }

    /** Absent keys are untouched; a present key with a blank value clears that property. */
    @Test
    fun applyEditsClearsOnlyThePropertiesItCarries() =
        runTest {
            val rig = AppSessionRig.create(backgroundScope)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                rig.session.meta.applyEdits(
                    listOf(id),
                    properties = mapOf(DocProperty.TITLE to "Keep me", DocProperty.CORRESPONDENT to "Acme"),
                )

                rig.session.meta.applyEdits(listOf(id), properties = mapOf(DocProperty.CORRESPONDENT to null))

                val doc = rig.db.documentDao().getByDocId(id)!!
                doc.title shouldBe "Keep me"
                doc.correspondent shouldBe null
            } finally {
                rig.close()
            }
        }

    private fun tagsOf(
        items: List<DocListItem>,
        docId: String,
    ): List<String> = items.first { it.doc.docId == docId }.tags
}
