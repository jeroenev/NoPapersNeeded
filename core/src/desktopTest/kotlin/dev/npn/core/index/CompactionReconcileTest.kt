package dev.npn.core.index

import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.JvmLibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.PathExternalIo
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.PublishedTransaction
import dev.npn.core.journal.SnapshotCodec
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.SnapshotManifest
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Compaction at the accumulator + coordinator level: snapshot publish, supersession-aware
 * validation, deletion-before-snapshot ordering, concurrent snapshots, and ping-pong avoidance.
 */
class CompactionReconcileTest {
    private val rig = IndexRig.create()
    private val libRoot = rig.libRoot
    private val fs = rig.fs
    private val db = rig.db
    private val clock = rig.clock
    private val accumulator = rig.accumulator

    // A large "now" so that files (real JVM mtimes near epoch-now) are older than the window.
    private val farFuture = 4_000_000_000_000L
    private val window = 1_000L

    @AfterTest
    fun teardown() = rig.close()

    private fun coordinator(threshold: Int = 3): JournalCoordinator =
        rig.coordinator(compaction = rig.compaction(threshold, stabilityWindowMs = window))

    private suspend fun v2Count() = journalFiles<JournalPath.ObjectV2>().size

    private suspend fun snapCount() = journalFiles<JournalPath.SnapshotV2>().size

    /** The paths of the journal files classified as [T]. */
    private suspend inline fun <reified T : JournalPath> journalFiles(): List<String> =
        fs.listAllUnder(NpnPaths.JOURNAL_DIR).map { it.relPath }.filter { JournalPaths.classify(it) is T }

    @Test
    fun compactionPublishesSnapshotDeletesSupersededAndKeepsProjection() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = db.documentDao().requireRowAtPath("a.pdf").docId

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
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = db.documentDao().requireRowAtPath("a.pdf").docId
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
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
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

    /**
     * A leftover whose bytes changed since (a sync conflict wrote over it) is still swept: its events
     * are in the snapshot on disk, which the pass reads instead of it, so it fails nothing.
     */
    @Test
    fun aSupersededObjectLeftOnDiskWithOtherBytesIsDeletedByTheNextReconcile() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = journalFiles<JournalPath.ObjectV2>().first()
            val kept = Files.createTempDirectory("npn-leftover").resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            Files.write(libRoot.resolve(leftover), Files.readAllBytes(kept) + "\n".encodeToByteArray())

            coordinator.reconcileMetadata()

            Files.exists(libRoot.resolve(leftover)).shouldBeFalse()
            checkNotNull(db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "after"))).shouldBeTrue()
        }

    /**
     * A library put back from before a compaction holds the objects and not the snapshot. The index
     * still remembers the snapshot, but nothing on disk carries the objects' events any more, so
     * they stay, and the folder alone still folds to every event.
     */
    @Test
    fun objectsBackWithoutTheSnapshotThatReplacedThemAreKept() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val backup = Files.createTempDirectory("npn-backup")
            val objects = journalFiles<JournalPath.ObjectV2>()
            objects.forEach {
                Files.copy(
                    libRoot.resolve(it),
                    backup.resolve(NpnPaths.fileName(it)),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()

            journalFiles<JournalPath.SnapshotV2>().forEach { Files.delete(libRoot.resolve(it)) }
            objects.forEach {
                Files.copy(
                    backup.resolve(NpnPaths.fileName(it)),
                    libRoot.resolve(it),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
            coordinator.reconcileMetadata()

            journalFiles<JournalPath.ObjectV2>() shouldContainExactlyInAnyOrder objects
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            TempIndexDb("npn-compact-rebuild").use { rebuilt ->
                val state = MetadataAccumulator(fs, rebuilt.db, installationId = "rebuild").reconcile().state
                state.shouldNotBeNull().effectiveTags(docId).size shouldBe 6
            }
        }

    /** A snapshot that no longer reads as what was applied fails the pass before its leftovers go. */
    @Test
    fun aChangedSnapshotFailsThePassBeforeAnythingIsDeleted() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = journalFiles<JournalPath.ObjectV2>().first()
            val kept = Files.createTempDirectory("npn-leftover").resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            Files.copy(kept, libRoot.resolve(leftover), StandardCopyOption.COPY_ATTRIBUTES)

            val snapshot = libRoot.resolve(journalFiles<JournalPath.SnapshotV2>().single())
            Files.write(snapshot, Files.readAllBytes(snapshot) + "\n".encodeToByteArray())
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }

            Files.exists(libRoot.resolve(leftover)).shouldBeTrue()
        }

    /**
     * After a chain compaction the first snapshot is gone, and the objects it replaced are carried
     * by the one that replaced it: a leftover of them is still deleted, and their loss stays
     * explained past the grace window.
     */
    @Test
    fun aSnapshotReplacedInTurnStillAccountsForTheObjectsItReplaced() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "a$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = journalFiles<JournalPath.ObjectV2>().first()
            val kept = Files.createTempDirectory("npn-leftover").resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            repeat(4) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "b$i")))
            }
            coordinator.reconcileMetadata()
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            snapCount() shouldBe 1

            Files.copy(kept, libRoot.resolve(leftover), StandardCopyOption.COPY_ATTRIBUTES)
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT + 1) { coordinator.reconcileMetadata() }

            Files.exists(libRoot.resolve(leftover)).shouldBeFalse()
            checkNotNull(db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 10
        }

    /** Only a snapshot on disk explains an object's loss; one the index remembers but is gone does not. */
    @Test
    fun aLossIsExplainedOnlyByASnapshotStillOnDisk() =
        runTest {
            val dao = db.metadataProjectionDao()
            val objectHash = "a".repeat(64)
            val snapshotHash = "b".repeat(64)
            val objectPath = "${NpnPaths.JOURNAL_DIR}/objects-v2-aa/$objectHash.jsonl"
            val snapshotPath = "${NpnPaths.JOURNAL_DIR}/objects-snap-v2-bb/$snapshotHash.jsonl"
            dao.ingest(source(objectPath, SourceKind.OBJECT, objectHash), emptyList())
            dao.ingest(
                source(snapshotPath, SourceKind.SNAPSHOT, snapshotHash),
                emptyList(),
                supersedes = listOf(objectHash),
            )
            val lost = listOf(checkNotNull(dao.source(objectPath)))
            val snapshotOnDisk = mapOf(snapshotPath to FileMeta(snapshotPath, "$snapshotHash.jsonl", 1, 1, false))

            suspend fun SourceSetValidator.judge(files: Map<String, FileMeta>): String? {
                val current = dao.allSources()
                return unexplainedSourceLoss(lost, current, files, dao.carriedByListed(files.keys, current).hashes)
            }
            val withSnapshot = SourceSetValidator(dao)
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT + 1) {
                withSnapshot.judge(snapshotOnDisk) shouldBe null
            }
            val withoutSnapshot = SourceSetValidator(dao)
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { withoutSnapshot.judge(emptyMap()) shouldBe null }
            withoutSnapshot.judge(emptyMap()).shouldNotBeNull()
        }

    private fun source(
        path: String,
        kind: SourceKind,
        hash: String,
    ) = MetadataSourceEntity(path, kind, SourceFingerprint(1, 1, hash), hash, SourceStatus.APPLIED)

    @Test
    fun unexplainedObjectLossEventuallyHardBlocks() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = db.documentDao().requireRowAtPath("a.pdf").docId
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
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = db.documentDao().requireRowAtPath("a.pdf").docId
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
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            rig.rescan()
            coordinator.refreshState()
            val docId = db.documentDao().requireRowAtPath("a.pdf").docId

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
