package dev.npn.core.app

import dev.npn.core.db.QueueState
import dev.npn.core.db.queueState
import dev.npn.core.db.searchDocIds
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.libraryPdfs
import dev.npn.core.fs.trashEntries
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.journalObjectTexts
import dev.npn.core.model.FieldKeys
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import java.nio.file.Files
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test

/**
 * Importing the bytes of a document the library does not show (missing, trashed) brings that
 * document back: only bytes the library shows are a duplicate, and a caller may delete a
 * duplicate's source.
 */
class HiddenDocumentImportTest {
    private lateinit var rig: AppSessionRig

    /** Run [block] over a fresh session holding a document at [at], closed before the test's scope ends. */
    private fun withDocument(
        at: String = "a.pdf",
        files: (LibraryFileSystem) -> LibraryFileSystem = { it },
        block: suspend (id: String, bytes: ByteArray) -> Unit,
    ) = appSessionTest(files = files) {
        rig = it
        val bytes = PdfFixtures.embeddedTextPdf("alpha")
        rig.writePdf(at, bytes)
        rig.session.rescan()
        block(rig.docId(at), bytes)
    }

    @Test
    fun importingTheBytesOfALiveDocumentIsADuplicate() =
        withDocument { id, bytes ->
            rig.session.ingest.importPdfBytes(bytes, "copy.pdf") shouldBe ImportOutcome.Duplicate(id, "a.pdf")

            rig.fs.libraryPdfs() shouldBe listOf("a.pdf")
        }

    /** A document whose file went missing comes back with the file an import brings, metadata and all. */
    @Test
    fun importingTheBytesOfAMissingDocumentBringsItBack() =
        withDocument { id, bytes ->
            rig.session.meta.setField(id, FieldKeys.TITLE, "Kept")
            Files.delete(rig.libRoot.resolve("a.pdf"))
            rig.session.rescan()
            row(id).missing.shouldBeTrue()

            val imported = importAgain(bytes).shouldBeInstanceOf<ImportOutcome.Imported>()

            imported.docId shouldBe id
            rig.fs.sha256(imported.relPath) shouldBe id
            assertLive(id)
            row(id).title shouldBe "Kept"
            rig.session.rescan()
            assertLive(id)
        }

    /** Search finds a returning document under the name its file has now. */
    @Test
    fun aDocumentBroughtBackUnderAnotherNameIsFoundByIt() =
        withDocument { id, bytes ->
            rig.session.docLifecycle.trashDoc(id)

            rig.session.ingest
                .importPdfBytes(bytes, "zulu.pdf")
                .shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.db.ftsDao().searchDocIds("zulu") shouldBe listOf(id)
        }

    /** Nor is a document a duplicate whose file is gone before a rescan noticed. */
    @Test
    fun importingTheBytesOfADocumentWhoseFileJustWentBringsItBack() =
        withDocument { id, bytes ->
            Files.delete(rig.libRoot.resolve("a.pdf"))

            val imported = importAgain(bytes).shouldBeInstanceOf<ImportOutcome.Imported>()

            rig.fs.sha256(imported.relPath) shouldBe id
            rig.session.rescan()
            assertLive(id)
        }

    /**
     * A trashed document's bytes take it out of the trash: the journal records the restore, so no
     * later fold hides it again, and the copy in the trash goes, so there is one copy, not two.
     */
    @Test
    fun importingTheBytesOfATrashedDocumentBringsItBackOutOfTheTrash() =
        withDocument { id, bytes ->
            rig.session.meta.setField(id, FieldKeys.TITLE, "Kept")
            rig.session.work.drainOcr()
            rig.session.docLifecycle.trashDoc(id)

            val imported = importAgain(bytes).shouldBeInstanceOf<ImportOutcome.Imported>()

            imported.docId shouldBe id
            assertLive(id)
            rig.fs.journalObjectTexts().filter { it.contains(JournalOp.DocRestore.WIRE) } shouldHaveSize 1
            rig.fs.trashEntries().shouldBeEmpty()
            rig.fs.libraryPdfs() shouldBe listOf(imported.relPath)
            // Its text is indexed already, so OCR has nothing to redo.
            rig.deviceDb
                .ocrQueueDao()
                .getByDocId(id)
                ?.queueState shouldNotBe QueueState.PENDING
            rig.session.rescan()
            rig.session.meta.setField(id, FieldKeys.NOTES, "Edited")
            assertLive(id)
            row(id).title shouldBe "Kept"
        }

    /**
     * The document is trashed again after its import brought it back, before the import cleared the
     * trash of its old copy. The trash then holds the document's file too, which that cleanup leaves
     * alone: a trashed document keeps a copy of its bytes.
     */
    @Test
    fun aDocumentTrashedAgainBeforeItsImportClearedTheTrashKeepsItsCopy() {
        val trashing = BeforeTrashListing()
        withDocument(files = trashing::over) { id, bytes ->
            rig.session.work.drainOcr()
            rig.session.docLifecycle.trashDoc(id)
            trashing.next = { rig.session.docLifecycle.trashDoc(id) }

            importAgain(bytes).shouldBeInstanceOf<ImportOutcome.Imported>()

            row(id).trashed.shouldBeTrue()
            rig.fs.trashEntries().map { rig.fs.sha256(it.relPath) } shouldContain id
            rig.session.docLifecycle.restoreDoc(id)
            assertLive(id)
            rig.fs.sha256(row(id).relPath) shouldBe id
        }
    }

