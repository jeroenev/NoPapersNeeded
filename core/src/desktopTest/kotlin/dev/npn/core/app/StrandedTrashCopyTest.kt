package dev.npn.core.app

import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A restore made on a device that had another device's trash event but not yet its file move takes
 * the file in place; the move then lands, and leaves the document's bytes only in the trash. A
 * rescan that finds the document missing brings that copy back, as long as the copy belongs to a
 * trash the journal has restored since, and only then.
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

    private suspend fun importAlpha(): String {
        rig.writePdf(PATH, PdfFixtures.embeddedTextPdf("alpha"))
        rig.session.rescan()
        return rig.docId(PATH)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val PATH = "a.pdf"
    }
}
