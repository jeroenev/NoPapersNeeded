package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.NpnPaths
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.index.indexTest
import dev.npn.core.journal.PendingOp
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test

/** Deleting a document for good removes its own bytes and nothing else. */
class PermanentDeleteTest {
    /**
     * A document restored since its "Delete forever" was asked for is not the trash's any more: the
     * delete leaves it live, with its file, and journals nothing.
     */
    @Test
    fun deletingForGoodADocumentRestoredSinceLeavesItAndItsFile() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("restored before the delete"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            rig.session.docLifecycle.restoreDoc(id)
            val objects = rig.journalObjectCount()

            rig.session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.NOT_IN_TRASH

            rig.fs.sha256("a.pdf") shouldBe id
            rig.journalObjectCount() shouldBe objects
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .isLive
                .shouldBeTrue()
        }

    /**
     * An import of the document's bytes brings it back, in its own file, after its deletion was
     * committed and before that file was deleted. The file is the document's again, and stays.
     */
    @Test
    fun aDocumentAnImportBroughtBackBeforeItsFileWentKeepsTheFile() =
        runTest {
            val importing = BeforeTrashListing()
            AppSessionRig.create(backgroundScope, files = importing::over).use { rig ->
                val bytes = PdfFixtures.embeddedTextPdf("deleted, and imported again")
                rig.writePdf("a.pdf", bytes)
                val id = trashedInPlace(rig, "a.pdf")
                rig.session.work.drainOcr()
                var imported: ImportOutcome? = null
                importing.next = { imported = rig.session.importPdfBytes(bytes, "a.pdf") }

                rig.session.docLifecycle.deleteDocPermanently(id)

                imported shouldBe ImportOutcome.Imported(id, "a.pdf")
                rig.fs.sha256("a.pdf") shouldBe id
                rig.session.rescan()
                val row =
                    rig.db
                        .documentDao()
                        .getByDocId(id)
                        .shouldNotBeNull()
                row.deleted.shouldBeFalse()
                row.relPath shouldBe "a.pdf"
            }
        }

