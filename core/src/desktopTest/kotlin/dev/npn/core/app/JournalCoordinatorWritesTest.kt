package dev.npn.core.app

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.Indexer
import dev.npn.core.index.IndexRig
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

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

            coordinator.commitAndApply(listOf(PendingOp.tagAdd("a".repeat(64), "kept"))).shouldBeTrue()
        }
}
