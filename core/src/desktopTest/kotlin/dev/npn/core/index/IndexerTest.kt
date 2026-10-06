package dev.npn.core.index

import dev.npn.core.db.searchDocIds
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFails

class IndexerTest {
    private lateinit var rig: IndexRig

    @BeforeTest fun setup() {
        rig = IndexRig.create()
    }

    @AfterTest fun teardown() {
        rig.close()
    }

    @Test
    fun failedFoldClearsProgress() =
        runTest {
            rig.db.close()

            // A rescan against a closed DB fails inside applyFold; the FOLDING progress must clear.
            assertFails { rig.rescan() }

            rig.indexer.scanState.value shouldBe null
        }

    @Test
    fun freshLibraryIndexesAll() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.writePdf("sub/b.pdf", "bravo")
            rig.writePdf("sub/c.pdf", "charlie")

            val report = rig.rescan()

            report.added shouldBe 3
            report.hashedCount shouldBe 3
            val docs = rig.db.documentDao().allPathsAndMeta()
            docs shouldHaveSize 3
            for (pm in docs) {
                rig.db
                    .documentDao()
                    .getByDocId(pm.docId)!!
                    .addedAt shouldBe 1_000L
            }
        }

    @Test
    fun rescanNoOpHashesNothing() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.writePdf("b.pdf", "bravo")
            rig.rescan().added shouldBe 2

            val second = rig.rescan()
            second.hashedCount shouldBe 0
            second.added shouldBe 0
            second.moved shouldBe 0
            second.changed shouldBe 0
            second.missingCount shouldBe 0
        }

    @Test
    fun moveDetectionKeepsDocId() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            rig.moveFile("a.pdf", "archive/a.pdf")
            val report = rig.rescan()

            report.moved shouldBe 1
            report.added shouldBe 0
            rig.db.documentDao().rowAtPath("a.pdf") shouldBe null
            val moved = rig.db.documentDao().rowAtPath("archive/a.pdf")!!
            moved.docId shouldBe docId
            moved.missing.shouldBeFalse()
        }

    /** A file moved over another document's file takes its path; the overwritten document is missing. */
    @Test
    fun aFileMovedOverAnotherDocumentTakesItsPath() =
        runTest {
            rig.writePdf("p.pdf", "xray")
            rig.writePdf("q.pdf", "alpha")
            rig.rescan()
            val overwritten = rig.fs.sha256("p.pdf")
            val moved = rig.fs.sha256("q.pdf")

            Files.move(rig.libRoot.resolve("q.pdf"), rig.libRoot.resolve("p.pdf"), StandardCopyOption.REPLACE_EXISTING)
            val report = rig.rescan()

            report.moved shouldBe 1
            rig.db
                .documentDao()
                .rowAtPath("p.pdf")
                ?.docId shouldBe moved
            rig.db
                .documentDao()
                .getByDocId(overwritten)
                ?.missing shouldBe true
            rig.rescan().moved shouldBe 0
        }

    /** A copy over another document's file is a duplicate of the copied one; the overwritten one is missing. */
    @Test
    fun aFileCopiedOverAnotherDocumentLeavesThatOneMissing() =
        runTest {
            rig.writePdf("p.pdf", "xray")
            rig.writePdf("q.pdf", "alpha")
            rig.rescan()
            val overwritten = rig.fs.sha256("p.pdf")
            val copied = rig.fs.sha256("q.pdf")

            Files.copy(rig.libRoot.resolve("q.pdf"), rig.libRoot.resolve("p.pdf"), StandardCopyOption.REPLACE_EXISTING)
            val report = rig.rescan()

            report.duplicates shouldContainExactly listOf(DuplicateReport(copied, "p.pdf"))
            rig.db
                .documentDao()
                .getByDocId(copied)
                ?.relPath shouldBe "q.pdf"
            rig.db
                .documentDao()
                .getByDocId(overwritten)
                ?.missing shouldBe true
        }

    /** Names shuffled in one go (a to b, then c to a): every document follows its bytes. */
    @Test
    fun shuffledNamesFollowTheirBytesInOneRescan() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.writePdf("c.pdf", "charlie")
            rig.rescan()
            val alpha = rig.fs.sha256("a.pdf")
            val charlie = rig.fs.sha256("c.pdf")

            rig.moveFile("a.pdf", "b.pdf")
            rig.moveFile("c.pdf", "a.pdf")
            val report = rig.rescan()

            report.moved shouldBe 2
            report.missingCount shouldBe 0
            report.duplicates.shouldBeEmpty()
            report.relinkCandidates.shouldBeEmpty()
            rig.db
                .documentDao()
                .rowAtPath("a.pdf")
                ?.docId shouldBe charlie
            rig.db
                .documentDao()
                .rowAtPath("b.pdf")
                ?.docId shouldBe alpha
        }

    @Test
    fun externalDuplicateRecordedAndResolved() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            rig.copyFile("a.pdf", "copies/a-copy.pdf")
            val report = rig.rescan()

            report.duplicates shouldHaveSize 1
            report.duplicates.first().docId shouldBe docId
            report.duplicates.first().extraRelPath shouldBe "copies/a-copy.pdf"
            // canonical row untouched
            rig.db
                .documentDao()
                .getByDocId(docId)!!
                .relPath shouldBe "a.pdf"
            rig.db.duplicateDao().forDoc(docId) shouldHaveSize 1

            rig.maintenance.resolveDuplicate(docId, "copies/a-copy.pdf")
            rig.exists("copies/a-copy.pdf").shouldBeFalse()
            rig.db.duplicateDao().forDoc(docId) shouldHaveSize 0
        }

    @Test
    fun externalEditCreatesRelinkCandidateAndCarriesTags() =
        runTest {
            rig.writePdf("a.pdf", "original")
            rig.rescan()
            val oldId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            // Tag the old doc via a real journal event, then fold it in.
            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(listOf(PendingOp.tagAdd(oldId, "invoice")))
            rig.rescan()
            rig.db.tagDao().tagsForDocument(oldId) shouldContainExactly listOf("invoice")

            // Overwrite bytes at the same path (external edit).
            rig.deleteFile("a.pdf")
            rig.writePdf("a.pdf", "edited-content")
            rig.clock.advance()
            val report = rig.rescan()

            val newId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId
            (newId != oldId).shouldBeTrue()
            rig.db
                .documentDao()
                .getByDocId(oldId)!!
                .missing
                .shouldBeTrue()
            report.relinkCandidates
                .any { it.oldDocId == oldId && it.newDocId == newId && it.reason == "path-match" }
                .shouldBeTrue()

            // Apply relink: metadata carries to new doc, old doc superseded (deleted). The coordinator
            // must have its live folded state seeded (production seeds it in openSession) so the
            // rescan folds against the current dots, not an empty state.
            rig.clock.advance()
            val coordinator = rig.coordinator(writer)
            coordinator.refreshState()
            val relink = RelinkService(coordinator)
            relink.applyRelink(oldId, newId)

            rig.db.tagDao().tagsForDocument(newId) shouldContainExactly listOf("invoice")
            rig.db
                .documentDao()
                .getByDocId(oldId)!!
                .deleted
                .shouldBeTrue()

            // Superseded for good: no later fold shows it again, as missing or otherwise.
            repeat(2) {
                coordinator.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(oldId)!!
                    .deleted
                    .shouldBeTrue()
            }
        }

    @Test
    fun disappearAndReappear() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            rig.deleteFile("a.pdf")
            val gone = rig.rescan()
            gone.missingCount shouldBe 1
            rig.db
                .documentDao()
                .getByDocId(docId)!!
                .missing
                .shouldBeTrue()

            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            rig.db
                .documentDao()
                .getByDocId(docId)!!
                .missing
                .shouldBeFalse()
        }

    @Test
    fun conflictFileReportedDiscardAndImport() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.writePdf("b.sync-conflict-20260101-120000-ABCDEFG.pdf", "conflicted")

            val report = rig.rescan()
            report.conflictFiles shouldContain "b.sync-conflict-20260101-120000-ABCDEFG.pdf"
            report.added shouldBe 1 // only a.pdf indexed

            // Discard deletes.
            rig.writePdf("c.sync-conflict-20260101-120000-XYZ0000.pdf", "junk")
            rig.maintenance.resolveConflictFile("c.sync-conflict-20260101-120000-XYZ0000.pdf", ConflictAction.Discard)
            rig.exists("c.sync-conflict-20260101-120000-XYZ0000.pdf").shouldBeFalse()

            // ImportAsNew imports the conflict file.
            val imported =
                rig.maintenance.resolveConflictFile(
                    "b.sync-conflict-20260101-120000-ABCDEFG.pdf",
                    ConflictAction.ImportAsNew,
                )
            imported.shouldNotBeNull()
            rig.exists("b.sync-conflict-20260101-120000-ABCDEFG.pdf").shouldBeFalse()
            rig.exists(imported).shouldBeTrue()
        }

    @Test
    fun conflictFileKeepStripsSuffix() =
        runTest {
            rig.writePdf("report.sync-conflict-20260101-120000-ABCDEFG.pdf", "data")
            val kept =
                rig.maintenance.resolveConflictFile(
                    "report.sync-conflict-20260101-120000-ABCDEFG.pdf",
                    ConflictAction.Keep,
                )
            kept shouldBe "report.pdf"
            rig.exists("report.pdf").shouldBeTrue()
        }

    @Test
    fun journalFoldAppliesTagsAndProps() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(
                listOf(
                    PendingOp.tagAdd(docId, "receipt"),
                    PendingOp.propSet(docId, SystemProperty.REVIEWED, "true"),
                    PendingOp.fieldSet(docId, FieldKeys.CORRESPONDENT, "acme"),
                ),
            )
            rig.rescan()

            rig.db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("receipt")
            val row = rig.db.documentDao().getByDocId(docId)!!
            row.reviewed shouldBe true
            row.correspondent shouldBe "acme"

            // tag-rename event -> tags updated
            rig.clock.advance()
            writer.commit(listOf(PendingOp.tagRename("receipt", "invoice")))
            rig.rescan()
            rig.db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")
        }

    @Test
    fun foldKeepsCachedColumnsInSyncWithFieldRows() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(
                listOf(
                    PendingOp.fieldSet(docId, FieldKeys.TITLE, "Annual report"),
                    PendingOp.fieldSet(docId, FieldKeys.CORRESPONDENT, "acme"),
                    PendingOp.fieldSet(docId, FieldKeys.DOC_TYPE, "report"),
                    PendingOp.fieldSet(docId, FieldKeys.DOC_DATE, "2026-01-15"),
                    PendingOp.fieldSet(docId, FieldKeys.NOTES, "keep for taxes"),
                ),
            )
            rig.rescan()

            // The `documents` cache columns are written by the Indexer from the field map, so each
            // one must agree with the `document_fields` row for the same key.
            val row = rig.db.documentDao().getByDocId(docId)!!
            val fields =
                rig.db
                    .documentFieldDao()
                    .fieldsForDocs(listOf(docId))
                    .associate { it.fieldKey to it.fieldValue }

            row.title shouldBe fields[FieldKeys.TITLE]
            row.correspondent shouldBe fields[FieldKeys.CORRESPONDENT]
            row.docType shouldBe fields[FieldKeys.DOC_TYPE]
            row.docDate shouldBe fields[FieldKeys.DOC_DATE]
            row.notes shouldBe fields[FieldKeys.NOTES]
        }

    @Test
    fun importFlow() =
        runTest {
            val src = rig.externalFile("scan.pdf", "scanned-bytes")
            val result = rig.importService.importPdf(src, "invoice.pdf")
            result.shouldBeInstanceOfImported()
            val imported = result as ImportResult.Imported
            imported.relPath shouldBe "inbox/invoice.pdf"
            rig.deviceDb
                .ocrQueueDao()
                .nextPending(now = Long.MAX_VALUE)
                ?.docId shouldBe imported.docId

            // identical bytes -> Duplicate
            val src2 = rig.externalFile("scan2.pdf", "scanned-bytes")
            val dup = rig.importService.importPdf(src2, "invoice.pdf")
            (dup is ImportResult.Duplicate).shouldBeTrue()
            (dup as ImportResult.Duplicate).existingDocId shouldBe imported.docId

            // different bytes, same name -> " (2)" suffix
            val src3 = rig.externalFile("scan3.pdf", "other-bytes")
            val third = rig.importService.importPdf(src3, "invoice.pdf")
            (third as ImportResult.Imported).relPath shouldBe "inbox/invoice (2).pdf"

            // .npn/tmp left clean
            rig.exists(".npn/tmp").let { present ->
                if (present) {
                    val entries = Files.list(rig.libRoot.resolve(".npn/tmp")).use { it.count() }
                    entries shouldBe 0L
                }
            }
        }

    @Test
    fun importBytesFlow() =
        runTest {
            val result = rig.importService.importBytes("payload".encodeToByteArray(), "converted.pdf")
            (result is ImportResult.Imported).shouldBeTrue()
            val imp = result as ImportResult.Imported
            imp.relPath shouldBe "inbox/converted.pdf"
            rig.exists("inbox/converted.pdf").shouldBeTrue()
        }

    /** Hide the document at [relPath] through the journal, as another device would, and take its file away. */
    private suspend fun hide(
        relPath: String,
        op: (String) -> PendingOp,
    ): String {
        val docId = rig.fs.sha256(relPath)
        rig.clock.advance()
        rig.writer("dev-main").commit(listOf(op(docId)))
        rig.deleteFile(relPath)
        rig.rescan()
        return docId
    }

    /** A deleted document's row keeps its path; other bytes imported under that name still get a row. */
    @Test
    fun anImportOntoThePathOfADeletedDocumentIsIndexed() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val deleted = hide("inbox/scan.pdf") { PendingOp.docDelete(it) }

            val imported =
                rig.importService.importBytes(
                    "bravo".encodeToByteArray(),
                    "scan.pdf",
                ) as ImportResult.Imported

            imported.relPath shouldBe "inbox/scan.pdf"
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe imported.docId
            rig.rescan()
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe imported.docId
            rig.db
                .documentDao()
                .getByDocId(deleted)
                ?.deleted shouldBe true
        }

    @Test
    fun anImportOntoThePathOfATrashedDocumentIsIndexed() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val trashed = hide("inbox/scan.pdf") { PendingOp.docTrash(it, "inbox/scan.pdf") }

            val imported =
                rig.importService.importBytes(
                    "bravo".encodeToByteArray(),
                    "scan.pdf",
                ) as ImportResult.Imported

            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe imported.docId
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            row.trashed.shouldBeTrue()
            row.trashedOrigPath shouldBe "inbox/scan.pdf"
        }

    /** A file copied in from outside onto a deleted document's old path is a new document there. */
    @Test
    fun aFileCopiedOntoThePathOfADeletedDocumentIsIndexed() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val deleted = hide("a.pdf") { PendingOp.docDelete(it) }

            rig.writePdf("a.pdf", "bravo")
            val report = rig.rescan()

            report.added shouldBe 1
            rig.db
                .documentDao()
                .rowAtPath("a.pdf")
                ?.docId shouldBe rig.fs.sha256("a.pdf")
            rig.db
                .documentDao()
                .getByDocId(deleted)
                ?.deleted shouldBe true
        }

    @Test
    fun twoDeviceInterleavedTagsFoldDeterministically() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            val devA = rig.writer("device-A")
            val devB = rig.writer("device-B")

            rig.clock.advance()
            devA.commit(listOf(PendingOp.tagAdd(docId, "work")))
            rig.clock.advance()
            devB.commit(listOf(PendingOp.tagAdd(docId, "urgent")))
            rig.clock.advance()
            devA.commit(listOf(PendingOp.tagAdd(docId, "2026")))

            rig.rescan()
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .sorted() shouldContainExactly listOf("2026", "urgent", "work")

            // a second rescan folds identically (idempotent)
            rig.rescan()
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .sorted() shouldContainExactly listOf("2026", "urgent", "work")
        }

    @Test
    fun foldPreservesFtsContentAcrossMetadataChange() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            // Simulate the OCR pipeline writing extracted text into the FTS content column.
            rig.db.ftsDao().upsertContent(
                FtsRows.row(
                    docId = docId,
                    fileName = "a.pdf",
                    tags = emptyList(),
                    fields = emptyMap(),
                    content = "quarterly electricity invoice for acme corporation",
                ),
            )
            rig.db.ftsDao().searchDocIds("electricity") shouldContainExactly listOf(docId)

            // A metadata op (tag add + prop set) committed through the coordinator projects
            // incrementally (the live path); it must not wipe the FTS content.
            val writer = rig.writer("dev-main")
            val coordinator = rig.coordinator(writer)
            coordinator.refreshState()
            rig.clock.advance()
            coordinator.commitAndApply(
                listOf(
                    PendingOp.tagAdd(docId, "receipt"),
                    PendingOp.fieldSet(docId, FieldKeys.CORRESPONDENT, "acme"),
                ),
            )

            // Content still searchable.
            rig.db.ftsDao().searchDocIds("electricity") shouldContainExactly listOf(docId)
            rig.db.ftsDao().contentForDoc(docId) shouldBe "quarterly electricity invoice for acme corporation"
            // Metadata columns updated by the fold.
            rig.db.ftsDao().searchDocIds("receipt") shouldContainExactly listOf(docId)
            rig.db.ftsDao().searchDocIds("acme") shouldContainExactly listOf(docId)
        }

    // Same guarantee as foldPreservesFtsContentAcrossMetadataChange, but via a full rescan.
    @Test
    fun rescanPreservesFtsContent() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            rig.db.ftsDao().upsertContent(
                FtsRows.row(
                    docId = docId,
                    fileName = "a.pdf",
                    tags = emptyList(),
                    fields = emptyMap(),
                    content = "boil water add pasta and salt",
                ),
            )

            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(listOf(PendingOp.tagAdd(docId, "recipe")))
            rig.rescan()

            rig.db.ftsDao().searchDocIds("pasta") shouldContainExactly listOf(docId)
            rig.db.ftsDao().searchDocIds("recipe") shouldContainExactly listOf(docId)
        }

    @Test
    fun trashResurrectionPreferredFile() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId

            // Journal says trashed with origPath a.pdf, but the file is still present on disk.
            val writer = rig.writer("dev-main")
            rig.clock.advance()
            writer.commit(listOf(PendingOp.docTrash(docId, "a.pdf")))

            val resurrected = ArrayList<Resurrected>()
            rig.rescan { resurrected += it }
            resurrected.map { it.docId } shouldContainExactly listOf(docId)
            val row = rig.db.documentDao().getByDocId(docId)!!
            row.trashed.shouldBeFalse()
            row.missing.shouldBeFalse()
        }

    /** Only a document's own bytes bring it back: another file at its old path is another document. */
    @Test
    fun anotherFileAtATrashedDocumentsPathLeavesItInTheTrash() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val trashed = hide("inbox/scan.pdf") { PendingOp.docTrash(it, "inbox/scan.pdf") }

            rig.writePdf("inbox/scan.pdf", "bravo")
            val resurrected = ArrayList<Resurrected>()
            rig.rescan { resurrected += it }

            resurrected.shouldBeEmpty()
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe rig.fs.sha256("inbox/scan.pdf")
            rig.db
                .documentDao()
                .getByDocId(trashed)
                ?.trashed shouldBe true
        }

    @Test
    fun anotherFileAtADeletedDocumentsPathLeavesItDeleted() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val deleted = rig.fs.sha256("inbox/scan.pdf")
            // A title, so every fold projects the deleted document.
            rig.clock.advance()
            rig.writer("dev-main").commit(listOf(PendingOp.fieldSet(deleted, FieldKeys.TITLE, "First scan")))
            hide("inbox/scan.pdf") { PendingOp.docTrash(it, "inbox/scan.pdf") }
            rig.clock.advance()
            rig.writer("dev-main").commit(listOf(PendingOp.docDelete(deleted)))
            rig.rescan()

            rig.writePdf("inbox/scan.pdf", "bravo")
            val resurrected = ArrayList<Resurrected>()
            repeat(2) { rig.rescan { resurrected += it } }

            resurrected.shouldBeEmpty()
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe rig.fs.sha256("inbox/scan.pdf")
            rig.db
                .documentDao()
                .getByDocId(deleted)
                ?.deleted shouldBe true
        }
}

private fun ImportResult.shouldBeInstanceOfImported() {
    check(this is ImportResult.Imported) { "expected Imported but was $this" }
}
