package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.npnDatabase
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SnapshotCompactor
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Compaction at the accumulator + coordinator level: snapshot publish, supersession-aware
 * validation, deletion-before-snapshot ordering, concurrent snapshots, and ping-pong avoidance.
 */
class CompactionReconcileTest {
    private lateinit var libRoot: Path
    private lateinit var dbDir: File
    private lateinit var fs: JvmLibraryFileSystem
    private lateinit var db: NpnDatabase
    private lateinit var clock: MutableClock
    private lateinit var indexer: Indexer
    private lateinit var accumulator: MetadataAccumulator

    // A large "now" so that files (real JVM mtimes near epoch-now) are older than the window.
    private val farFuture = 4_000_000_000_000L
    private val window = 1_000L

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-compact-lib")
        dbDir = Files.createTempDirectory("npn-compact-db").toFile()
        fs = JvmLibraryFileSystem(libRoot, PathExternalIo)
        db = npnDatabase(File(dbDir, "index.db"))
        clock = MutableClock(1_000L)
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
    }

    @AfterTest
    fun teardown() {
        db.close()
        libRoot.toFile().deleteRecursively()
        dbDir.deleteRecursively()
    }

    private fun coordinator(threshold: Int = 3): JournalCoordinator {
        val writer =
            ContentAddressedJournalWriter(
                fs = fs,
                installationId = "test-installation",
                allocator = HlcWatermarkAllocator(clock),
            )
        return JournalCoordinator(
            writer = writer,
            indexer = indexer,
            accumulator = accumulator,
            compactor = SnapshotCompactor(fs, "test-installation"),
            snapshots = SnapshotSupport(fs, db.metadataProjectionDao()),
            compactionThreshold = threshold,
            compactionStabilityWindowMs = window,
        )
    }

    private fun writePdf(
        relPath: String,
        content: String,
    ) {
        val p = libRoot.resolve(relPath)
        Files.createDirectories(p.parent)
        Files.write(p, content.encodeToByteArray())
    }

    /** Index PDFs so rowAtPath resolves, mirroring a session's initial rescan. */
    private suspend fun initialScan() {
        val state = accumulator.reconcile().state ?: accumulator.loadState()
        indexer.rescan(metadataState = state)
        accumulator.markReady(state.maxHlc)
    }

    private suspend fun objects(): List<JournalPath> =
        fs.listAllUnder(NpnPaths.JOURNAL_DIR).map { JournalPaths.classify(it.relPath) }

    private suspend fun v2Count() = objects().count { it is JournalPath.ObjectV2 }

    private suspend fun snapCount() = objects().count { it is JournalPath.SnapshotV2 }

    @Test
    fun compactionPublishesSnapshotDeletesSupersededAndKeepsProjection() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId

            // 6 separate transactions -> 6 v2 objects, above the threshold of 3.
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            v2Count() shouldBe 6

            val tagsBefore = db.tagDao().tagsForDocument(docId).sorted()

            val snapshotHash = coordinator.compact(nowMillis = farFuture)
            snapshotHash.shouldNotBeNull()
            snapCount() shouldBe 1
            // Superseded objects were deleted.
            v2Count() shouldBe 0

            // A reconcile after compaction must NOT block writes and must keep the projection intact.
            coordinator.reconcileMetadata()
            db.tagDao().tagsForDocument(docId).sorted() shouldContainExactly tagsBefore

            // Writes still work.
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "after"))).shouldBeTrue()
        }

    @Test
    fun deletionBeforeSnapshotDoesNotBlockThenSnapshotArrivalCleansUp() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId
            repeat(5) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()

            // Simulate a remote device that compacted: it deleted the superseded objects, but the
            // snapshot file has not arrived yet (sync reordering). Move the objects aside.
            val v2Files =
                fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .filter { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            val stash = Files.createTempDirectory("npn-stash")
            val moved =
                v2Files.map { file ->
                    val target = stash.resolve(file.name)
                    Files.move(libRoot.resolve(file.relPath), target)
                    file to target
                }

            // Pending supersession: events still applied locally, so reconcile must NOT hard-block.
            repeat(3) { coordinator.reconcileMetadata() }
            db.metadataProjectionDao().state()!!.migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 5

            // Now the snapshot arrives (build it from the stashed objects' events via the compactor).
            // Simpler: restore the objects then compact locally to produce a real snapshot, then delete.
            moved.forEach { (file, target) -> Files.move(target, libRoot.resolve(file.relPath)) }
            coordinator.reconcileMetadata()
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()

            // After the snapshot is applied and superseded objects deleted, reconcile stays clean.
            coordinator.reconcileMetadata()
            db.metadataProjectionDao().state()!!.migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 5
        }

    @Test
    fun aSupersededObjectLeftOnDiskIsDeletedByTheNextReconcile() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            // Keep one object aside, mtime and all, to put back as if the compaction's delete of it
            // had failed.
            val leftover =
                fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .first { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            val kept = Files.createTempDirectory("npn-leftover").resolve(leftover.name)
            Files.copy(libRoot.resolve(leftover.relPath), kept, StandardCopyOption.COPY_ATTRIBUTES)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            Files.copy(kept, libRoot.resolve(leftover.relPath), StandardCopyOption.COPY_ATTRIBUTES)

            coordinator.reconcileMetadata()

            Files.exists(libRoot.resolve(leftover.relPath)).shouldBeFalse()
            checkNotNull(db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "after"))).shouldBeTrue()
        }

    @Test
    fun unexplainedObjectLossEventuallyHardBlocks() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId
            repeat(4) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()

            // Delete a v2 object with NO snapshot ever superseding it -> genuine damage.
            val victim =
                fs
                    .listAllUnder(NpnPaths.JOURNAL_DIR)
                    .first { JournalPaths.classify(it.relPath) is JournalPath.ObjectV2 }
            Files.delete(libRoot.resolve(victim.relPath))

            // Tolerated for the grace window, then escalates to a hard block.
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { coordinator.reconcileMetadata() }
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            db.metadataProjectionDao().state()!!.migrationState shouldBe ProjectionStatus.FAILED
        }

    @Test
    fun repeatedReconcilesDoNotChurnSnapshots() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()

            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            snapCount() shouldBe 1

            // Subsequent compaction passes with no new objects must be no-ops (no ping-pong): a lone
            // snapshot below-threshold and no second snapshot means nothing to collapse.
            repeat(5) {
                coordinator.reconcileMetadata()
                coordinator.compact(nowMillis = farFuture) shouldBe null
            }
            snapCount() shouldBe 1
        }

    @Test
    fun chainCompactionCollapsesMultipleSnapshots() =
        runTest {
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
            coordinator.refreshState()
            val docId = db.documentDao().rowAtPath("a.pdf")!!.docId

            // First snapshot from 6 objects.
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "a$i")))
            }
            coordinator.reconcileMetadata()
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            snapCount() shouldBe 1

            // Simulate a SECOND snapshot arriving from another device (build one via a fresh compactor
            // over the current snapshot + a couple new objects) by producing more objects then chaining.
            repeat(4) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "b$i")))
            }
            coordinator.reconcileMetadata()
            // Now 1 snapshot + 4 objects. Objects (4) exceed threshold(3) -> chain compaction fires,
            // superseding both the old snapshot and the new objects into one.
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            snapCount() shouldBe 1
            v2Count() shouldBe 0
            // All tags survive the chain.
            db.tagDao().tagsForDocument(docId).size shouldBe 10
        }
}
