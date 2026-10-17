package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FieldKeyCount
import dev.npn.core.db.isLive
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.SplitFailure
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SplitPdfTest {
    @Test
    fun splitTwoPagePdfIntoTwoDocsTrashesOriginalAndCopiesMetadata() =
        appSessionTest { rig ->
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
                .first()
                .shouldNotBeNull()
                .doc.fileName shouldBe "contract (part 1).pdf"

            // Original is trashed, not deleted.
            val trashed =
                rig.session.documents
                    .observeTrashed()
                    .first()
            trashed.map { it.doc.docId } shouldBe listOf(imported.docId)
        }

    /**
     * A field retyped since a value was set keeps the value, as the retype promises; the split
     * carries it onto the parts as the original holds it rather than failing over it.
     */
    @Test
    fun aValueThatNoLongerFitsItsFieldsTypeIsCarriedAsItIs() =
        appSessionTest { rig ->
            val docId = rig.importDocument(PdfFixtures.embeddedTextPdf("retyped"), "invoice", "acme")
            rig.session.fieldRegistry.fields
                .first { it.isNotEmpty() }
            val due = FieldDef("due", "Due", FieldType.TEXT, showByDefault = true, aiEnabled = false, position = 9)
            rig.session.fieldRegistry.upsert(due)
            rig.session.meta.setField(docId, "due", "soon")
            rig.session.fieldRegistry.upsert(due.copy(type = FieldType.DATE))
            // An edit is still checked against the type; only the split's copy carries such a value.
            assertFailsWith<IllegalArgumentException> {
                rig.session.meta.applyEdits(listOf(docId), fields = mapOf("due" to "later"))
            }

            val done = rig.splitInTwo(docId).shouldBeInstanceOf<SplitOutcome.Done>()

            for (partId in done.partDocIds) {
                rig.metadataOf(partId).second["due"] shouldBe "soon"
                rig.metadataOf(partId).second[FieldKeys.CORRESPONDENT] shouldBe "acme"
            }
        }

    /**
     * A split whose original cannot be trashed takes its parts back from wherever the storage-path
     * rule moved them once the metadata reached them, and a rescan finds nothing to bring back.
     */
    @Test
    fun aFailedSplitTakesBackThePartsTheStoragePathRuleMoved() =
        appSessionTest(storagePath = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)) { rig ->
            val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("moved pages"), "contract.pdf")
            val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.addTag(listOf(imported.docId), "invoice")
            rig.session.meta.setField(listOf(imported.docId), FieldKeys.CORRESPONDENT, "acme")
            // The import's OCR writes the row in the background; it is done before the row is kept.
            // The queues drain once the session has had its first rescan, which start() runs.
            rig.session.rescan()
            rig.session.work.drainOcr()
            val original = checkNotNull(rig.db.documentDao().getByDocId(imported.docId))
            original.relPath shouldBe "acme/contract.pdf"
            rig.blockTrash()

            rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1)) shouldBe
                SplitOutcome.Failed(SplitFailure.NOT_COMPLETED)

            rig.shouldHoldOnly(original)
            rig.session.rescan()
            rig.shouldHoldOnly(original)
        }

    /**
     * Another app wrote a different PDF over the document's file since the last rescan (SAF has no
     * watcher). That file is not split as the document: its pages would get the document's metadata.
     */
    @Test
    fun aDocumentWhoseFileWasReplacedIsNotSplit() =
        appSessionTest { rig ->
            val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("the document"), "contract.pdf")
            val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.addTag(listOf(imported.docId), "invoice")
            rig.session.meta.setField(listOf(imported.docId), FieldKeys.CORRESPONDENT, "acme")
            // The import's OCR writes the row in the background; it is done before the row is kept.
            // The queues drain once the session has had its first rescan, which start() runs.
            rig.session.rescan()
            rig.session.work.drainOcr()
            val original = checkNotNull(rig.db.documentDao().getByDocId(imported.docId))
            val replacement = PdfFixtures.embeddedTextPdf("another document")
            rig.fs.delete(original.relPath)
            rig.writePdf(original.relPath, replacement)

            rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1)) shouldBe
                SplitOutcome.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)

            rig.fs.readBytes(original.relPath) shouldBe replacement
            rig.shouldHoldOnly(original)
        }

    /**
     * A part whose bytes are a document the library shows is that document. The split lists it
     * among its parts, but its tags and fields stay its own: only a part the split made gets the
     * original's.
     */
    @Test
    fun aPartThatIsAnExistingDocumentKeepsItsOwnMetadata() =
        runTest {
            val hook = SplitHook()
            AppSessionRig.create(backgroundScope, pdfEngine = hook::over).use { rig ->
                val receiptBytes = PdfFixtures.embeddedTextPdf("receipt")
                val receipt = rig.importDocument(receiptBytes, "receipt", "beta")
                val source = rig.importDocument(PdfFixtures.embeddedTextPdf("contract"), "invoice", "acme")
                val before = rig.metadataOf(receipt)
                hook.firstPart = receiptBytes

                val done = rig.splitInTwo(source).shouldBeInstanceOf<SplitOutcome.Done>()

                done.partDocIds shouldHaveSize 2
                done.partDocIds[0] shouldBe receipt
                rig.metadataOf(receipt) shouldBe before
                rig.metadataOf(done.partDocIds[1]) shouldBe
                    (listOf("invoice") to mapOf(FieldKeys.CORRESPONDENT to "acme"))
            }
        }

    /** A part whose bytes are a trashed document brings that document back with its own metadata. */
    @Test
    fun aPartThatBringsADocumentBackKeepsItsOwnMetadata() =
        runTest {
            val hook = SplitHook()
            AppSessionRig.create(backgroundScope, pdfEngine = hook::over).use { rig ->
                val receiptBytes = PdfFixtures.embeddedTextPdf("receipt")
                val receipt = rig.importDocument(receiptBytes, "receipt", "beta")
                val before = rig.metadataOf(receipt)
                rig.session.docLifecycle
                    .trashDoc(receipt)
                    .shouldNotBeNull()
                val source = rig.importDocument(PdfFixtures.embeddedTextPdf("contract"), "invoice", "acme")
                hook.firstPart = receiptBytes

                val done = rig.splitInTwo(source).shouldBeInstanceOf<SplitOutcome.Done>()

                done.partDocIds[0] shouldBe receipt
                checkNotNull(rig.db.documentDao().getByDocId(receipt)).isLive shouldBe true
                rig.metadataOf(receipt) shouldBe before
            }
        }

    /** A split that cannot trash its original leaves a part that was an existing document as it was. */
    @Test
    fun aFailedSplitLeavesAnExistingDocumentAsItWas() =
        runTest {
            val hook = SplitHook()
            AppSessionRig.create(backgroundScope, pdfEngine = hook::over).use { rig ->
                val receiptBytes = PdfFixtures.embeddedTextPdf("receipt")
                val receipt = rig.importDocument(receiptBytes, "receipt", "beta")
                val source = rig.importDocument(PdfFixtures.embeddedTextPdf("contract"), "invoice", "acme")
                // The imports' OCR writes the rows in the background; it is done before the rows are kept.
                // The queues drain once the session has had its first rescan, which start() runs.
                rig.session.rescan()
                rig.session.work.drainOcr()
                val receiptRow = rig.db.documentDao().getByDocId(receipt)
                val before = rig.metadataOf(receipt)
                hook.firstPart = receiptBytes
                rig.blockTrash()

                rig.splitInTwo(source) shouldBe SplitOutcome.Failed(SplitFailure.NOT_COMPLETED)

                rig.db.documentDao().getByDocId(receipt) shouldBe receiptRow
                rig.metadataOf(receipt) shouldBe before
                val rows = rig.db.documentDao().allPathsAndMeta()
                rows.map { it.docId } shouldContainExactlyInAnyOrder listOf(receipt, source)
            }
        }

    /**
     * An original trashed elsewhere while it was being split is out of the library, as the split
     * wants it: the split is done and keeps its parts.
     */
    @Test
    fun aSplitWhoseOriginalWasTrashedMeanwhileIsDone() =
        runTest {
            val hook = SplitHook()
            AppSessionRig.create(backgroundScope, pdfEngine = hook::over).use { rig ->
                val source = rig.importDocument(PdfFixtures.embeddedTextPdf("contract"), "invoice", "acme")
                hook.beforePart = {
                    rig.session.docLifecycle
                        .trashDoc(source)
                        .shouldNotBeNull()
                }

                val done = rig.splitInTwo(source).shouldBeInstanceOf<SplitOutcome.Done>()

                for (part in done.partDocIds) {
                    checkNotNull(rig.db.documentDao().getByDocId(part)).isLive shouldBe true
                    rig.metadataOf(part).first shouldBe listOf("invoice")
                }
                checkNotNull(rig.db.documentDao().getByDocId(source)).trashed shouldBe true
            }
        }

    @Test
    fun aPartIsNamedAfterItsOriginalWithoutTheExtensionInAnyCase() =
        appSessionTest { rig ->
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
        }

    @Test
    fun splitRejectsNonCoveringSegments() =
        appSessionTest { rig ->
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
        }

    @Test
    fun aSplitOfADocumentThatIsGoneSaysSo() =
        appSessionTest { rig ->
            val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("gone"), "doc.pdf")
            val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.docLifecycle.trashDoc(imported.docId)

            rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1)) shouldBe
                SplitOutcome.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
            rig.session.maintenance.splitPdf("f".repeat(64), listOf(0..0, 1..1)) shouldBe
                SplitOutcome.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
        }
}

