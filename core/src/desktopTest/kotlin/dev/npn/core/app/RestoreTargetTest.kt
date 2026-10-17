package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.MutableClock
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.index.indexTest
import dev.npn.core.index.requireByDocId
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnLog
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Collections
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A restore goes back to the path its document was trashed from, or beside it when another document
 * holds that path: by a file there, or by a live row naming it.
 */
class RestoreTargetTest {
    /**
     * A trashed document gives its old path up to a rename: the renamed document takes it, and the
     * trashed one stays in the trash as it was, and comes back beside it rather than over it.
     */
    @Test
    fun aRenameOntoATrashedDocumentsPathTakesItAndTheRestoreGoesBesideIt() =
        appSessionTest { rig ->
            rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
            rig.writePdf("inbox/q.pdf", PdfFixtures.embeddedTextPdf("renamed"))
            rig.session.rescan()
            val trashed = rig.docId("inbox/invoice.pdf")
            val renamed = rig.docId("inbox/q.pdf")
            rig.session.docLifecycle.trashDoc(trashed)

            rig.session.docLifecycle.renameFile(renamed, "invoice.pdf")

            rig.exists("inbox/q.pdf").shouldBeFalse()
            rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
            rig.db
                .documentDao()
                .getByDocId(renamed)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/invoice.pdf"
            val hidden =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            hidden.trashed.shouldBeTrue()
            hidden.missing.shouldBeFalse()
            hidden.trashedOrigPath shouldBe "inbox/invoice.pdf"
            rig.session.rescan()
            rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed

            rig.session.docLifecycle.restoreDoc(trashed)

            rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
            rig.fs.sha256("inbox/invoice (restored).pdf") shouldBe trashed
            rig.session.rescan()
            val restored =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            restored.isLive.shouldBeTrue()
            restored.relPath shouldBe "inbox/invoice (restored).pdf"
            rig.db
                .documentDao()
                .getByDocId(renamed)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/invoice.pdf"
        }

    /** Nor does a restore with no copy left in the trash take the path another document holds. */
    @Test
    fun aRestoreWithNoTrashCopyLeavesTheDocumentAtItsOldPathAlone() =
        appSessionTest { rig ->
            rig.writePdf("inbox/invoice.pdf", PdfFixtures.embeddedTextPdf("trashed"))
            rig.writePdf("inbox/q.pdf", PdfFixtures.embeddedTextPdf("renamed"))
            rig.session.rescan()
            val trashed = rig.docId("inbox/invoice.pdf")
            val renamed = rig.docId("inbox/q.pdf")
            rig.session.docLifecycle.trashDoc(trashed)
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory }
                .forEach { rig.fs.delete(it.relPath) }
            rig.session.docLifecycle.renameFile(renamed, "invoice.pdf")

            rig.session.docLifecycle.restoreDoc(trashed)

            rig.fs.sha256("inbox/invoice.pdf") shouldBe renamed
            rig.db
                .documentDao()
                .getByDocId(renamed)
                .shouldNotBeNull()
                .relPath shouldBe "inbox/invoice.pdf"
            val restored =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            restored.trashed.shouldBeFalse()
            restored.relPath shouldBe "inbox/invoice (restored).pdf"
        }

    /**
     * A document imported since took the path a trashed one came from, and its file went outside the
     * app with no rescan since: its live row still names the path, free on disk. The restore goes
     * beside it, as when the file is there, and leaves that row alone.
     */
    @Test
    fun aRestoreGoesBesideAPathALiveRowStillNames() =
        withPathTakenByAGoneFile { rig, trashed, later ->
            rig.session.docLifecycle.restoreDoc(trashed)

            val restored =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            restored.isLive.shouldBeTrue()
            restored.relPath shouldBe "inbox/x (restored).pdf"
            rig.fs.sha256(restored.relPath) shouldBe trashed
            rig.db.documentDao().getByDocId(later.docId) shouldBe later
        }

    /** The same with no copy left in the trash: the restored row does not take that path either. */
    @Test
    fun aRestoreWithNoTrashCopyGoesBesideAPathALiveRowStillNames() =
        withPathTakenByAGoneFile { rig, trashed, later ->
            rig.fs
                .listAllUnder(NpnPaths.TRASH_DIR)
                .filter { !it.isDirectory }
                .forEach { rig.fs.delete(it.relPath) }

            rig.session.docLifecycle.restoreDoc(trashed)

            val restored =
                rig.db
                    .documentDao()
                    .getByDocId(trashed)
                    .shouldNotBeNull()
            restored.trashed.shouldBeFalse()
            restored.relPath shouldBe "inbox/x (restored).pdf"
            rig.db.documentDao().getByDocId(later.docId) shouldBe later
        }

    /**
     * Run [block] over a library where a document was trashed from `inbox/x.pdf`, another was then
     * imported as `x.pdf` and landed there, and that one's file was deleted outside the app since.
     * [block] gets the trashed document's id and the other one's row.
     */
    private fun withPathTakenByAGoneFile(block: suspend (AppSessionRig, String, DocumentEntity) -> Unit) =
        appSessionTest { rig ->
            rig.writePdf("inbox/x.pdf", PdfFixtures.embeddedTextPdf("trashed"))
            rig.session.rescan()
            val trashed = rig.docId("inbox/x.pdf")
            rig.session.docLifecycle.trashDoc(trashed)
            val imported =
                rig.session.ingest
                    .importPdfBytes(PdfFixtures.embeddedTextPdf("imported since"), "x.pdf")
                    .shouldBeInstanceOf<ImportOutcome.Imported>()
            imported.relPath shouldBe "inbox/x.pdf"
            rig.session.work.drainOcr()
            Files.delete(rig.libRoot.resolve("inbox/x.pdf"))
            val later =
                rig.db
                    .documentDao()
                    .getByDocId(imported.docId)
                    .shouldNotBeNull()
            block(rig, trashed, later)
        }
}
