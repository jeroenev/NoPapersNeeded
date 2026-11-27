package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.trashEntries
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Every way a document can be left live, its file gone, with its bytes only in the trash.
 *
 * A restore made on a device that had another device's trash event but not yet its file move takes
 * the file in place; the move then lands, and leaves the document's bytes only in the trash. A
 * rescan that finds the document missing brings that copy back, as long as the copy belongs to a
 * trash the journal has restored since, and only then. A trash or restore whose move landed and then
 * failed puts the file back, as one whose commit failed does.
 */
class StrandedTrashCopyTest {
    private lateinit var rig: AppSessionRig

    /** The trash, its restore and the file move as another device's sync delivers them, in that order. */
    @Test
    fun aCopyLeftInTheTrashByARestoreComesBackToItsPath() =
        appSessionTest {
            rig = it
            val id = importAlpha()
            val trashedAt = rig.clock.nowMillis()
            val peer = rig.writer("other-installation")
            peer.commit(listOf(PendingOp.docTrash(id, PATH)))
            rig.clock.advance(MINUTE)
            peer.commit(listOf(PendingOp.docRestore(id), PendingOp.propSet(id, SystemProperty.STORAGE_PATH, PATH)))
            // The trash's file move, which reached this device after the restore.
            val entry = NpnPaths.trashPath(PATH, id, trashedAt)
            rig.fs.move(PATH, entry)

            rig.session.rescan()

            rig.exists(PATH).shouldBeTrue()
            rig.exists(entry).shouldBeFalse()
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
            row.missing.shouldBeFalse()
            row.trashed.shouldBeFalse()
            row.relPath shouldBe PATH
        }

    /**
     * A copy of a trash later than the restore belongs to a trash whose event has not arrived yet:
     * bringing it back would undo that trash, so it stays, and the document shows as missing meanwhile.
     */
    @Test
    fun aCopyOfALaterTrashStaysInTheTrash() =
        appSessionTest {
            rig = it
            val id = importAlpha()
            val peer = rig.writer("other-installation")
            peer.commit(listOf(PendingOp.docTrash(id, PATH)))
            rig.clock.advance(MINUTE)
            peer.commit(listOf(PendingOp.docRestore(id), PendingOp.propSet(id, SystemProperty.STORAGE_PATH, PATH)))
            rig.clock.advance(MINUTE)
            // The peer trashes it again; its file move arrives here before its event.
            val entry = NpnPaths.trashPath(PATH, id, rig.clock.nowMillis())
            rig.fs.move(PATH, entry)

            rig.session.rescan()

            rig.exists(PATH).shouldBeFalse()
            rig.exists(entry).shouldBeTrue()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .missing
                .shouldBeTrue()
        }

    /**
     * A trash whose move into the trash lands and then fails publishes nothing and puts the file back:
     * left in the trash with no event, the document would be live and missing, its bytes in a trash
     * entry newer than any restore, which no rescan may bring back.
     */
    @Test
    fun aTrashWhoseMoveLandsAndThenFailsPutsTheFileBack() {
        val faults = FileSystemFaults()
        appSessionTest(files = faults::over) {
            rig = it
            val id = importAlpha()
            val before = lifecycleOf(id)
            val objects = rig.journalObjectCount()

            faults.failNext(FileSystemFaults.Point.AFTER_MOVE)
            assertFailsWith<IOException> { rig.session.docLifecycle.trashDoc(id) }

            rig.exists(PATH).shouldBeTrue()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.journalObjectCount() shouldBe objects
            lifecycleOf(id) shouldBe before
        }
    }

    /** A restore whose move out of the trash lands and then fails puts the file back in the trash. */
    @Test
    fun aRestoreWhoseMoveLandsAndThenFailsPutsTheFileBackInTheTrash() {
        val faults = FileSystemFaults()
        appSessionTest(files = faults::over) {
            rig = it
            val id = importAlpha()
            rig.session.docLifecycle.trashDoc(id)
            val before = lifecycleOf(id)

            faults.failNext(FileSystemFaults.Point.AFTER_MOVE, PATH)
            assertFailsWith<IOException> { rig.session.docLifecycle.restoreDoc(id) }

            rig.exists(PATH).shouldBeFalse()
            rig.fs.trashEntries() shouldHaveSize 1
            lifecycleOf(id) shouldBe before
        }
    }

    /**
     * A trash whose commit failed and whose file could not be put back leaves a copy no event
     * explains. A rescan leaves it for a day, as it would a trash whose event is still on its way,
     * and then brings it back.
     */
    @Test
    fun aCopyNoTrashEventExplainsComesBackAfterTheSettleWindow() {
        val faults = FileSystemFaults()
        appSessionTest(files = faults::over) {
            rig = it
            val id = importAlpha()
            faults.publishFailures = 1
            faults.failNext(FileSystemFaults.Point.BEFORE_MOVE, PATH)
            assertFailsWith<VerifiedPublicationFailedException> { rig.session.docLifecycle.trashDoc(id) }
            rig.exists(PATH).shouldBeFalse()

            rig.session.rescan()
            rig.clock.advance(DAY / 2)
            rig.session.rescan()
            rig.exists(PATH).shouldBeFalse()
            rig.fs.trashEntries() shouldHaveSize 1

            rig.clock.advance(DAY / 2 + 1)
            rig.session.rescan()

            rig.exists(PATH).shouldBeTrue()
            rig.fs.trashEntries().shouldBeEmpty()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .missing
                .shouldBeFalse()
        }
    }

    /** The row's lifecycle and file, which a failed change must leave as it found them; OCR may move on. */
    private suspend fun lifecycleOf(id: String) =
        rig.db
            .documentDao()
            .getByDocId(id)
            .shouldNotBeNull()
            .let { listOf(it.relPath, it.size, it.mtime, it.trashed, it.trashedAt, it.deleted, it.missing) }

    private suspend fun importAlpha(): String {
        rig.writePdf(PATH, PdfFixtures.embeddedTextPdf("alpha"))
        rig.session.rescan()
        return rig.docId(PATH)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24L * 60L * MINUTE
        const val PATH = "a.pdf"
    }
}
