package dev.npn.core.app

import dev.npn.core.index.requireByDocId
import dev.npn.core.model.FieldKeys
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
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
     * One metadata transaction plus a path intent, one move. Writing the same values setter-by-setter
     * publishes an object and re-runs the storage path rules per value, so a synced library folder
     * sees a rename for each half-finished name.
     */
    @Test
    fun applyEditsCommitsMetadataAndPathIntentAndMovesOnce() =
        appSessionTest(storagePath = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)) { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val objectsBefore = rig.journalObjectCount()

            rig.session.meta.applyEdits(
                docIds = listOf(id),
                // Blank and duplicate tags are dropped rather than committed.
                addTags = listOf("invoice", "invoice", "  "),
                fields =
                    mapOf(
                        FieldKeys.TITLE to "Annual report",
                        FieldKeys.CORRESPONDENT to "Acme",
                        FieldKeys.DOC_DATE to "2026-01-31",
                    ),
            )

            rig.journalObjectCount() shouldBe objectsBefore + 2
            val doc = rig.db.documentDao().requireByDocId(id)
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
        }

    /**
     * Many documents, one transaction — what carrying metadata onto a split's parts needs. The
     * per-document follow-ups still run per document, so each one lands at its own rule path.
     */
    @Test
    fun applyEditsWritesManyDocumentsInOneTransaction() =
        runTest {
            val rule = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)
            AppSessionRig.create(backgroundScope, storagePath = rule).use { rig ->
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("beta"))
                rig.session.rescan()
                val a = rig.docId("a.pdf")
                val b = rig.docId("b.pdf")
                val objectsBefore = rig.journalObjectCount()

                rig.session.meta.applyEdits(
                    docIds = listOf(a, b),
                    addTags = listOf("scan"),
                    fields = mapOf(FieldKeys.CORRESPONDENT to "Acme"),
                )

                // One metadata object for both documents, followed by their two path intents.
                rig.journalObjectCount() shouldBe objectsBefore + 3

                val docA = rig.db.documentDao().requireByDocId(a)
                val docB = rig.db.documentDao().requireByDocId(b)
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
            }
        }

    /** Absent keys are untouched; a present key with a blank value clears that property. */
    @Test
    fun applyEditsClearsOnlyThePropertiesItCarries() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.meta.applyEdits(
                listOf(id),
                fields = mapOf(FieldKeys.TITLE to "Keep me", FieldKeys.CORRESPONDENT to "Acme"),
            )

            rig.session.meta.applyEdits(listOf(id), fields = mapOf(FieldKeys.CORRESPONDENT to null))

            val doc = rig.db.documentDao().requireByDocId(id)
            doc.title shouldBe "Keep me"
            doc.correspondent shouldBe null
        }

    /**
     * A fill-only edit (unattended enrichment) is settled against the document as it is when it
     * commits: a value set since its caller looked is kept, a tag carried in another case is not
     * added again, and only what is still missing is written.
     */
    @Test
    fun aFillOnlyEditWritesOnlyWhatTheDocumentLacksWhenItCommits() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.meta.setField(id, FieldKeys.TITLE, "Mine")
            rig.session.meta.addTag(listOf(id), "Invoice")

            rig.session.meta.applyEdits(
                listOf(id),
                addTags = listOf("invoice", "energy"),
                fields = mapOf(FieldKeys.TITLE to "Model title", FieldKeys.CORRESPONDENT to "Acme"),
                fillOnly = true,
            )

            val doc =
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
            doc.title shouldBe "Mine"
            doc.correspondent shouldBe "Acme"
            val items =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(items, id) shouldContainExactlyInAnyOrder listOf("Invoice", "energy")
        }

    /** A fill-only edit that finds everything filled writes nothing at all. */
    @Test
    fun aFillOnlyEditOfAFilledDocumentWritesNothing() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.meta.applyEdits(
                listOf(id),
                addTags = listOf("invoice"),
                fields =
                    mapOf(FieldKeys.TITLE to "Mine"),
            )
            val objectsBefore = rig.journalObjectCount()

            rig.session.meta.applyEdits(
                listOf(id),
                addTags = listOf("invoice"),
                fields = mapOf(FieldKeys.TITLE to "Model title"),
                fillOnly = true,
            )

            rig.journalObjectCount() shouldBe objectsBefore
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .title shouldBe "Mine"
        }
}
