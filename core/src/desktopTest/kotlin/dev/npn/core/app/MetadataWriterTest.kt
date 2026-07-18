package dev.npn.core.app

import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class MetadataWriterTest {

    private suspend fun AppSessionRig.docId(relPath: String): String = fs.sha256(relPath)

    private fun tagsOf(items: List<DocListItem>, docId: String): List<String> =
        items.first { it.doc.docId == docId }.tags

    @Test
    fun addTagThenRemoveTag() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.addTag(listOf(id), "invoice")
            var docs = rig.session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(docs, id) shouldContain "invoice"

            rig.session.meta.removeTag(listOf(id), "invoice")
            docs = rig.session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(docs, id) shouldNotContain "invoice"
        } finally {
            rig.close()
        }
    }

    @Test
    fun setReviewedReflected() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setReviewed(listOf(id), true)
            rig.db.documentDao().getByDocId(id)!!.reviewed shouldBe true
        } finally {
            rig.close()
        }
    }

    @Test
    fun deleteTagRemovesFromAllDocs() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.session.rescan()
            val a = rig.docId("a.pdf")
            val b = rig.docId("b.pdf")

            rig.session.meta.addTag(listOf(a, b), "shared")
            var docs = rig.session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(docs, a) shouldContain "shared"
            tagsOf(docs, b) shouldContain "shared"

            rig.session.meta.deleteTag("shared")
            docs = rig.session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(docs, a) shouldNotContain "shared"
            tagsOf(docs, b) shouldNotContain "shared"
        } finally {
            rig.close()
        }
    }

    @Test
    fun setCorrespondentAndDocType() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setCorrespondent(listOf(id), "ACME")
            rig.session.meta.setDocType(listOf(id), "Invoice")
            val doc = rig.db.documentDao().getByDocId(id)!!
            doc.correspondent shouldBe "ACME"
            doc.docType shouldBe "Invoice"

            rig.session.documents.observeCorrespondents().first() shouldContain "ACME"
            rig.session.documents.observeDocTypes().first() shouldContain "Invoice"
        } finally {
            rig.close()
        }
    }

    @Test
    fun setFieldProjectsToDbAndObserveDocAndKeys() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setField(id, "amount", "42.50")
            rig.session.meta.setField(id, "vendor", "ACME")

            // Projected into document_fields and surfaced via observeDoc.
            val item = rig.session.documents.observeDoc(id).first()!!
            item.fields shouldBe mapOf("amount" to "42.50", "vendor" to "ACME")

            // Distinct keys available for autocomplete.
            rig.session.documents.observeFieldKeys().first() shouldContainExactlyInAnyOrder
                listOf("amount", "vendor")

            // Clearing a field removes it.
            rig.session.meta.setField(id, "amount", null)
            rig.session.documents.observeDoc(id).first()!!.fields shouldBe mapOf("vendor" to "ACME")
        } finally {
            rig.close()
        }
    }

    @Test
    fun twoWritersConvergeAfterRescan() = runTest {
        val rig = AppSessionRig.create(backgroundScope)
        var db2 = rig.db
        try {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            // Device 1 tags it.
            rig.session.meta.addTag(listOf(id), "fromA")

            // Device 2 opens the SAME library (separate cache, distinct deviceId).
            val (session2, secondDb) = rig.secondSession(backgroundScope)
            db2 = secondDb
            session2.rescan() // folds device 1's segment
            session2.meta.addTag(listOf(id), "fromB")

            // Device 1 rescans, picking up device 2's segment -> both tags present.
            rig.session.rescan()
            val docs = rig.session.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            tagsOf(docs, id) shouldContainExactlyInAnyOrder listOf("fromA", "fromB")

            // Device 2 rescans -> also has both.
            session2.rescan()
            val docs2 = session2.documents.observeDocuments(LibraryFilter(), LibrarySort()).first()
            docs2.first { it.doc.docId == id }.tags shouldContainExactlyInAnyOrder listOf("fromA", "fromB")

            session2.close()
        } finally {
            if (db2 !== rig.db) db2.close()
            rig.close()
        }
    }
}
