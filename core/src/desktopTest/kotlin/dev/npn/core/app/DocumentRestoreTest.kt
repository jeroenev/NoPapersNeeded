package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.requireDoc
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.journalObjectTexts
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.test.Test

/**
 * A deleted or trashed document whose file comes back: the decision to show it again is recorded
 * in the journal, so the next fold, after any edit, does not hide it again. A deleted document's
 * return is a `doc-undelete`, which this build reads but does not write
 * ([JournalOp.DocUndelete.WRITTEN]): the tests of that record turn the gate on, and the others pin
 * what the default writes.
 */
class DocumentRestoreTest {
    private lateinit var rig: AppSessionRig

    /**
     * Run [block] over a fresh session, closed before the test's scope ends, which records a deleted
     * document's return when [writeUndelete].
     */
    private fun withRig(
        writeUndelete: Boolean = JournalOp.DocUndelete.WRITTEN,
        block: suspend () -> Unit,
    ) = appSessionTest(writeUndelete = writeUndelete) {
        rig = it
        block()
    }

    /** Neither deleted nor trashed once imported again, and still so after an edit and a rescan. */
    @Test
    fun aReImportedDeletedDocumentStaysVisibleAfterAnEdit() =
        withRig(writeUndelete = true) {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)

            rig.session
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
            rig.session.documents
                .requireDoc(id)
                .doc.fields[FieldKeys.TITLE] shouldBe "Back again"
            // And a rescan agrees: the journal, not this scan, now says it is live.
            rig.session.rescan()
            assertVisible(id)
        }

    /** Delete and restore are last-writer-wins: a delete after the restore hides it again. */
    @Test
    fun aDeleteNewerThanTheRestoreWins() =
        withRig(writeUndelete = true) {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            rig.session
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()

            deleteForGood(id)
            rig.session.rescan()

            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
        }

    /**
     * A sync tool that puts a deleted file back long after the delete: the rescan prefers the file,
     * and once it has been back for the settle window too, records that, so an edit afterwards does
     * not hide it again.
     */
    @Test
    fun aFileBroughtBackLongAfterItsDeleteIsRestoredForGood() =
        withRig(writeUndelete = true) {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)

            rig.clock.advance(2 * DAY)
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            rig.clock.advance(DAY + 1)
            rig.session.rescan()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
        }

    /**
     * A trashed document's file back with another mtime than its trash took it with may still be a
     * copy a transport that keeps no mtimes has yet to take away, so it waits out the window as any
     * return does; once past it, the rescan records the return, and an edit no longer hides it.
     */
    @Test
    fun aTrashedFileBroughtBackWithANewMtimeIsRestoredForGoodAfterTheWindow() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val stat = rig.fs.stat("a.pdf").shouldNotBeNull()
            rig.session.docLifecycle.trashDoc(id)

            rig.writePdf("a.pdf", bytes)
            Files.setLastModifiedTime(rig.libRoot.resolve("a.pdf"), FileTime.fromMillis(stat.mtimeMillis + 60_000L))
            rig.session.rescan()
            rig.clock.advance(DAY + 1)
            rig.session.rescan()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
        }

    /**
     * Right after a delete, a file still in place may be one a sync tool has not removed yet, so
     * the rescan shows it but records nothing: the delete still stands for every other replica.
     */
    @Test
    fun aFileStillThereRightAfterItsDeleteIsNotRestoredForGood() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            val objects = rig.journalObjectCount()

            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()

            rig.journalObjectCount() shouldBe objects
            assertVisible(id)
        }

    /**
     * One back with the stat its trash took it with may be the copy a sync tool has yet to take away:
     * it stays in the trash, with nothing recorded, however long rescans find it.
     */
    @Test
    fun aTrashedFileBackWithItsTrashsStatStaysInTheTrash() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val stat = rig.fs.stat("a.pdf").shouldNotBeNull()
            rig.session.docLifecycle.trashDoc(id)
            val objects = rig.journalObjectCount()

            rig.writePdf("a.pdf", bytes)
            Files.setLastModifiedTime(rig.libRoot.resolve("a.pdf"), FileTime.fromMillis(stat.mtimeMillis))
            rig.session.rescan()
            rig.clock.advance(2 * DAY)
            rig.session.rescan()

            rig.journalObjectCount() shouldBe objects
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /**
     * By default an import of a deleted document's bytes records nothing, as builds before
     * `doc-undelete` did: the import stands, and the document shows until a fold of the journal
     * hides it again, and again at each rescan that finds its file.
     */
    @Test
    fun aReImportedDeletedDocumentWritesNoUndeleteByDefault() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            val objects = rig.journalObjectCount()

            val imported =
                rig.session
                    .importPdfBytes(bytes, "a.pdf")
                    .shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.journalObjectCount() shouldBe objects
            rig.exists(imported.relPath).shouldBeTrue()
            assertVisible(id)
            rig.session.rescan()
            assertVisible(id)
            rig.fs
                .journalObjectTexts()
                .filter { it.contains(JournalOp.DocUndelete.WIRE) }
                .shouldBeEmpty()
        }

    /** Nor does a rescan finding a deleted document's file long after the delete, by default. */
    @Test
    fun aFileBroughtBackLongAfterItsDeleteWritesNoUndeleteByDefault() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            val objects = rig.journalObjectCount()

            rig.clock.advance(2 * DAY)
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()

            rig.journalObjectCount() shouldBe objects
            rig.fs
                .journalObjectTexts()
                .filter { it.contains(JournalOp.DocUndelete.WIRE) }
                .shouldBeEmpty()
            assertVisible(id)
        }

    /** A `doc-undelete` another device wrote still brings the document back here, for good. */
    @Test
    fun anUndeleteFromAnotherDeviceIsFoldedByDefault() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)

            rig.clock.advance(2 * DAY)
            rig.writePdf("a.pdf", bytes)
            rig
                .writer("other-installation")
                .commit(listOf(PendingOp.docUndelete(id), PendingOp.propSet(id, SystemProperty.STORAGE_PATH, "a.pdf")))
            rig.session.rescan()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
            rig.db
                .documentDao()
                .getByDocId(id)
                ?.relPath shouldBe "a.pdf"
        }

    /**
     * Two scans imported under one name: the second lands where the trashed first one was. The
     * file there is the second scan, which says nothing about the first.
     */
    @Test
    fun aScanImportedWhereATrashedScanWasLeavesThatOneInTheTrash() =
        withRig {
            val first = importScan("first")
            rig.session.meta.setField(first, FieldKeys.TITLE, "First scan")
            rig.session.docLifecycle.trashDoc(first)
            val second = importScan("second")

            rig.session.rescan()

            rig.db
                .documentDao()
                .getByDocId(second)
                ?.relPath shouldBe SCAN_PATH
            rig.db
                .documentDao()
                .getByDocId(first)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
            rig.session.meta.setField(second, FieldKeys.TITLE, "Second scan")
            rig.session.rescan()
            assertVisible(second)
        }

    /** Nor does it, long after the first was deleted for good, write the first one's return. */
    @Test
    fun aScanImportedLongAfterADeleteWhereTheDeletedScanWasLeavesItDeleted() =
        withRig(writeUndelete = true) {
            val first = importScan("first")
            rig.session.meta.setField(first, FieldKeys.TITLE, "First scan")
            deleteForGood(first)

            rig.clock.advance(2 * DAY)
            val second = importScan("second")
            rig.session.rescan()
            rig.session.rescan()

            rig.fs
                .journalObjectTexts()
                .filter { it.contains(JournalOp.DocUndelete.WIRE) || it.contains(JournalOp.DocRestore.WIRE) }
                .shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(first)
                .shouldNotBeNull()
                .deleted
                .shouldBeTrue()
            assertVisible(second)
        }

    /**
     * A trash another device published says the document came from a journal bucket. No build
     * writes that, but a peer's event is read as it is: the restore puts the document in the inbox,
     * where a document can be, and the journal still reads.
     */
    @Test
    fun aRestoreNeverFollowsATrashedPathIntoTheLibrarysOwnFolder() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            val bucketPath = JournalPaths.objectPath("ab" + "0".repeat(62))
            // That device moved the file into the trash, and the move synced with its event.
            val trashed = rig.libRoot.resolve(NpnPaths.trashPath("a.pdf", id, rig.clock.nowMillis()))
            Files.createDirectories(trashed.parent)
            Files.move(rig.libRoot.resolve("a.pdf"), trashed)
            rig
                .writer("other-installation")
                .commit(listOf(PendingOp.docTrash(id, bucketPath)))
            rig.session.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashedOrigPath shouldBe bucketPath

            rig.session.docLifecycle.restoreDoc(id)

            rig.exists(bucketPath).shouldBeFalse()
            rig.exists("inbox/a.pdf").shouldBeTrue()
            rig.session.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/a.pdf"
            assertVisible(id)
        }

    /**
     * A document whose file another one replaced waits under `.npn/missing/`; trashed from there and
     * restored, it comes back in the inbox, not at that placeholder.
     */
    @Test
    fun aDocumentTrashedWhileMissingIsRestoredToTheInbox() =
        withRig {
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.session.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .relPath shouldStartWith "${NpnPaths.NPN_DIR}/"

            rig.session.docLifecycle.trashDoc(id)
            rig.session.docLifecycle.restoreDoc(id)

            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/a.pdf"
            rig.fs
                .journalObjectTexts()
                .filter { NpnPaths.NPN_DIR in it && SystemProperty.STORAGE_PATH.wire in it }
                .shouldBeEmpty()
        }

    /**
     * Once every device reads `doc-undelete` (their records list `keep-v1`; here, the only device),
     * a re-import of a deleted document records its return, whatever this build's default says.
     */
    @Test
    fun aReImportedDeletedDocumentIsUndeletedOnceEveryDeviceReadsTheOp() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            rig.session.keepGate
                .refresh()
                .shouldBeTrue()

            rig.session
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
            rig.fs
                .journalObjectTexts()
                .any { JournalOp.DocUndelete.WIRE in it }
                .shouldBeTrue()
        }

    /** Trash [id], then delete it for good from the trash, as the user does. */
    private suspend fun deleteForGood(id: String) {
        rig.session.docLifecycle.trashDoc(id)
        rig.session.docLifecycle.deleteDocPermanently(id)
    }

    /** Import a scan of [text] as `scan.pdf`, the name every scan gets; returns its document id. */
    private suspend fun importScan(text: String): String {
        val bytes = PdfFixtures.embeddedTextPdf(text)
        rig.session
            .importPdfBytes(bytes, "scan.pdf")
            .shouldBeInstanceOf<ImportOutcome.Imported>()
        return sha256Bytes(bytes)
    }

    private suspend fun assertVisible(id: String) {
        val row =
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
        row.deleted.shouldBeFalse()
        row.trashed.shouldBeFalse()
        rig.session.documents
            .requireDoc(id)
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
        const val SCAN_PATH = "inbox/scan.pdf"
    }
}
