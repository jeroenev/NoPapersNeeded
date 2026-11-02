package dev.npn.core.index

import dev.npn.core.db.isLive
import dev.npn.core.fs.TrashOps
import dev.npn.core.journal.PendingOp
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
                importsOver { _, _ ->
                    recording.complete(Unit)
                    recorded.await()
                }

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

    /**
     * A sync tool put a trashed document's bytes back at its path, and an import of them finds them
     * there: it moves nothing. Its return then cannot be recorded. The file there was the user's
     * before the import, so taking the import back leaves it, even with a copy still in the trash.
     */
    @Test
    fun anImportThatFoundTheBytesInPlaceLeavesThatFileWhenItsReturnCannotBeRecorded() =
        runTest {
            val id = trashed("a.pdf", "alpha")
            rig.writePdf("a.pdf", "alpha")
            val before = rig.db.documentDao().getByDocId(id)
            val imports = importsOver { _, _ -> error("Metadata writes are disabled") }

            assertFailsWith<IllegalStateException> { imports.importBytes("alpha".encodeToByteArray(), "a.pdf") }

            rig.fs.sha256("a.pdf") shouldBe id
            rig.exists("inbox/a.pdf").shouldBeFalse()
            TrashOps(rig.fs).copiesOf(id) shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before
        }

    /**
     * The import moved its copy of a trashed document's bytes into the library, and before its return
     * could be recorded, another process wrote other bytes over that copy. Taking the import back
     * deletes only its own copy, so that file stays.
     */
    @Test
    fun anImportsCopyWrittenOverBeforeItIsTakenBackStays() =
        runTest {
            val id = trashed("a.pdf", "alpha")
            val before = rig.db.documentDao().getByDocId(id)
            val imports =
                importsOver { _, relPath ->
                    rig.writePdf(relPath, "written over")
                    error("Metadata writes are disabled")
                }

            assertFailsWith<IllegalStateException> { imports.importBytes("alpha".encodeToByteArray(), "a.pdf") }

            rig.fs.readBytes("inbox/a.pdf").decodeToString() shouldBe "written over"
            TrashOps(rig.fs).copiesOf(id) shouldHaveSize 1
            rig.db.documentDao().getByDocId(id) shouldBe before
        }

    /**
     * Bytes the index has no row for, which the journal still hides (trashed on another device
     * before this index ever saw them): the import brings that document back, and a split taking the
     * import back leaves it, file and row, as it does any document an import brought back.
     */
    @Test
    fun anImportTheJournalRecordsAsAReturnIsNotUndone() =
        runTest {
            val imports = importsOver { _, _ -> true }

            val imported =
                imports.importBytes("alpha".encodeToByteArray(), "a.pdf").shouldBeInstanceOf<ImportResult.Imported>()
            imported.broughtBack.shouldBeTrue()
            imports.undoImport(imported)

            rig.fs.sha256(imported.relPath) shouldBe imported.docId
            rig.db
                .documentDao()
                .getByDocId(imported.docId)
                .shouldNotBeNull()
                .isLive
                .shouldBeTrue()
        }

    /** A new document's import, which the journal does not hide, is no return: undoing it removes it. */
    @Test
    fun anImportOfNewBytesIsNoReturnAndIsUndone() =
        runTest {
            val imports = importsOver { _, _ -> false }

            val imported =
                imports.importBytes("alpha".encodeToByteArray(), "a.pdf").shouldBeInstanceOf<ImportResult.Imported>()
            imported.broughtBack.shouldBeFalse()
            imports.undoImport(imported)

            rig.exists(imported.relPath).shouldBeFalse()
            rig.db
                .documentDao()
                .getByDocId(imported.docId)
                .shouldBeNull()
        }

    /** An import service over this rig whose journal answers [restoreReturned] as given. */
    private fun importsOver(restoreReturned: suspend (docId: String, relPath: String) -> Boolean) =
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

                        override suspend fun isTrashed(docId: String): Boolean = false

                        override suspend fun isCurrent(docId: String): Boolean = true

                        override suspend fun restoreReturned(
                            docId: String,
                            relPath: String,
                        ): Boolean = restoreReturned(docId, relPath)

                        override suspend fun trashTimes(): Map<String, Long?> = emptyMap()
                    },
                )
            },
        )

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
