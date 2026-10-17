package dev.npn.core.index

import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.PendingOp
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * An import that brings a hidden document back is taken back when its return cannot be recorded,
 * whatever ends the attempt: never is the document left live without a record of its return, which
 * the next fold would undo with the import's copy left in the library.
 */
class ImportTakeBackTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() = rig.close()

    /** A caller that goes away while the return is recorded does not cut the import short. */
    @Test
    fun anImportWhoseCallerLeftWhileItsReturnWasRecordedIsTakenBackWhenTheRecordFails() =
        runTest {
            val id = trashed("a.pdf", "alpha")
            val before = rig.db.documentDao().getByDocId(id)
            val recording = CompletableDeferred<Unit>()
            val recorded = CompletableDeferred<Boolean>()
            val imports =
                ImportService(
                    rig.fs,
                    rig.db,
                    rig.deviceDb.ocrQueueDao(),
                    rig.clock,
                    serialize = { step ->
                        step(
                            object : LockedJournal {
                                override suspend fun successorOf(docId: String): String = docId

                                override suspend fun isDeleted(docId: String): Boolean = false

                                override suspend fun restoreReturned(
                                    docId: String,
                                    relPath: String,
                                ): Boolean {
                                    recording.complete(Unit)
                                    return recorded.await()
                                }

                                override suspend fun trashTimes(): Map<String, Long?> = emptyMap()
                            },
                        )
                    },
                )

            val importing =
                launch { runCatchingCancellable { imports.importBytes("alpha".encodeToByteArray(), "a.pdf") } }
            recording.await()
            importing.cancel()
            recorded.completeExceptionally(IllegalStateException("Metadata writes are disabled"))
            importing.join()

            rig.exists("inbox/a.pdf").shouldBeFalse()
            TrashOps(rig.fs).copiesOf(id) shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before
        }

    /** Trash the document of [content] at [relPath], its file into the trash, as this device does; returns its id. */
    private suspend fun trashed(
        relPath: String,
        content: String,
    ): String {
        rig.writePdf(relPath, content)
        rig.rescan()
        val id = rig.fs.sha256(relPath)
        rig.fs.move(relPath, TrashOps(rig.fs).trashTarget(relPath, id, rig.clock.nowMillis()))
        rig.clock.advance()
        rig.writer().commit(listOf(PendingOp.docTrash(id, relPath)))
        rig.rescan()
        return id
    }
}
