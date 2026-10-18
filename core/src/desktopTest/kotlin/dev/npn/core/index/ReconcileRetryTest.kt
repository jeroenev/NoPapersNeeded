package dev.npn.core.index

import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.PendingOp
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A reconcile that fails partway has recorded the sources it read before the failure, without
 * folding or projecting them. Whatever comes next must not fold onto the fold it had, which lacks
 * them: the next reconcile or ingest refolds and reprojects in full.
 */
class ReconcileRetryTest {
    private val rig = IndexRig.create()

    @AfterTest
    fun tearDown() = rig.close()

    /**
     * A coordinator with a materialized fold, after a reconcile that failed on the later (by path)
     * of two objects another device wrote, one tag each: it recorded the earlier one, then found the
     * later one only partly synced. [laterBytes] are the later object's whole bytes, to put back.
     */
    private class FailedReconcile(
        val coordinator: JournalCoordinator,
        val docId: String,
        val earlierTag: String,
        val laterTag: String,
        val laterPath: String,
        val laterBytes: ByteArray,
    )

    private suspend fun failReconcileOnTheLaterOfTwoRemoteObjects(): FailedReconcile {
        rig.writePdf("a.pdf", "alpha")
        rig.rescan()
        val docId = checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
        val coordinator = rig.coordinator(rig.writer("dev-main"))
        coordinator.refreshState()
        rig.clock.advance()
        coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "local")))

        val remote = rig.writer("device-two")
        val published =
            listOf("one", "two").map { tag ->
                rig.clock.advance()
                tag to remote.commit(listOf(PendingOp.tagAdd(docId, tag))).relPath
            }
        val (earlier, later) = published.sortedBy { (_, path) -> path }
        val laterBytes = rig.fs.readBytes(later.second)
        Files.write(rig.libRoot.resolve(later.second), laterBytes.copyOf(laterBytes.size / 2))
        rig.clock.advance()
        assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
        return FailedReconcile(coordinator, docId, earlier.first, later.first, later.second, laterBytes)
    }

    private suspend fun tagsOf(docId: String) = rig.db.tagDao().tagsForDocument(docId)

    /**
     * The retry skips the earlier object as applied, so it must refold rather than fold the later
     * one onto the fold it had; the live fold it leaves holds the earlier tag's dots too.
     */
    @Test
    fun aRetryAfterAFailedReconcileKeepsWhatTheFailedPassRecorded() =
        runTest {
            val failed = failReconcileOnTheLaterOfTwoRemoteObjects()
            Files.write(rig.libRoot.resolve(failed.laterPath), failed.laterBytes)

            failed.coordinator.reconcileMetadata()

            tagsOf(failed.docId) shouldContainExactlyInAnyOrder listOf("local", failed.earlierTag, failed.laterTag)
            failed.coordinator.commitBuilt { state -> state.tagRemovalOps(listOf(failed.docId), failed.earlierTag) }
            tagsOf(failed.docId) shouldContainExactlyInAnyOrder listOf("local", failed.laterTag)
        }

    /** A retry that reads nothing, the partial object gone, still projects what the failed pass recorded. */
    @Test
    fun aRetryThatReadsNothingStillProjectsWhatTheFailedPassRecorded() =
        runTest {
            val failed = failReconcileOnTheLaterOfTwoRemoteObjects()
            rig.deleteFile(failed.laterPath)

            failed.coordinator.reconcileMetadata()

            tagsOf(failed.docId) shouldContainExactlyInAnyOrder listOf("local", failed.earlierTag)
        }

    /**
     * A sync tool's file for an object it is still transferring, left in the bucket (an interrupted
     * transfer can leave one for a day): no reconcile and no open fails on it, and nothing deletes it.
     */
    @Test
    fun aSyncToolsTransferFileInABucketFailsNothing() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            rig.rescan()
            val docId = checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
            val coordinator = rig.coordinator()
            coordinator.refreshState()
            rig.clock.advance()
            val published = rig.writer("device-two").commit(listOf(PendingOp.tagAdd(docId, "remote")))
            val bucket = NpnPaths.parentDir(published.relPath)
            val name = NpnPaths.fileName(published.relPath)
            val partial = rig.fs.readBytes(published.relPath).let { it.copyOf(it.size / 2) }
            val transfers =
                listOf(
                    "$bucket/.syncthing.$name.tmp",
                    "$bucket/~syncthing~$name.tmp",
                    "$bucket/.$name.~3fa9c2d1",
                    "$bucket/$name.!sync",
                    "${NpnPaths.JOURNAL_DIR}/objects-snap-v2-cd/.syncthing.${"cd".repeat(32)}.jsonl.tmp",
                )
            for (transfer in transfers) {
                Files.createDirectories(rig.libRoot.resolve(transfer).parent)
                Files.write(rig.libRoot.resolve(transfer), partial)
            }

            coordinator.reconcileMetadata()
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "local"))).shouldBeTrue()

            tagsOf(docId) shouldContainExactlyInAnyOrder listOf("remote", "local")
            transfers.forEach { rig.exists(it).shouldBeTrue() }
            TempIndexDb("npn-transfer-open").use { fresh ->
                val opened = MetadataAccumulator(rig.fs, fresh.db).reconcileOnOpen()
                opened
                    .shouldBeInstanceOf<OpenReconciliation.Refolded>()
                    .state
                    .effectiveTags(docId) shouldBe setOf("remote", "local")
            }
            transfers.forEach { rig.exists(it).shouldBeTrue() }
        }

    /** The folder watch's ingest of the object the reconcile failed on refolds in full too. */
    @Test
    fun anIngestAfterAFailedReconcileKeepsWhatTheFailedPassRecorded() =
        runTest {
            val failed = failReconcileOnTheLaterOfTwoRemoteObjects()
            Files.write(rig.libRoot.resolve(failed.laterPath), failed.laterBytes)

            failed.coordinator.ingestObject(failed.laterPath)

            tagsOf(failed.docId) shouldContainExactlyInAnyOrder listOf("local", failed.earlierTag, failed.laterTag)
        }
}
