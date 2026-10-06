package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FieldKeyCount
import dev.npn.core.fs.NpnPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
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

    /**
     * A split whose original cannot be trashed takes its parts back from wherever the storage-path
     * rule moved them once the metadata reached them, and a rescan finds nothing to bring back.
     */
    @Test
    fun aFailedSplitTakesBackThePartsTheStoragePathRuleMoved() =
        runTest {
            val rule = StoragePathRule("{correspondent}/{title}.pdf", enabled = true)
            val rig = AppSessionRig.create(backgroundScope, storagePath = rule)
            try {
                val outcome = rig.session.importPdfBytes(PdfFixtures.embeddedTextPdf("moved pages"), "contract.pdf")
                val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
                rig.session.meta.addTag(listOf(imported.docId), "invoice")
                rig.session.meta.setField(listOf(imported.docId), FieldKeys.CORRESPONDENT, "acme")
                val original = checkNotNull(rig.db.documentDao().getByDocId(imported.docId))
                original.relPath shouldBe "acme/contract.pdf"
                rig.blockTrash()

                rig.session.maintenance.splitPdf(imported.docId, listOf(0..0, 1..1)) shouldBe
                    SplitOutcome.Failed(SplitFailure.NOT_COMPLETED)

                rig.shouldHoldOnly(original)
                rig.session.rescan()
                rig.shouldHoldOnly(original)
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
