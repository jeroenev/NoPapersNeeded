package dev.npn.core.app

import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.trashEntries
import dev.npn.core.index.IndexRig
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.WatchedFileSystem
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test

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
    private val lifecycle = rig.docLifecycle(coordinator, files)

    /**
     * [lifecycle] for the call [behindARescan] holds up. A call runs on its lifetime's scope, not the
     * caller's coroutine, so that scope is what carries the waiter's name.
     */
    private val waitingLifecycle =
        rig.docLifecycle(
            coordinator,
            files,
            SessionLifetime(CoroutineScope(Dispatchers.Unconfined + CoroutineName(ParkedSignal.WAITER))),
        )

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

            behindARescan({ waitingLifecycle.trashDoc(id) }) { rig.moveFile("inbox/a.pdf", "archive/a.pdf") }

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
            lifecycle.trashDoc(id)
            val entry =
                rig.fs
                    .trashEntries()
                    .single()
                    .relPath

            behindARescan({ waitingLifecycle.restoreDoc(id) }) {
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
            lifecycle.trashDoc(id)
            files.clear()
            var hashedBeforeTheLock = emptyList<String>()

            behindARescan({ waitingLifecycle.restoreDoc(id) }) { hashedBeforeTheLock = files.calls("sha256") }

            hashedBeforeTheLock shouldHaveSize 1
            files.calls("sha256") shouldBe hashedBeforeTheLock
            rig.fs.sha256("a.pdf") shouldBe id
        }

    /**
     * Start a rescan and stop it once it holds the writer lock; run [action], a call on
     * [waitingLifecycle], in a coroutine of its own until it waits for that lock, then [meanwhile],
     * which changes the library before the rescan reads it; then let the rescan go on, and await both.
     */
    private suspend fun TestScope.behindARescan(
        action: suspend () -> Unit,
        meanwhile: suspend () -> Unit,
    ) {
        val reached = journal.pauseNextListing()
        val rescan = async { coordinator.rescan() }
        reached.await()
        val waiter = async { action() }
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
     * The coordinator's dispatcher, over [Dispatchers.Default], sending to [parked] each time a coroutine named
     * [WAITER] has run there as far as it can. Dispatched there, a caller goes straight for the writer
     * lock, so the first such signal while another holds the lock says it waits for it.
     */
    private class ParkedSignal : CoroutineDispatcher() {
        val parked = Channel<Unit>(Channel.UNLIMITED)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) = Dispatchers.Default.dispatch(context) {
            block.run()
            if (context[CoroutineName]?.name == WAITER) parked.trySend(Unit)
        }

        companion object {
            const val WAITER = "waiter"
        }
    }
}
