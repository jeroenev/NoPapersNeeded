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
 * Trash and restore read the document's row and check its file under the writer lock, in the same
 * step as the file move and the journal write: a rescan holding the lock while they wait for it
 * cannot change the document under them.
 */
class LifecycleBehindARescanTest {
    private val rig = IndexRig.create()
    private val journal = PausingJournal(rig.fs)
    private val waiting = ParkedSignal()
    private val coordinator =
        rig.coordinator(
            accumulator = MetadataAccumulator(journal, rig.db, installationId = "test-installation"),
            dispatcher = waiting,
        )
    private val files = WatchedFileSystem(rig.fs).also { it.db = rig.db }
    private val writer =
        rig.metadataWriter(coordinator, files)

    @AfterTest
    fun teardown() = rig.close()

    /**
     * The file was renamed outside the app, and the rescan that finds it holds the lock while the
     * trash waits: the trash takes the file from where the rescan found it, and records that path.
     */
    @Test
    fun aTrashWaitingBehindARescanThatFindsTheFileRenamedTrashesItWhereItIsNow() =
        runTest {
            rig.writePdf("inbox/a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("inbox/a.pdf")

            behindARescan({ writer.trashDoc(id) }) { rig.moveFile("inbox/a.pdf", "archive/a.pdf") }

            rig.exists("archive/a.pdf").shouldBeFalse()
            rig.fs.trashEntries().map { rig.fs.sha256(it.relPath) } shouldBe listOf(id)
            val row =
                rig.db
                    .documentDao()
                    .getByDocId(id)
                    .shouldNotBeNull()
            row.trashed.shouldBeTrue()
            row.trashedOrigPath shouldBe "archive/a.pdf"
            coordinator.rescan()
            rig.db
                .documentDao()
                .getByDocId(id)
                .shouldNotBeNull()
                .trashed
                .shouldBeTrue()
        }

    /**
     * Another device restored the document and renamed it; the rescan that brings both here holds
     * the lock while a restore waits. The document is no longer in the trash, so the restore does
     * nothing: it neither points the row at the old path nor journals the file back there.
     */
    @Test
    fun aRestoreWaitingBehindARescanThatFindsTheDocumentRestoredElsewhereDoesNothing() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            writer.trashDoc(id)
            val entry =
                rig.fs
                    .trashEntries()
                    .single()
                    .relPath

            behindARescan({ writer.restoreDoc(id) }) {
                rig
                    .writer("other-installation")
                    .commit(
                        listOf(PendingOp.docRestore(id), PendingOp.propSet(id, SystemProperty.STORAGE_PATH, "c.pdf")),
                    )
                rig.moveFile(entry, "c.pdf")
            }

            repeat(2) { pass ->
                if (pass > 0) coordinator.rescan()
                rig.accumulator.loadState().effectiveProp(id, SystemProperty.STORAGE_PATH) shouldBe "c.pdf"
                rig.exists("a.pdf").shouldBeFalse()
                rig.fs.sha256("c.pdf") shouldBe id
                val row =
                    rig.db
                        .documentDao()
                        .getByDocId(id)
                        .shouldNotBeNull()
                row.isLive.shouldBeTrue()
                row.relPath shouldBe "c.pdf"
            }
        }

    /** A restore hashes the trash entries before it waits for the lock, and an unchanged one not again under it. */
    @Test
    fun aRestoreHashesItsTrashCopyBeforeWaitingForTheLock() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            coordinator.rescan()
            val id = rig.fs.sha256("a.pdf")
            writer.trashDoc(id)
            files.clear()
            var hashedBeforeTheLock = emptyList<String>()

            behindARescan({ writer.restoreDoc(id) }) { hashedBeforeTheLock = files.calls("sha256") }

            hashedBeforeTheLock shouldHaveSize 1
            files.calls("sha256") shouldBe hashedBeforeTheLock
            rig.fs.sha256("a.pdf") shouldBe id
        }

    /**
     * Start a rescan and stop it once it holds the writer lock; run [action] in a coroutine of its
     * own until it waits for that lock, then [meanwhile], which changes the library before the rescan
     * reads it; then let the rescan go on, and await both.
     */
    private suspend fun TestScope.behindARescan(
        action: suspend () -> Unit,
        meanwhile: suspend () -> Unit,
    ) {
        val reached = journal.pauseNextListing()
        val rescan = async { coordinator.rescan() }
        reached.await()
        val waiter = async(CoroutineName(ParkedSignal.WAITER)) { action() }
        waiting.parked.receive()
        meanwhile()
        journal.resume()
        rescan.await()
        waiter.await()
    }

    /** [fs], whose next listing of the journal waits, once [pauseNextListing] asks it to, until [resume]. */
    private class PausingJournal(
        private val fs: LibraryFileSystem,
    ) : LibraryFileSystem by fs {
        @Volatile private var pause: CompletableDeferred<Unit>? = null
        private val resumed = CompletableDeferred<Unit>()

        /** Pause the next journal listing; the result completes once it has started. */
        fun pauseNextListing(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { pause = it }

        fun resume() = resumed.complete(Unit)

        override suspend fun listAllUnder(relPath: String): List<FileMeta> {
            if (relPath == NpnPaths.JOURNAL_DIR) {
                pause?.let { reached ->
                    pause = null
                    reached.complete(Unit)
                    resumed.await()
                }
            }
            return fs.listAllUnder(relPath)
        }
    }

    /**
     * The coordinator's dispatcher, over [threads], sending to [parked] each time a coroutine named
     * [WAITER] has run there as far as it can. Dispatched there, a caller goes straight for the writer
     * lock, so the first such signal while another holds the lock says it waits for it.
     */
    private class ParkedSignal(
        private val threads: CoroutineDispatcher = Dispatchers.Default,
    ) : CoroutineDispatcher() {
        val parked = Channel<Unit>(Channel.UNLIMITED)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = threads.dispatch(context) {
            block.run()
            if (context[CoroutineName]?.name == WAITER) parked.trySend(Unit)
        }

        companion object {
            const val WAITER = "waiter"
        }
    }
}
