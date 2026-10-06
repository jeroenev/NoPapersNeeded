package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.Test

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
