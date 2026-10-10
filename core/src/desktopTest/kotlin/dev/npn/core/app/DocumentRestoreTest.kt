package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * A deleted or trashed document whose file comes back: the decision to show it again is recorded
 * in the journal, so the next fold, after any edit, does not hide it again.
 */
class DocumentRestoreTest {
    private lateinit var rig: AppSessionRig

    /** Run [block] over a fresh session, closed before the test's scope ends. */
    private fun withRig(block: suspend () -> Unit) =
        runTest {
            rig = AppSessionRig.create(backgroundScope)
            try {
                block()
            } finally {
                rig.close()
            }
        }

    /** Neither deleted nor trashed once imported again, and still so after an edit and a rescan. */
    @Test
    fun aReImportedDeletedDocumentStaysVisibleAfterAnEdit() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)

            rig.session.ingest
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
            rig.session.documents
                .observeDoc(id)
                .first()
                .shouldNotBeNull()
                .doc.fields[FieldKeys.TITLE] shouldBe "Back again"
            // And a rescan agrees: the journal, not this scan, now says it is live.
            rig.session.rescan()
            assertVisible(id)
        }

    /** Delete and restore are last-writer-wins: a delete after the restore hides it again. */
    @Test
    fun aDeleteNewerThanTheRestoreWins() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)
            rig.session.ingest
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
     * and records that, so an edit afterwards does not hide it again.
     */
    @Test
    fun aFileBroughtBackLongAfterItsDeleteIsRestoredForGood() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            deleteForGood(id)

            rig.clock.advance(2 * DAY)
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

            assertVisible(id)
        }

    @Test
    fun aTrashedFileBroughtBackLongAfterTheTrashIsRestoredForGood() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)

            rig.clock.advance(2 * DAY)
            rig.writePdf("a.pdf", bytes)
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

    @Test
    fun aTrashedFileBackRightAfterTheTrashIsShownButNotRestoredForGood() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            val objects = rig.journalObjectCount()

            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()

            rig.journalObjectCount() shouldBe objects
            assertVisible(id)
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
        withRig {
            val first = importScan("first")
            rig.session.meta.setField(first, FieldKeys.TITLE, "First scan")
            deleteForGood(first)

            rig.clock.advance(2 * DAY)
            val second = importScan("second")
            rig.session.rescan()
            rig.session.rescan()

            journalOps()
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

    /** Trash [id], then delete it for good from the trash, as the user does. */
    private suspend fun deleteForGood(id: String) {
        rig.session.docLifecycle.trashDoc(id)
        rig.session.docLifecycle.deleteDocPermanently(id)
    }

    /** Import a scan of [text] as `scan.pdf`, the name every scan gets; returns its document id. */
    private suspend fun importScan(text: String): String {
        val bytes = PdfFixtures.embeddedTextPdf(text)
        rig.session.ingest
            .importPdfBytes(bytes, "scan.pdf")
            .shouldBeInstanceOf<ImportOutcome.Imported>()
        return sha256Bytes(bytes)
    }

    /** Every published journal object's text. */
    private suspend fun journalOps(): List<String> =
        rig.fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            .map { rig.fs.readBytes(it.relPath).decodeToString() }

    private suspend fun assertVisible(id: String) {
        val row =
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
        row.deleted.shouldBeFalse()
        row.trashed.shouldBeFalse()
        rig.session.documents
            .observeDoc(id)
            .first()
            .shouldNotBeNull()
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
        const val SCAN_PATH = "inbox/scan.pdf"
    }
}