    /**
     * The document is trashed while its import brings it back, between its row turning live and the
     * return being journaled. The trash waits until the import has journaled the return, then
     * trashes it: the row, the file and the journal agree it is in the trash.
     */
    @Test
    fun aTrashLandingWhileAnImportBringsTheDocumentBackWaitsForTheReturnToBeJournaled() {
        val placing = AfterImportPlaced()
        withDocument(files = placing::over) { id, bytes ->
            rig.session.work.drainOcr()
            rig.session.docLifecycle.trashDoc(id)
            placing.scope = rig.scope
            placing.next = { rig.session.docLifecycle.trashDoc(id) }

            importAgain(bytes).shouldBeInstanceOf<ImportOutcome.Imported>()
            placing.started.shouldNotBeNull().await()

            row(id).trashed.shouldBeTrue()
            rig.fs.trashEntries().map { rig.fs.sha256(it.relPath) } shouldContain id
            rig.fs.libraryPdfs().shouldBeEmpty()
            rig.session.rescan()
            row(id).trashed.shouldBeTrue()
            row(id).missing.shouldBeFalse()
            rig.session.docLifecycle.restoreDoc(id)
            assertLive(id)
            rig.fs.sha256(row(id).relPath) shouldBe id
        }
    }

    /**
     * When the return cannot be recorded, the import is taken back: the document stays in the trash
     * as it was, with its copy there, and no second copy is left in the library.
     */
    @Test
    fun aTrashedDocumentsImportThatCannotBeRecordedLeavesItInTheTrash() =
        withDocument { id, bytes ->
            // Settled first, so no OCR pass writes the row while the import runs.
            rig.session.work.drainOcr()
            rig.session.docLifecycle.trashDoc(id)
            val before = row(id)
            rig.blockMetadataWrites()

            importAgain(bytes) shouldBe ImportOutcome.Failed(ImportFailure.COPY_FAILED)

            row(id) shouldBe before
            rig.fs.trashEntries() shouldHaveSize 1
            rig.fs.libraryPdfs().shouldBeEmpty()
        }

    /**
     * The same for a document trashed from the import's own target, `inbox/a.pdf`: the import moved
     * a copy onto the path the trashed row still names, and takes it back out, so no rescan finds the
     * document's bytes there and takes it out of the trash.
     */
    @Test
    fun aTrashedDocumentsImportOntoItsOldPathThatCannotBeRecordedLeavesItInTheTrash() =
        withDocument(at = "inbox/a.pdf") { id, bytes ->
            rig.session.work.drainOcr()
            rig.session.docLifecycle.trashDoc(id)
            val before = row(id)
            before.relPath shouldBe "inbox/a.pdf"
            rig.blockMetadataWrites()

            importAgain(bytes) shouldBe ImportOutcome.Failed(ImportFailure.COPY_FAILED)

            row(id) shouldBe before
            rig.fs.trashEntries() shouldHaveSize 1
            rig.fs.libraryPdfs().shouldBeEmpty()
            rig.unblockMetadataWrites()
            rig.session.rescan()
            row(id).trashed.shouldBeTrue()
            rig.fs.libraryPdfs().shouldBeEmpty()
        }

    /**
     * Nor is the imported file deleted when it is the only copy of the bytes there is: it stays,
     * the trashed row pointing at it, and an import retried meanwhile makes no second copy.
     */
    @Test
    fun anImportThatCannotBeRecordedKeepsTheOnlyCopy() =
        withDocument { id, bytes ->
            rig.session.docLifecycle.trashDoc(id)
            rig.fs.trashEntries().forEach { rig.fs.delete(it.relPath) }
            rig.blockMetadataWrites()

            repeat(2) {
                importAgain(bytes) shouldBe ImportOutcome.Failed(ImportFailure.COPY_FAILED)
            }

            val kept = rig.fs.libraryPdfs().single()
            rig.fs.sha256(kept) shouldBe id
            row(id).trashed.shouldBeTrue()
            row(id).relPath shouldBe kept
        }

    /** Import [bytes] under the name the document had. */
    private suspend fun importAgain(bytes: ByteArray) = rig.session.ingest.importPdfBytes(bytes, "a.pdf")

    private suspend fun row(id: String) =
        rig.db
            .documentDao()
            .getByDocId(id)
            .shouldNotBeNull()

    private suspend fun assertLive(id: String) {
        val row = row(id)
        row.trashed.shouldBeFalse()
        row.deleted.shouldBeFalse()
        row.missing.shouldBeFalse()
    }
}

/**
 * A library filesystem, for [AppSessionRig.create]'s `files`, that starts [next] on [scope] once an
 * import has moved its staged file into the library, while the import holds the writer lock. [next]
 * runs at once, on the lock holder's dispatcher, until it waits for that lock.
 */
private class AfterImportPlaced {
    @Volatile var next: (suspend () -> Unit)? = null
    lateinit var scope: CoroutineScope

    /** [next], once started. */
    @Volatile var started: Deferred<Unit>? = null

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun move(
                fromRelPath: String,
                toRelPath: String,
            ) {
                fs.move(fromRelPath, toRelPath)
                if (!fromRelPath.startsWith("${NpnPaths.TMP_DIR}/")) return
                val action = next ?: return
                next = null
                val dispatcher = checkNotNull(currentCoroutineContext()[ContinuationInterceptor])
                started = scope.async(dispatcher, CoroutineStart.UNDISPATCHED) { action() }
            }
        }
}