/**
 * A PDF engine, for [AppSessionRig.create]'s `pdfEngine`, that runs [beforePart] once before a split
 * makes its next part, and makes the first page into [firstPart] when set: a part whose bytes are a
 * document the library has.
 */
private class SplitHook {
    @Volatile var firstPart: ByteArray? = null

    @Volatile var beforePart: (suspend () -> Unit)? = null

    fun over(engine: PdfEngine): PdfEngine =
        object : PdfEngine by engine {
            override suspend fun extractPages(
                bytes: ByteArray,
                pageIndices: List<Int>,
            ): ByteArray {
                beforePart?.also { beforePart = null }?.invoke()
                return firstPart?.takeIf { pageIndices == listOf(0) } ?: engine.extractPages(bytes, pageIndices)
            }
        }
}

/** Import [bytes] as `<tag>.pdf`, tagged [tag] and from [correspondent]; returns its docId. */
private suspend fun AppSessionRig.importDocument(
    bytes: ByteArray,
    tag: String,
    correspondent: String,
): String {
    val docId = session.importPdfBytes(bytes, "$tag.pdf").shouldBeInstanceOf<ImportOutcome.Imported>().docId
    session.meta.addTag(listOf(docId), tag)
    session.meta.setField(listOf(docId), FieldKeys.CORRESPONDENT, correspondent)
    return docId
}

