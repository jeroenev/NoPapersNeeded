package dev.npn.core.app

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.requireByDocId
import dev.npn.core.index.requireDoc
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MetadataWriterTest {
    @Test
    fun addTagThenRemoveTag() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.addTag(listOf(id), "invoice")
            var docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(docs, id) shouldContain "invoice"

            rig.session.meta.removeTag(listOf(id), "invoice")
            docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(docs, id) shouldNotContain "invoice"
        }

    @Test
    fun setReviewedReflected() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setReviewed(listOf(id), true)
            rig.db
                .documentDao()
                .requireByDocId(id)
                .reviewed shouldBe true
        }

    @Test
    fun deleteTagRemovesFromAllDocs() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.session.rescan()
            val a = rig.docId("a.pdf")
            val b = rig.docId("b.pdf")

            rig.session.meta.addTag(listOf(a, b), "shared")
            var docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(docs, a) shouldContain "shared"
            tagsOf(docs, b) shouldContain "shared"

            rig.session.tagAdmin.deleteTag("shared")
            docs =
                rig.session.documents
                    .observeDocuments(LibraryFilter(), LibrarySort())
                    .first()
            tagsOf(docs, a) shouldNotContain "shared"
            tagsOf(docs, b) shouldNotContain "shared"
        }

    @Test
    fun unusedTagsArePrunedFromTagList() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.addTag(listOf(id), "fleeting")
            rig.session.tags
                .observeTags()
                .first()
                .map { it.name } shouldContain "fleeting"

            // Untagging the last document drops the tag from the list entirely (no count-0 rows).
            rig.session.meta.removeTag(listOf(id), "fleeting")
            rig.session.tags
                .observeTags()
                .first()
                .map { it.name } shouldNotContain "fleeting"

            // A style alone does not keep an unused tag alive, but it survives in the journal and
            // comes back when the tag is used again.
            rig.session.tagAdmin.setTagStyle("styled", "#ff0000", null)
            rig.session.tags
                .observeTags()
                .first()
                .map { it.name } shouldNotContain "styled"
            rig.session.meta.addTag(listOf(id), "styled")
            val styled =
                rig.session.tags
                    .observeTags()
                    .first()
                    .first { it.name == "styled" }
            styled.color shouldBe "#ff0000"

            // deleteTag prunes as well.
            rig.session.tagAdmin.deleteTag("styled")
            rig.session.tags
                .observeTags()
                .first()
                .map { it.name } shouldNotContain "styled"
        }

    @Test
    fun setCorrespondentAndDocType() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setField(listOf(id), "correspondent", "ACME")
            rig.session.meta.setField(listOf(id), "docType", "Invoice")
            val doc = rig.db.documentDao().requireByDocId(id)
            doc.correspondent shouldBe "ACME"
            doc.docType shouldBe "Invoice"

            // Vocabulary now comes from the field registry's distinct values, not the
            // removed observeCorrespondents()/observeDocTypes() repository accessors.
            rig.session.fieldRegistry
                .distinctValues(FieldKeys.CORRESPONDENT)
                .first { it.isNotEmpty() } shouldContain "ACME"
            rig.session.fieldRegistry
                .distinctValues(FieldKeys.DOC_TYPE)
                .first { it.isNotEmpty() } shouldContain "Invoice"
        }

    @Test
    fun setFieldProjectsToDbAndObserveDocAndKeys() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.meta.setField(id, "amount", "42.50")
            rig.session.meta.setField(id, "vendor", "ACME")

            // Projected into document_fields and surfaced via observeDoc.
            val item =
                rig.session.documents
                    .requireDoc(id)
            item.doc.fields shouldBe mapOf("amount" to "42.50", "vendor" to "ACME")

            // Unregistered keys surface as orphans, with usage counts.
            rig.session.fieldRegistry
                .orphanKeys()
                .first()
                .map { it.fieldKey } shouldContainExactlyInAnyOrder
                listOf("amount", "vendor")

            // Clearing a field removes it.
            rig.session.meta.setField(id, "amount", null)
            rig.session.documents
                .requireDoc(id)
                .doc.fields shouldBe mapOf("vendor" to "ACME")
        }

    /**
     * A value that does not fit its registry type is rejected before anything is written: an
     * off-type `docDate` throws [IllegalArgumentException] and commits no journal object. A blank
     * value clears the field instead of storing an empty string — no `document_fields` row and the
     * derived [Doc.title] reads null.
     */
    @Test
    fun setFieldRejectsOffTypeDateAndBlankClears() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            // An off-type docDate is rejected and writes nothing.
            val before = rig.journalObjectCount()
            assertFailsWith<IllegalArgumentException> {
                rig.session.meta.setField(id, "docDate", "not-a-date")
            }
            rig.journalObjectCount() shouldBe before

            // A blank title clears the field: no document_fields row, derived title is null.
            rig.session.meta.setField(id, "title", "Real title")
            rig.session.meta.setField(id, "title", "  ")
            rig.db
                .documentFieldDao()
                .fieldsForDocs(listOf(id))
                .map { it.fieldKey } shouldNotContain "title"
            rig.session.documents
                .requireDoc(id)
                .doc.title shouldBe null
        }

    /**
     * [MetadataWriter.setOcrEngine] pins the per-document OCR engine as a `prop-set` journal
     * event for key `ocrEngine` (the [SystemProperty.OCR_ENGINE] wire key), which the Indexer
     * projects onto [DocumentEntity.ocrEngineOverride]. Clearing (null) removes the override.
     */
    @Test
    fun setOcrEngineCommitsPropSetAndProjectsOverride() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")

            rig.session.ocr.setOcrEngine(id, OcrEngineRef.Known(OcrEngineId.TESSERACT))

            // Committed as a prop-set event carrying the "ocrEngine" property key.
            val events = rig.db.metadataProjectionDao().allEvents()
            val propSet = events.filter { it.operation == "prop-set" }
            propSet.map { it.documentId } shouldContain id
            propSet.any { it.canonicalJson.contains("\"ocrEngine\"") } shouldBe true

            // Projected onto the override column (not the indexed-version column).
            rig.db
                .documentDao()
                .requireByDocId(id)
                .ocrEngineOverride shouldBe "tesseract"

            // Clearing removes the override.
            rig.session.ocr.setOcrEngine(id, null)
            rig.db
                .documentDao()
                .requireByDocId(id)
                .ocrEngineOverride shouldBe null
        }

    /**
     * Regression: [DocumentRepository.observeDoc] must re-emit on tag changes without a
     * re-subscribe (the detail view used to read tags one-shot and only re-emitted on
     * `documents`/`document_fields` invalidations, leaving the screen stale until reopened).
     * Each collector below subscribes BEFORE the write, so its predicate can only be satisfied
     * by a re-emission of the live flow.
     */
    @Test
    fun observeDocReEmitsTagEditsOnLiveSubscription() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val flow = rig.session.documents.observeDoc(id)

            coroutineScope {
                val added =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        flow.first { it != null && "invoice" in it.tags }
                    }
                rig.session.meta.addTag(listOf(id), "invoice")
                added.await()

                val removed =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        flow.first { it != null && "invoice" !in it.tags }
                    }
                rig.session.meta.removeTag(listOf(id), "invoice")
                removed.await()

                val readded =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        flow.first { it != null && "invoice" in it.tags }
                    }
                rig.session.meta.addTag(listOf(id), "invoice")
                readded.await()
            }
        }

    /**
     * Once the tag is published the write landed: the storage-path rule's move after it failing is
     * logged, not thrown, so no caller takes the edit for one that did not land and sends it again.
     */
    @Test
    fun aFollowUpThatFailsAfterTheWriteLandedDoesNotFailTheWrite() =
        runTest {
            var existsFails = false
            AppSessionRig
                .create(
                    backgroundScope,
                    storagePath = StoragePathRule("{tags}/{title}.pdf", enabled = true),
                    files = { fs ->
                        object : LibraryFileSystem by fs {
                            override suspend fun exists(relPath: String): Boolean {
                                if (existsFails) throw IOException("directory query failed")
                                return fs.exists(relPath)
                            }
                        }
                    },
                ).use { rig ->
                    rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                    rig.session.rescan()
                    val id = rig.docId("a.pdf")
                    val before = rig.journalObjectCount()
                    existsFails = true

                    rig.session.meta.addTag(listOf(id), "invoice")

                    existsFails = false
                    rig.session.documents
                        .requireDoc(id)
                        .tags shouldBe listOf("invoice")
                    rig.journalObjectCount() shouldBe before + 1
                }
        }
}
