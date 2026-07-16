package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.DocProperty
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class IndexerTest {

    private lateinit var rig: IndexRig

    @BeforeTest fun setup() { rig = IndexRig.create() }

    @AfterTest fun teardown() { rig.close() }

    // 1. fresh library: N pdfs -> N documents, addedAt set, report.added == N
    @Test
    fun freshLibraryIndexesAll() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.writePdf("sub/b.pdf", "bravo")
        rig.writePdf("sub/c.pdf", "charlie")

        val report = rig.indexer.rescan()

        report.added shouldBe 3
        report.hashedCount shouldBe 3
        val docs = rig.db.documentDao().allPathsAndMeta()
        docs shouldHaveSize 3
        for (pm in docs) {
            rig.db.documentDao().getByDocId(pm.docId)!!.addedAt shouldBe 1_000L
        }
    }

    // 2. rescan no-op: second rescan hashes nothing (fast path), zero changes
    @Test
    fun rescanNoOpHashesNothing() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.writePdf("b.pdf", "bravo")
        rig.indexer.rescan().added shouldBe 2

        val second = rig.indexer.rescan()
        second.hashedCount shouldBe 0
        second.added shouldBe 0
        second.moved shouldBe 0
        second.changed shouldBe 0
        second.missingCount shouldBe 0
    }

    // 3. move detection: move file externally -> relPath updated, same docId
    @Test
    fun moveDetectionKeepsDocId() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        rig.moveFile("a.pdf", "archive/a.pdf")
        val report = rig.indexer.rescan()

        report.moved shouldBe 1
        report.added shouldBe 0
        rig.db.documentDao().getByPath("a.pdf") shouldBe null
        val moved = rig.db.documentDao().getByPath("archive/a.pdf")!!
        moved.docId shouldBe docId
        moved.missing.shouldBeFalse()
    }

    // 4. external duplicate: copy file to a second path -> DuplicateEntity recorded, canonical
    //    untouched; resolveDuplicate removes extra
    @Test
    fun externalDuplicateRecordedAndResolved() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        rig.copyFile("a.pdf", "copies/a-copy.pdf")
        val report = rig.indexer.rescan()

        report.duplicates shouldHaveSize 1
        report.duplicates.first().docId shouldBe docId
        report.duplicates.first().extraRelPath shouldBe "copies/a-copy.pdf"
        // canonical row untouched
        rig.db.documentDao().getByDocId(docId)!!.relPath shouldBe "a.pdf"
        rig.db.duplicateDao().forDoc(docId) shouldHaveSize 1

        rig.maintenance.resolveDuplicate(docId, "copies/a-copy.pdf", keepCanonical = true)
        rig.exists("copies/a-copy.pdf").shouldBeFalse()
        rig.db.duplicateDao().forDoc(docId) shouldHaveSize 0
    }

    // 5. changed bytes at path -> old missing + new doc + relink candidate; applyRelink carries
    //    tags (set via a real journal event) to the new doc and emits relink event
    @Test
    fun externalEditCreatesRelinkCandidateAndCarriesTags() = runTest {
        rig.writePdf("a.pdf", "original")
        rig.indexer.rescan()
        val oldId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        // Tag the old doc via a real journal event, then fold it in.
        val writer = rig.writer("dev-main")
        rig.clock.advance()
        writer.commit(listOf(PendingOp.tagAdd(oldId, "invoice")))
        rig.indexer.rescan()
        rig.db.tagDao().tagsForDocument(oldId) shouldContainExactly listOf("invoice")

        // Overwrite bytes at the same path (external edit).
        rig.deleteFile("a.pdf")
        rig.writePdf("a.pdf", "edited-content")
        rig.clock.advance()
        val report = rig.indexer.rescan()

        val newId = rig.db.documentDao().getByPath("a.pdf")!!.docId
        (newId != oldId).shouldBeTrue()
        rig.db.documentDao().getByDocId(oldId)!!.missing.shouldBeTrue()
        report.relinkCandidates.any { it.oldDocId == oldId && it.newDocId == newId && it.reason == "path-match" }
            .shouldBeTrue()

        // Apply relink: metadata carries to new doc, old doc superseded (deleted).
        rig.clock.advance()
        val relink = RelinkService(rig.coordinator(writer))
        relink.applyRelink(oldId, newId)

        rig.db.tagDao().tagsForDocument(newId) shouldContainExactly listOf("invoice")
        rig.db.documentDao().getByDocId(oldId)!!.deleted.shouldBeTrue()
    }

    // 6. disappeared file -> missing; reappearing -> missing cleared
    @Test
    fun disappearAndReappear() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        rig.deleteFile("a.pdf")
        val gone = rig.indexer.rescan()
        gone.missingCount shouldBe 1
        rig.db.documentDao().getByDocId(docId)!!.missing.shouldBeTrue()

        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        rig.db.documentDao().getByDocId(docId)!!.missing.shouldBeFalse()
    }

    // 7. conflict file listed in report; resolveConflictFile Discard deletes; ImportAsNew imports
    @Test
    fun conflictFileReportedDiscardAndImport() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.writePdf("b.sync-conflict-20260101-120000-ABCDEFG.pdf", "conflicted")

        val report = rig.indexer.rescan()
        report.conflictFiles shouldContain "b.sync-conflict-20260101-120000-ABCDEFG.pdf"
        report.added shouldBe 1 // only a.pdf indexed

        // Discard deletes.
        rig.writePdf("c.sync-conflict-20260101-120000-XYZ0000.pdf", "junk")
        rig.maintenance.resolveConflictFile("c.sync-conflict-20260101-120000-XYZ0000.pdf", ConflictAction.Discard)
        rig.exists("c.sync-conflict-20260101-120000-XYZ0000.pdf").shouldBeFalse()

        // ImportAsNew imports the conflict file.
        val imported = rig.maintenance.resolveConflictFile(
            "b.sync-conflict-20260101-120000-ABCDEFG.pdf",
            ConflictAction.ImportAsNew,
        )
        imported.shouldNotBeNull()
        rig.exists("b.sync-conflict-20260101-120000-ABCDEFG.pdf").shouldBeFalse()
        rig.exists(imported).shouldBeTrue()
    }

    @Test
    fun conflictFileKeepStripsSuffix() = runTest {
        rig.writePdf("report.sync-conflict-20260101-120000-ABCDEFG.pdf", "data")
        val kept = rig.maintenance.resolveConflictFile(
            "report.sync-conflict-20260101-120000-ABCDEFG.pdf",
            ConflictAction.Keep,
        )
        kept shouldBe "report.pdf"
        rig.exists("report.pdf").shouldBeTrue()
    }

    // 8. journal fold applied: tag-add + prop-set + tag-rename
    @Test
    fun journalFoldAppliesTagsAndProps() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        val writer = rig.writer("dev-main")
        rig.clock.advance()
        writer.commit(
            listOf(
                PendingOp.tagAdd(docId, "receipt"),
                PendingOp.propSet(docId, DocProperty.REVIEWED, "true"),
                PendingOp.propSet(docId, DocProperty.CORRESPONDENT, "acme"),
            ),
        )
        rig.indexer.rescan()

        rig.db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("receipt")
        val row = rig.db.documentDao().getByDocId(docId)!!
        row.reviewed shouldBe true
        row.correspondent shouldBe "acme"

        // tag-rename event -> tags updated
        rig.clock.advance()
        writer.commit(listOf(PendingOp.tagRename("receipt", "invoice")))
        rig.indexer.rescan()
        rig.db.tagDao().tagsForDocument(docId) shouldContainExactly listOf("invoice")
    }

    // 9. import: new pdf -> Imported + ocr queue row; identical bytes -> Duplicate; name
    //    collision -> " (2)"; .npn/tmp left clean
    @Test
    fun importFlow() = runTest {
        val src = rig.externalFile("scan.pdf", "scanned-bytes")
        val result = rig.importService.importPdf(src, "invoice.pdf")
        result.shouldBeInstanceOfImported()
        val imported = result as ImportResult.Imported
        imported.relPath shouldBe "inbox/invoice.pdf"
        rig.db.ocrQueueDao().nextPending()!!.docId shouldBe imported.docId

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
                val entries = java.nio.file.Files.list(rig.libRoot.resolve(".npn/tmp")).use { it.count() }
                entries shouldBe 0L
            }
        }
    }

    @Test
    fun importBytesFlow() = runTest {
        val result = rig.importService.importBytes("payload".encodeToByteArray(), "converted.pdf")
        (result is ImportResult.Imported).shouldBeTrue()
        val imp = result as ImportResult.Imported
        imp.relPath shouldBe "inbox/converted.pdf"
        rig.exists("inbox/converted.pdf").shouldBeTrue()
    }

    // 10. two-device simulation: two writers, interleaved tag events, deterministic fold
    @Test
    fun twoDeviceInterleavedTagsFoldDeterministically() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        val devA = rig.writer("device-A")
        val devB = rig.writer("device-B")

        rig.clock.advance()
        devA.commit(listOf(PendingOp.tagAdd(docId, "work")))
        rig.clock.advance()
        devB.commit(listOf(PendingOp.tagAdd(docId, "urgent")))
        rig.clock.advance()
        devA.commit(listOf(PendingOp.tagAdd(docId, "2026")))

        rig.indexer.rescan()
        rig.db.tagDao().tagsForDocument(docId).sorted() shouldContainExactly listOf("2026", "urgent", "work")

        // a second rescan folds identically (idempotent)
        rig.indexer.rescan()
        rig.db.tagDao().tagsForDocument(docId).sorted() shouldContainExactly listOf("2026", "urgent", "work")
    }

    // 11. deviceId fork: foreign segment in own dir -> DeviceIdentity yields a fresh id
    @Test
    fun deviceIdForkOnForeignSegment() = runTest {
        val kv = rig.db.keyValueDao()
        val identity = DeviceIdentity(kv)
        val deviceId = identity.getOrCreate()

        // This installation writes a segment normally.
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId
        val writer = rig.writer(deviceId)
        rig.clock.advance()
        writer.commit(listOf(PendingOp.tagAdd(docId, "mine")))

        // Simulate a foreign installation sharing this device id: a segment appears in the same
        // device directory that our local state does not know about.
        rig.clock.advance()
        rig.fsSegmentStore.writeSegment(
            deviceId,
            dev.npn.core.journal.SegmentRef.plainName(999L),
            "",
        )

        val forkedWriter = rig.writer(deviceId)
        val newId = identity.ensureSingleWriter(forkedWriter)
        (newId != deviceId).shouldBeTrue()
        kv.get(DeviceIdentity.KEY_DEVICE_ID) shouldBe newId
    }

    // 12. FTS content preserved across a metadata fold: OCR writes content, a tag-add fold must
    //     not wipe it, and the metadata columns must still be updated.
    @Test
    fun foldPreservesFtsContentAcrossMetadataChange() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        // Simulate the OCR pipeline writing extracted text into the FTS content column.
        rig.db.ftsDao().replaceDocument(
            docId = docId,
            fileName = "a.pdf",
            title = null,
            content = "quarterly electricity invoice for acme corporation",
            tags = null,
            correspondent = null,
            docType = null,
        )
        rig.db.ftsDao().searchDocIds("electricity") shouldContainExactly listOf(docId)

        // A metadata op (tag add + prop set) followed by a fold must not wipe the content.
        val writer = rig.writer("dev-main")
        rig.clock.advance()
        writer.commit(
            listOf(
                PendingOp.tagAdd(docId, "receipt"),
                PendingOp.propSet(docId, DocProperty.CORRESPONDENT, "acme"),
            ),
        )
        rig.indexer.refreshFold()

        // Content still searchable.
        rig.db.ftsDao().searchDocIds("electricity") shouldContainExactly listOf(docId)
        rig.db.ftsDao().contentForDoc(docId) shouldBe "quarterly electricity invoice for acme corporation"
        // Metadata columns updated by the fold.
        rig.db.ftsDao().searchDocIds("receipt") shouldContainExactly listOf(docId)
        rig.db.ftsDao().searchDocIds("acme") shouldContainExactly listOf(docId)
    }

    // 13. Same guarantee via a full rescan (rescan reuses applyFold): content survives.
    @Test
    fun rescanPreservesFtsContent() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        rig.db.ftsDao().replaceDocument(
            docId = docId,
            fileName = "a.pdf",
            title = null,
            content = "boil water add pasta and salt",
            tags = null,
            correspondent = null,
            docType = null,
        )

        val writer = rig.writer("dev-main")
        rig.clock.advance()
        writer.commit(listOf(PendingOp.tagAdd(docId, "recipe")))
        rig.indexer.rescan()

        rig.db.ftsDao().searchDocIds("pasta") shouldContainExactly listOf(docId)
        rig.db.ftsDao().searchDocIds("recipe") shouldContainExactly listOf(docId)
    }

    @Test
    fun trashResurrectionPreferredFile() = runTest {
        rig.writePdf("a.pdf", "alpha")
        rig.indexer.rescan()
        val docId = rig.db.documentDao().getByPath("a.pdf")!!.docId

        // Journal says trashed with origPath a.pdf, but the file is still present on disk.
        val writer = rig.writer("dev-main")
        rig.clock.advance()
        writer.commit(listOf(PendingOp.docTrash(docId, "a.pdf")))

        val report = rig.indexer.rescan()
        report.resurrected.any { it.docId == docId }.shouldBeTrue()
        val row = rig.db.documentDao().getByDocId(docId)!!
        row.trashed.shouldBeFalse()
        row.missing.shouldBeFalse()
    }
}

private fun ImportResult.shouldBeInstanceOfImported() {
    check(this is ImportResult.Imported) { "expected Imported but was $this" }
}