    /**
     * A rescan between the deletion and the removal of the file finds the document's bytes still at
     * its path and shows it again for now, writing nothing to the journal so soon after the delete.
     * That is no return: the file still goes, and the document stays deleted.
     */
    @Test
    fun aRescanBeforeTheFileWentDoesNotKeepIt() =
        runTest {
            val rescanning = BeforeTrashListing()
            AppSessionRig.create(backgroundScope, files = rescanning::over).use { rig ->
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("deleted, its file still in place"))
                val id = trashedInPlace(rig, "a.pdf")
                rescanning.next = { rig.session.rescan() }

                rig.session.docLifecycle.deleteDocPermanently(id)

                rig.exists("a.pdf").shouldBeFalse()
                rig.session.rescan()
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
            }
        }

    /**
     * An import took the old path of a document being deleted for good, after the deletion and before
     * the deletion read the file there. That file is the import's, as its row says, and is not read
     * for the deleted document's bytes. The two files have the same size, so only the import's row,
     * not a size that differs, spares the file a read.
     */
    @Test
    fun deletingForGoodReadsNoFileAnotherDocumentHoldsAtTheOldPath() =
        indexTest { rig ->
            val recorded = WatchedFileSystem(rig.fs).also { it.db = rig.db }
            val importing = BeforeTrashListing()
            val files = importing.over(recorded)
            val coordinator = rig.coordinator()
            val lifecycle =
                rig.docLifecycle(coordinator, files)
            rig.writePdf("inbox/x.pdf", "deleted for good")
            coordinator.rescan()
            val deleted = rig.fs.sha256("inbox/x.pdf")
            lifecycle.trashDoc(deleted)
            importing.next = {
                rig.writePdf("inbox/x.pdf", "imported in time")
                coordinator.rescan()
                recorded.clear()
            }

            lifecycle.deleteDocPermanently(deleted)

            recorded.calls("sha256").filterNot { it.contains(NpnPaths.TRASH_DIR) }.shouldBeEmpty()
            rig.fs.readBytes("inbox/x.pdf").decodeToString() shouldBe "imported in time"
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory }
                .shouldBeEmpty()
        }

    /**
     * As above, but a sync tool has since put the deleted document's bytes back over the import's
     * file. The import's row names the path with a stat the file no longer has, so it proves nothing:
     * the file is read, holds the deleted document's bytes, and goes, so no rescan brings it back.
     */
    @Test
    fun deletingForGoodRemovesItsBytesOverAnotherDocumentsFileAtTheOldPath() =
        indexTest { rig ->
            val importing = BeforeTrashListing()
            val coordinator = rig.coordinator()
            val lifecycle = rig.docLifecycle(coordinator, importing.over(rig.fs))
            rig.writePdf("inbox/x.pdf", "deleted for good")
            coordinator.rescan()
            val deleted = rig.fs.sha256("inbox/x.pdf")
            lifecycle.trashDoc(deleted)
            importing.next = {
                rig.writePdf("inbox/x.pdf", "imported in time")
                coordinator.rescan()
                val imported = rig.db.documentDao().getByRelPath("inbox/x.pdf").shouldNotBeNull()
                rig.writePdf("inbox/x.pdf", "deleted for good")
                Files.setLastModifiedTime(
                    rig.libRoot.resolve("inbox/x.pdf"),
                    FileTime.fromMillis(imported.mtime + 10_000),
                )
            }

            lifecycle.deleteDocPermanently(deleted) shouldBe PermanentDeleteOutcome.DELETED

            rig.exists("inbox/x.pdf").shouldBeFalse()
        }

    /**
     * The document that took the old path is in the trash itself now, its file left in place (another
     * device's trash, its file move not synced yet), and a tool that keeps mtimes has put the deleted
     * document's bytes there with that file's size and mtime. A hidden row's stat proves nothing, so
     * the file is read, holds the deleted document's bytes, and goes.
     */
    @Test
    fun deletingForGoodTakesNoHiddenRowsStatAsProofOfTheFileAtTheOldPath() =
        indexTest { rig ->
            val importing = BeforeTrashListing()
            val coordinator = rig.coordinator()
            val lifecycle = rig.docLifecycle(coordinator, importing.over(rig.fs))
            rig.writePdf("inbox/x.pdf", "deleted for good")
            coordinator.rescan()
            val deleted = rig.fs.sha256("inbox/x.pdf")
            lifecycle.trashDoc(deleted)
            importing.next = {
                rig.writePdf("inbox/x.pdf", "imported in time")
                coordinator.rescan()
                val imported = rig.db.documentDao().getByRelPath("inbox/x.pdf").shouldNotBeNull()
                coordinator.commitAndApply(listOf(PendingOp.docTrash(imported.docId, "inbox/x.pdf")))
                rig.db
                    .documentDao()
                    .getByRelPath("inbox/x.pdf")
                    .shouldNotBeNull()
                    .trashed
                    .shouldBeTrue()
                rig.writePdf("inbox/x.pdf", "deleted for good")
                Files.setLastModifiedTime(rig.libRoot.resolve("inbox/x.pdf"), FileTime.fromMillis(imported.mtime))
            }

            lifecycle.deleteDocPermanently(deleted) shouldBe PermanentDeleteOutcome.DELETED

            rig.exists("inbox/x.pdf").shouldBeFalse()
        }

    /**
     * A trashed document whose trash copy is gone gave its old path up: the file there now is another
     * one, not yet indexed. Deleting the document for good deletes the document, not that file.
     */
    @Test
    fun deletingForGoodADocumentWithNoTrashCopyLeavesAnotherFileAtItsOldPath() =
        appSessionTest { rig ->
            rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
            rig.session.rescan()
            val trashed = rig.docId("inbox/invoice.pdf")
            rig.session.docLifecycle.trashDoc(trashed)
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory }
                .forEach { rig.fs.delete(it.relPath) }
            val newcomer = PdfFixtures.embeddedTextPdf("newcomer")
            rig.writePdf("inbox/invoice.pdf", newcomer)

            rig.session.docLifecycle.deleteDocPermanently(trashed)

            rig.fs.readBytes("inbox/invoice.pdf") shouldBe newcomer
            rig.db
                .documentDao()
                .getByDocId(trashed)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
            // The journal holds the deletion: a rescan, which folds it, keeps the document gone
            // and indexes the newcomer.
            rig.session.rescan()
            rig.db
                .documentDao()
                .getByDocId(trashed)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
            rig.db
                .documentDao()
                .getByRelPath("inbox/invoice.pdf")
                ?.docId shouldBe rig.fs.sha256("inbox/invoice.pdf")
        }

    /**
     * A sync tool put the bytes of a document in the trash back at its path, and no rescan has looked
     * since. Deleting the document for good removes both copies, so no rescan shows it again.
     */
    @Test
    fun deletingForGoodRemovesTheCopyInTheTrashAndTheOneAtItsPath() =
        appSessionTest { rig ->
            val bytes = PdfFixtures.embeddedTextPdf("in the trash, and back at its path")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            rig.writePdf("a.pdf", bytes)

            rig.session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.DELETED

            rig.exists("a.pdf").shouldBeFalse()
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory }
                .shouldBeEmpty()
            rig.session.rescan()
            rig.fs
                .listAllIndexable()
                .filter { !it.isDirectory }
                .shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
        }

    /**
     * The provider refuses to delete what the trash holds. The documents are deleted all the same,
     * as their tombstones say, and the files left behind are reported, one by one and in bulk.
     */
    @Test
    fun aFileThatCouldNotBeRemovedIsReportedAndTheDocumentStaysDeleted() =
        appSessionTest(files = refusingTrashDeletes::over) { rig ->
            val names = listOf("a", "b", "c")
            names.forEach { rig.writePdf("$it.pdf", PdfFixtures.embeddedTextPdf("file of $it")) }
            rig.session.rescan()
            val ids = names.map { rig.docId("$it.pdf") }
            ids.forEach { rig.session.docLifecycle.trashDoc(it) }

            rig.session.docLifecycle.deleteDocPermanently(ids[0]) shouldBe PermanentDeleteOutcome.FILE_LEFT
            rig.session.docLifecycle.deleteDocsPermanently(ids.drop(1)) shouldBe
                PermanentDeleteTally(failed = 0, filesLeft = 2)

            ids.forEach {
                rig.db
                    .documentDao()
                    .getByDocId(it)
                    .shouldNotBeNull()
                    .deleted
                    .shouldBeTrue()
            }
            rig.fs.listAllUnder(NpnPaths.TRASH_DIR).filter { !it.isDirectory } shouldHaveSize 3
        }

    /**
     * Two documents deleted for good, and the second one's bytes come back under the first one's old
     * name. The second one's row takes that path from the first one's, which waits at its sentinel,
     * and the rescan, and every one after it, goes through.
     */
    @Test
    fun aDeletedDocumentsBytesAtAnotherDeletedDocumentsPathAreRescanned() =
        appSessionTest { rig ->
            val second = PdfFixtures.embeddedTextPdf("deleted second")
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("deleted first"))
            rig.writePdf("b.pdf", second)
            rig.session.rescan()
            val first = rig.docId("a.pdf")
            val secondId = rig.docId("b.pdf")
            for (id in listOf(first, secondId)) {
                rig.session.docLifecycle.trashDoc(id)
                rig.session.docLifecycle.deleteDocPermanently(id) shouldBe PermanentDeleteOutcome.DELETED
            }
            rig.writePdf("a.pdf", second)

            rig.session.rescan()
            rig.session.rescan()

            val dao = rig.db.documentDao()
            dao.getByRelPath("a.pdf")?.docId shouldBe secondId
            dao.getByDocId(first).shouldNotBeNull().relPath shouldBe missingSentinel(first)
        }

    /** A provider that refuses to delete anything in the trash. */
    private val refusingTrashDeletes = FileSystemFaults().apply { refuseDeletesUnder = "${NpnPaths.TRASH_DIR}/" }

    /**
     * Index [path] and trash its document with its file left where it was, as another device's trash
     * stands here when its journal object arrives before its file move; returns the document's id.
     */
    private suspend fun trashedInPlace(
        rig: AppSessionRig,
        path: String,
    ): String {
        rig.session.rescan()
        val id = rig.docId(path)
        rig.session.docLifecycle.trashDoc(id)
        val entry =
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .single { !it.isDirectory }
                .relPath
        rig.fs.move(entry, path)
        return id
    }
}
