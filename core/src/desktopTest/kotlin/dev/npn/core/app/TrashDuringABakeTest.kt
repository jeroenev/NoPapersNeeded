package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test

/**
 * A trash of a document while it is baked in place: it waits for the bake, then acts on the document
 * the bake made.
 */
class TrashDuringABakeTest {
    /**
     * A trash of a document being baked that lands after the bake put its rewrite in place and before
     * its relink: it waits for the bake and trashes the document the bake made, which its Undo brings
     * back. Landing first, it was recorded against bytes no longer there, and the baked document came
     * back live with the relink.
     */
    @Test
    fun aTrashDuringABakeTrashesTheBakedDocument() = trashedDuringABake(PausedBake.Moment.AFTER_SWAP)

    /** The same for a trash that lands after the bake checked its file is unchanged and before it deletes it. */
    @Test
    fun aTrashBeforeABakeSwapsTrashesTheBakedDocument() = trashedDuringABake(PausedBake.Moment.BEFORE_DELETE)

    private fun trashedDuringABake(moment: PausedBake.Moment) =
        runTest {
            val paused = PausedBake("a.pdf", moment)
            val rig = AppSessionRig.create(backgroundScope, files = paused::over)
            try {
                rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
                rig.session.rescan()
                val id = rig.docId("a.pdf")
                paused.armed.set(true)

                val bake = async { rig.session.content.bakeInPlace(id) }
                paused.reached.await()
                val trash = async { rig.session.docLifecycle.trashDoc(id) }
                // Time for the trash to land in the bake's window, as it would if it did not wait.
                paused.finishesWithin(trash, QUIET_MILLIS) shouldBe false
                paused.release()
                bake.await() shouldBe BakeOutcome.Baked
                val receipt = trash.await().shouldNotBeNull()
                rig.session.rescan()

                rig.db
                    .documentDao()
                    .getByRelPath("a.pdf")
                    ?.isLive shouldNotBe true
                rig.exists("a.pdf") shouldBe false
                val baked = receipt.docId
                baked shouldNotBe id
                rig.db
                    .documentDao()
                    .getByDocId(baked)
                    ?.trashed shouldBe true

                rig.session.docLifecycle.undoTrash(listOf(receipt)) shouldBe 0
                rig.session.rescan()
                val restored =
                    rig.db
                        .documentDao()
                        .getByDocId(baked)
                        .shouldNotBeNull()
                restored.isLive shouldBe true
                restored.relPath shouldBe "a.pdf"
                rig.docId("a.pdf") shouldBe baked
            } finally {
                paused.release()
                rig.close()
            }
        }

    private companion object {
        /** How long a trash that does not wait gets to finish. */
        const val QUIET_MILLIS = 1_000L
    }
}

/** A library filesystem over which, once [armed], a bake of [path] pauses at its gate at [moment] of its swap. */
private class PausedBake(
    private val path: String,
    private val moment: Moment,
) : FsGate() {
    enum class Moment {
        /** Once the bake checked the file is the one it read, right before it deletes it. */
        BEFORE_DELETE,

        /** Right after the bake moved its rewrite into place, before its relink. */
        AFTER_SWAP,
    }

    val armed = AtomicBoolean(false)

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun delete(relPath: String) {
                if (moment == Moment.BEFORE_DELETE && relPath == path) pauseIfArmed()
                fs.delete(relPath)
            }

            override suspend fun move(
                fromRelPath: String,
                toRelPath: String,
            ) {
                fs.move(fromRelPath, toRelPath)
                val swap = fromRelPath == NpnPaths.Temp.bakePath(path) && toRelPath == path
                if (moment == Moment.AFTER_SWAP && swap) pauseIfArmed()
            }
        }

    private suspend fun pauseIfArmed() {
        if (armed.getAndSet(false)) pause()
    }
}