/** Split the two-page [docId] into one part per page. */
private suspend fun AppSessionRig.splitInTwo(docId: String): SplitOutcome {
    val pages = listOf(0..0, 1..1)
    return session.maintenance.splitPdf(docId, pages)
}

/** [docId]'s tags and its fields by key, as the index has them. */
private suspend fun AppSessionRig.metadataOf(docId: String): Pair<List<String>, Map<String, String>> =
    db.tagDao().tagsForDocument(docId) to
        db.documentFieldDao().valuesOf(docId)

/** Make every move into the trash fail, as a refused SAF move would: the trash folder is a file. */
private fun AppSessionRig.blockTrash() {
    val trash = libRoot.resolve(NpnPaths.TRASH_DIR)
    trash.toFile().deleteRecursively()
    Files.createDirectories(trash.parent)
    Files.write(trash, byteArrayOf(1))
}

/**
 * The library holds [original] as it was and nothing of a part: no row, no tag or field row, no
 * search entry (a part's file name has the word "part"), no file.
 */
private suspend fun AppSessionRig.shouldHoldOnly(original: DocumentEntity) {
    db.documentDao().getByDocId(original.docId) shouldBe original
    db.documentDao().allPathsAndMeta().map { it.docId } shouldContainExactly listOf(original.docId)
    db.tagDao().docIdsWithTag("invoice") shouldContainExactly listOf(original.docId)
    db.documentFieldDao().observeKeysWithCounts().first() shouldContainExactly
        listOf(FieldKeyCount(FieldKeys.CORRESPONDENT, 1))
    db.ftsDao().search("part").shouldBeEmpty()
    fs.listAllIndexable().filterNot { it.isDirectory }.map { it.relPath } shouldContainExactly listOf(original.relPath)
}
