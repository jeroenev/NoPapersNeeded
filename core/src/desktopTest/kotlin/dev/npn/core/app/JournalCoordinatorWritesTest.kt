package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationResult
import dev.npn.core.fs.VerifiedPublicationFailedException
import dev.npn.core.index.Indexer
import dev.npn.core.index.IndexRig
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
                JournalCoordinator(rig.writer("dev-a"), Indexer(stalling, rig.db, rig.clock), rig.accumulator)
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
                    ): VerifiedPublicationResult {
                        if (failures-- > 0) throw VerifiedPublicationFailedException("rename failed")
                        return rig.fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, expectedHash)
                    }
                }
            val coordinator =
                JournalCoordinator(
                    writer = rig.writer("dev-a"),
                    indexer = rig.indexer,
                    accumulator = rig.accumulator,
                    objectWriter =
                        ContentAddressedJournalWriter(flaky, "test-installation", HlcWatermarkAllocator(rig.clock)),
                )
            coordinator.refreshState()

            assertFailsWith<VerifiedPublicationFailedException> {
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "first")))
            }
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(DOC, "second"))).shouldBeTrue()

            val journal = rig.fs.listAllUnder(NpnPaths.JOURNAL_DIR).filter { !it.isDirectory }
            journal.filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.ObjectV2 } shouldHaveSize 1
            journal.filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.Legacy }.shouldBeEmpty()
        }

    private companion object {
        val DOC = "a".repeat(64)
    }
}
