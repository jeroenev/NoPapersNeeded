package dev.npn.core.index

import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
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

            rig.maintenance.resolveDuplicate(docId, "copies/a-copy.pdf", keepCanonical = true)
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
            rig.db
                .ocrQueueDao()
                .nextPending()!!
                .docId shouldBe imported.docId

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
                    val entries =
                        java.nio.file.Files
                            .list(rig.libRoot.resolve(".npn/tmp"))
                            .use { it.count() }
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
    fun deviceIdForkOnForeignSegment() =
        runTest {
            val kv = rig.db.keyValueDao()
            val identity = DeviceIdentity(kv)
            val deviceId = identity.getOrCreate()

            // This installation writes a segment normally, populating local journal state.
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId =
                rig.db
                    .documentDao()
                    .rowAtPath("a.pdf")!!
                    .docId
            val localState = DbLocalJournalState(kv)
            val writer =
                SegmentWriter(
                    store = rig.fsSegmentStore,
                    deviceId = deviceId,
                    hlcClock = HlcClock(deviceId, rig.clock),
                    localState = localState,
                )
            rig.clock.advance()
            writer.commit(listOf(PendingOp.tagAdd(docId, "mine")))
            // Precondition: local state now knows a seq and at least one own segment.
            (localState.getLastSeq() != null).shouldBeTrue()
            localState.getKnownOwnSegments().isNotEmpty().shouldBeTrue()

            // Simulate a foreign installation sharing this device id: a segment appears in the same
            // device directory that our local state does not know about.
            rig.clock.advance()
            rig.fsSegmentStore.writeSegment(
                deviceId,
                dev.npn.core.journal.SegmentRef
                    .plainName(999L),
                "",
            )

            val newId = identity.ensureSingleWriter(writer, localState)
            (newId != deviceId).shouldBeTrue()
            kv.get(DeviceIdentity.KEY_DEVICE_ID) shouldBe newId
            // Regression: fork must clear the old device's local bookkeeping.
            localState.getLastSeq() shouldBe null
            localState.getKnownOwnSegments() shouldBe emptySet()
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
            rig.db.ftsDao().replaceDocument(
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

            rig.db.ftsDao().replaceDocument(
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

            val report = rig.rescan()
            report.resurrected.any { it.docId == docId }.shouldBeTrue()
            val row = rig.db.documentDao().getByDocId(docId)!!
            row.trashed.shouldBeFalse()
            row.missing.shouldBeFalse()
        }
}

private fun ImportResult.shouldBeInstanceOfImported() {
    check(this is ImportResult.Imported) { "expected Imported but was $this" }
}
