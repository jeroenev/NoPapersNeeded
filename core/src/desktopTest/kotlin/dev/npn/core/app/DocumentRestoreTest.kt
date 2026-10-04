package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
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

    @Test
    fun aReImportedDeletedDocumentStaysVisibleAfterAnEdit() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.deleteDocPermanently(id)

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

    @Test
    fun aReImportedDocumentDeletedFromTheTrashIsNeitherDeletedNorTrashed() =
        withRig {
            val bytes = PdfFixtures.embeddedTextPdf("alpha")
            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()
            val id = rig.docId("a.pdf")
            rig.session.docLifecycle.trashDoc(id)
            rig.session.docLifecycle.deleteDocPermanently(id)

            rig.session.ingest
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()
            rig.session.meta.setField(id, FieldKeys.TITLE, "Back again")

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
            rig.session.docLifecycle.deleteDocPermanently(id)
            rig.session.ingest
                .importPdfBytes(bytes, "a.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.session.docLifecycle.deleteDocPermanently(id)
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
            rig.session.docLifecycle.deleteDocPermanently(id)

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
            rig.session.docLifecycle.deleteDocPermanently(id)
            val objects = rig.journalObjectCount()

            rig.writePdf("a.pdf", bytes)
            rig.session.rescan()

            rig.journalObjectCount() shouldBe objects
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
            .observeDoc(id)
            .first()
            .shouldNotBeNull()
    }

    private companion object {
        const val DAY = 24L * 60L * 60L * 1000L
    }
}
