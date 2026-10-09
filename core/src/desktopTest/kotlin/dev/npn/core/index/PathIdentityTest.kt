package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Which row holds which path. relPath is unique: a row whose path now holds other bytes, or a
 * hidden row in a live document's way, gives the path up, and a hidden document comes back only
 * where the rescan finds its own bytes.
 */
class PathIdentityTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() = rig.close()

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

    /**
     * A live row still names its path after its file went outside the app, until a rescan notices:
     * an import under that name lands beside it, and leaves the row for the rescan to find missing.
     */
    @Test
    fun anImportLandsBesideALiveRowWhoseFileWentUnnoticed() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val gone = rig.fs.sha256("inbox/scan.pdf")
            val row = rig.db.documentDao().getByDocId(gone)
            rig.deleteFile("inbox/scan.pdf")

            val imported =
                rig.importService.importBytes("bravo".encodeToByteArray(), "scan.pdf") as ImportResult.Imported

            imported.relPath shouldBe "inbox/scan (2).pdf"
            rig.db
                .documentDao()
                .rowAtPath(imported.relPath)
                ?.docId shouldBe imported.docId
            rig.db.documentDao().getByDocId(gone) shouldBe row
            val report = rig.rescan()
            report.duplicates.shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(gone)
                .shouldNotBeNull()
                .missing
                .shouldBeTrue()
            rig.db
                .documentDao()
                .rowAtPath(imported.relPath)
                ?.docId shouldBe imported.docId
        }

    /** A row write that fails after the file moved in takes the file back out: a failed import leaves no file. */
    @Test
    fun anImportWhoseRowCannotBeWrittenLeavesNoFileInTheLibrary() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            // A live row takes the target the moment the file lands there, so the import's row write fails.
            val racing =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun move(
                        fromRelPath: String,
                        toRelPath: String,
                    ) {
                        rig.fs.move(fromRelPath, toRelPath)
                        if (!NpnPaths.isInternal(toRelPath)) rig.db.documentDao().upsert(holderAt(toRelPath))
                    }
                }
            val imports =
                ImportService(racing, rig.db, rig.deviceDb.ocrQueueDao(), rig.clock, fieldTypes = { emptyMap() })

            assertFailsWith<IllegalStateException> { imports.importBytes("bravo".encodeToByteArray(), "scan.pdf") }

            rig.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .map { it.relPath } shouldContainExactly listOf("a.pdf")
            rig.db
                .documentDao()
                .getByDocId(sha256Bytes("bravo".encodeToByteArray()))
                .shouldBeNull()
        }

    /** A live row at [relPath] whose file is not there. */
    private fun holderAt(relPath: String) =
        DocumentEntity(
            docId = "f".repeat(64),
            relPath = relPath,
            fileName = NpnPaths.fileName(relPath),
            size = 1,
            mtime = 1,
            addedAt = 1,
        )

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

    /** Only a document's own bytes bring it back: another file at its old path is another document. */
    @Test
    fun anotherFileAtATrashedDocumentsPathLeavesItInTheTrash() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val trashed = hide("inbox/scan.pdf") { PendingOp.docTrash(it, "inbox/scan.pdf") }

            rig.writePdf("inbox/scan.pdf", "bravo")
            val resurrected = ArrayList<Resurrected>()
            val report = rig.rescan { resurrected += it }

            resurrected.shouldBeEmpty()
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe rig.fs.sha256("inbox/scan.pdf")
            assertStillOnlyTrashed(trashed, "inbox/scan.pdf", report)
        }

    /**
     * Nor does another document's file moved onto a trashed document's old path take it out of the
     * trash, even with the size and mtime the trashed document's file had: a hidden row's stored stat
     * is no evidence that the file at its old path is still its own.
     */
    @Test
    fun anotherDocumentMovedOntoATrashedDocumentsPathLeavesItInTheTrash() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.writePdf("q.pdf", "bravo")
            rig.rescan()
            val moved = rig.fs.sha256("q.pdf")
            val trashed = hide("inbox/scan.pdf") { PendingOp.docTrash(it, "inbox/scan.pdf") }
            val trashedRow =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            rig.fs.stat("q.pdf")?.size shouldBe trashedRow.size
            Files.setLastModifiedTime(rig.libRoot.resolve("q.pdf"), FileTime.fromMillis(trashedRow.mtime))

            rig.moveFile("q.pdf", "inbox/scan.pdf")
            val report = rig.rescan()

            report.moved shouldBe 1
            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe moved
            assertStillOnlyTrashed(trashed, "inbox/scan.pdf", report)
        }

    /**
     * Another device's trash reaches a rescan together with a new file under the trashed document's
     * name: the rescan reads the trash from the journal it folds, so the document is only trashed.
     */
    @Test
    fun aTrashArrivingWithAnotherFileAtItsPathLeavesTheDocumentOnlyTrashed() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val trashed = rig.fs.sha256("inbox/scan.pdf")

            rig.clock.advance()
            rig.writer("dev-main").commit(listOf(PendingOp.docTrash(trashed, "inbox/scan.pdf")))
            rig.deleteFile("inbox/scan.pdf")
            rig.writePdf("inbox/scan.pdf", "bravo")
            val report = rig.rescan()

            rig.db
                .documentDao()
                .rowAtPath("inbox/scan.pdf")
                ?.docId shouldBe rig.fs.sha256("inbox/scan.pdf")
            assertStillOnlyTrashed(trashed, "inbox/scan.pdf", report)
        }

    /** A document whose file is replaced in place is missing, the new file offered as its edit. */
    @Test
    fun aFileReplacedInPlaceLeavesItsDocumentMissingWithAPathMatch() =
        runTest {
            rig.writePdf("inbox/scan.pdf", "alpha")
            rig.rescan()
            val replaced = rig.fs.sha256("inbox/scan.pdf")

            rig.deleteFile("inbox/scan.pdf")
            rig.writePdf("inbox/scan.pdf", "bravo")
            val report = rig.rescan()

            val now = rig.fs.sha256("inbox/scan.pdf")
            rig.db
                .documentDao()
                .getByDocId(replaced)
                .shouldNotBeNull()
                .missing
                .shouldBeTrue()
            report.relinkCandidates
                .filter {
                    it.reason == RelinkPairing.PATH_MATCH
                }.map { it.oldDocId to it.newDocId } shouldContainExactly
                listOf(replaced to now)
        }

    /**
     * [docId] is in the trash and nowhere else: not missing, trashed from [origPath], and no relink
     * candidate of the rescan that produced [report] would carry its metadata onto another file.
     */
    private suspend fun assertStillOnlyTrashed(
        docId: String,
        origPath: String,
        report: ScanReport,
    ) {
        val row =
            rig.db
                .documentDao()
                .getByDocId(docId)
                .shouldNotBeNull()
        row.trashed.shouldBeTrue()
        row.missing.shouldBeFalse()
        row.trashedOrigPath shouldBe origPath
        rig.db
            .documentDao()
            .observeMissing()
            .first()
            .map { it.docId } shouldNotContain docId
        report.relinkCandidates.map { it.oldDocId } shouldNotContain docId
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
            val reports = List(2) { rig.rescan { found -> resurrected += found } }

            resurrected.shouldBeEmpty()
            reports.flatMap { it.relinkCandidates }.map { it.oldDocId } shouldNotContain deleted
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
