package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.IndexRig
import dev.npn.core.index.Indexer
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** When [JournalCoordinator] may refuse a write, and when it must not. */
class JournalCoordinatorWritesTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun teardown() = rig.close()

    @Test
    fun aCancelledRescanLeavesWritesEnabled() =
        runTest {
            val listing = CompletableDeferred<Unit>()
            val stalling =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun listAllIndexable(): List<FileMeta> {
                        listing.complete(Unit)
                        awaitCancellation()
                    }
                }
            val coordinator =
                JournalCoordinator(rig.writer(), Indexer(stalling, rig.db, rig.clock), rig.accumulator)
            coordinator.refreshState()

            val rescan = launch { coordinator.rescan() }
            listing.await()
            rescan.cancelAndJoin()

            coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "kept"))).shouldBeTrue()
        }

    @Test
    fun aTransientPublishFailureFailsOneCommitAndKeepsStorageV2() =
        runTest {
            var failures = 1
            val flaky =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun publishVerifiedObjectOnce(
                        stagingPath: String,
                        canonicalPath: String,
                        expectedHash: String,
                    ) {
                        if (failures-- > 0) throw VerifiedPublicationFailedException("rename failed")
                        rig.fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                    }
                }
            val coordinator =
                JournalCoordinator(
                    ContentAddressedJournalWriter(flaky, "test-installation", HlcWatermarkAllocator(rig.clock)),
                    rig.indexer,
                    rig.accumulator,
                )
            coordinator.refreshState()

            assertFailsWith<VerifiedPublicationFailedException> {
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "first")))
            }
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "second"))).shouldBeTrue()

            val journal = rig.fs.listAllUnder(NpnPaths.JOURNAL_DIR).filter { !it.isDirectory }
            journal.filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 } shouldHaveSize 1
            journal.filter { JournalPaths.classify(it.relPath) is JournalPath.Legacy }.shouldBeEmpty()
        }

    /** There is no older format to fall back to: a filesystem that cannot publish fails the write. */
    @Test
    fun aFilesystemThatCannotPublishFailsTheWriteInsteadOfWritingV1() =
        runTest {
            val noPrimitive =
                object : LibraryFileSystem by rig.fs {
                    override suspend fun publishVerifiedObjectOnce(
                        stagingPath: String,
                        canonicalPath: String,
                        expectedHash: String,
                    ): Unit = throw VerifiedPublicationUnavailableException("no link, no rename")
                }
            val coordinator =
                JournalCoordinator(
                    ContentAddressedJournalWriter(noPrimitive, "test-installation", HlcWatermarkAllocator(rig.clock)),
                    rig.indexer,
                    rig.accumulator,
                )
            coordinator.refreshState()

            repeat(2) {
                assertFailsWith<VerifiedPublicationUnavailableException> {
                    coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "never")))
                }
            }

            rig.fs
                .listAllUnder(NpnPaths.JOURNAL_DIR)
                .filter { !it.isDirectory && JournalPaths.classify(it.relPath) !is JournalPath.Staging }
                .shouldBeEmpty()
        }

    /** Every commit, whatever the entry point, publishes one storage-v2 object and nothing else. */
    @Test
    fun everyWritePublishesAStorageV2Object() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = rig.coordinator()
            coordinator.rescan()
            val doc = rig.fs.sha256("a.pdf")

            coordinator.commitAndApply(listOf(PendingOp.tagAdd(doc, "one")))
            coordinator.commitBuilt { state -> state.tagRemovalOps(listOf(doc), "one") }
            coordinator.commitWithOptimisticLifecycle(listOf(PendingOp.docTrash(doc, "a.pdf")), setOf(doc)) {}
            coordinator.commitAndRescan(listOf(PendingOp.docRestore(doc)))

            val journal = rig.fs.listAllUnder(NpnPaths.JOURNAL_DIR).filter { !it.isDirectory }
            journal.forEach { JournalPaths.classify(it.relPath).shouldBeInstanceOf<JournalPath.ObjectV2>() }
            journal shouldHaveSize 4
        }

    /** A viewmodel commits from the main thread; the fold and the write must not run there. */
    @Test
    fun aCommitRunsOffTheCallersThread() =
        runTest {
            val caller = Executors.newSingleThreadExecutor { Thread(it, CALLER_THREAD) }.asCoroutineDispatcher()
            try {
                val coordinator = rig.coordinator()
                coordinator.refreshState()
                var buildThread: String? = null

                withContext(caller) {
                    coordinator.commitBuilt {
                        buildThread = Thread.currentThread().name
                        listOf(PendingOp.tagAdd(DOC, "main-safe"))
                    }
                }

                buildThread shouldNotBe null
                buildThread shouldNotBe CALLER_THREAD
            } finally {
                caller.close()
            }
        }

    private companion object {
        const val CALLER_THREAD = "caller"

        val DOC = "a".repeat(64)
    }
}
