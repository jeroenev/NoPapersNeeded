package dev.npn.core.index

import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.compactAsAnEarlierBuild
import dev.npn.core.journal.journalObjects
import dev.npn.core.journal.journalPaths
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Libraries an earlier build compacted, at the accumulator + coordinator level: their snapshots
 * fold, and the objects they replaced, deleted or still there, are accounted for.
 */
class CompactionReconcileTest {
    private val rig = IndexRig.create()
    private val libRoot = rig.libRoot
    private val fs = rig.fs
    private val db = rig.db
    private val clock = rig.clock

    @AfterTest
    fun teardown() = rig.close()

    private fun coordinator(): JournalCoordinator = rig.coordinator()

    /**
     * Compact every object and snapshot on disk as an earlier build did, then let [coordinator]
     * reconcile what it left; returns the snapshot's path.
     */
    private suspend fun compactAll(coordinator: JournalCoordinator): String =
        fs
            .compactAsAnEarlierBuild(
                fs.journalPaths<JournalPath.ObjectV2>() + fs.journalPaths<JournalPath.SnapshotV2>(),
            ).also { coordinator.reconcileMetadata() }

    private suspend fun v2Count() = fs.journalPaths<JournalPath.ObjectV2>().size

    private suspend fun snapCount() = fs.journalPaths<JournalPath.SnapshotV2>().size

    @Test
    fun aLibraryAnEarlierBuildCompactedFoldsAndStaysWritable() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator()
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

            compactAll(coordinator)
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
            val coordinator = coordinator()
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
                fs.journalObjects()
            val stash = rig.scratchDir()
            val moved =
                v2Files.map { file ->
                    val target = stash.resolve(file.name)
                    Files.move(libRoot.resolve(file.relPath), target)
                    file to target
                }

            // Pending supersession: events still applied locally, so reconcile must NOT hard-block.
            repeat(3) { coordinator.reconcileMetadata() }
            db
                .metadataProjectionDao()
                .state()
                .shouldNotBeNull()
                .migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 5

            // Now the snapshot arrives (build it from the stashed objects' events via the compactor).
            // Simpler: restore the objects then compact locally to produce a real snapshot, then delete.
            moved.forEach { (file, target) -> Files.move(target, libRoot.resolve(file.relPath)) }
            coordinator.reconcileMetadata()
            compactAll(coordinator)

            // After the snapshot is applied and superseded objects deleted, reconcile stays clean.
            coordinator.reconcileMetadata()
            db
                .metadataProjectionDao()
                .state()
                .shouldNotBeNull()
                .migrationState shouldBe ProjectionStatus.READY
            db.tagDao().tagsForDocument(docId).size shouldBe 5
        }

    @Test
    fun aSupersededObjectLeftOnDiskIsDeletedByTheNextReconcile() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator()
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
                fs.journalObjects().first()
            val kept = rig.scratchDir().resolve(leftover.name)
            Files.copy(libRoot.resolve(leftover.relPath), kept, StandardCopyOption.COPY_ATTRIBUTES)
            compactAll(coordinator)
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
            val coordinator = coordinator()
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = rig.scratchDir().resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            compactAll(coordinator)
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
            val coordinator = coordinator()
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val backup = rig.scratchDir()
            val objects = fs.journalPaths<JournalPath.ObjectV2>()
            objects.forEach {
                Files.copy(
                    libRoot.resolve(it),
                    backup.resolve(NpnPaths.fileName(it)),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
            compactAll(coordinator)

            fs.journalPaths<JournalPath.SnapshotV2>().forEach { Files.delete(libRoot.resolve(it)) }
            objects.forEach {
                Files.copy(
                    backup.resolve(NpnPaths.fileName(it)),
                    libRoot.resolve(it),
                    StandardCopyOption.COPY_ATTRIBUTES,
                )
            }
            coordinator.reconcileMetadata()

            fs.journalPaths<JournalPath.ObjectV2>() shouldContainExactlyInAnyOrder objects
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            TempIndexDb("npn-compact-rebuild").use { rebuilt ->
                val state = MetadataAccumulator(fs, rebuilt.db).reconcile().state
                state.shouldNotBeNull().effectiveTags(docId).size shouldBe 6
            }
        }

    /** A snapshot that no longer reads as what was applied fails the pass before its leftovers go. */
    @Test
    fun aChangedSnapshotFailsThePassBeforeAnythingIsDeleted() =
        runTest {
            rig.writePdf("a.pdf", "alpha")
            val coordinator = coordinator()
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = rig.scratchDir().resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            compactAll(coordinator)
            Files.copy(kept, libRoot.resolve(leftover), StandardCopyOption.COPY_ATTRIBUTES)

            val snapshot = libRoot.resolve(fs.journalPaths<JournalPath.SnapshotV2>().single())
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
            val coordinator = coordinator()
            rig.rescan()
            coordinator.refreshState()
            val docId = checkNotNull(db.documentDao().rowAtPath("a.pdf")).docId
            repeat(6) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "a$i")))
            }
            coordinator.reconcileMetadata()
            val leftover = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = rig.scratchDir().resolve(NpnPaths.fileName(leftover))
            Files.copy(libRoot.resolve(leftover), kept, StandardCopyOption.COPY_ATTRIBUTES)
            compactAll(coordinator)
            repeat(4) { i ->
                clock.advance()
                coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "b$i")))
            }
            coordinator.reconcileMetadata()
            compactAll(coordinator)
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

            val snapshots = SnapshotSupport(fs, dao)

            suspend fun SourceSetValidator.judge(files: Map<String, FileMeta>): String? {
                val current = dao.allSources()
                return unexplainedSourceLoss(
                    lost,
                    current,
                    files,
                    snapshots.carriedByListed(files.keys, current).hashes,
                )
            }
            val withSnapshot = SourceSetValidator(dao)
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT + 1) {
                withSnapshot.judge(snapshotOnDisk) shouldBe null
            }
            val withoutSnapshot = SourceSetValidator(dao)
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { withoutSnapshot.judge(emptyMap()) shouldBe null }
            withoutSnapshot.judge(emptyMap()).shouldNotBeNull()
        }

    /** A pass that hard-blocks on one lost object still counts the grace window of those after it. */
    @Test
    fun aHardLossStillCountsTheLossesListedAfterIt() =
        runTest {
            val first = "a".repeat(64)
            val second = "c".repeat(64)
            val lost =
                listOf(
                    source("${NpnPaths.JOURNAL_DIR}/objects-v2-aa/$first.jsonl", SourceKind.OBJECT, first),
                    source("${NpnPaths.JOURNAL_DIR}/objects-v2-cc/$second.jsonl", SourceKind.OBJECT, second),
                )
            val validator = SourceSetValidator(db.metadataProjectionDao())

            suspend fun judge(previous: List<MetadataSourceEntity>) =
                validator.unexplainedSourceLoss(previous, emptyList(), emptyMap(), emptySet())
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { judge(lost) shouldBe null }

            judge(lost).shouldNotBeNull()

            // The second object's window was spent in the same passes as the first's, not restarted.
            judge(lost.drop(1)).shouldNotBeNull()
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
            val coordinator = coordinator()
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
                fs.journalObjects().first()
            Files.delete(libRoot.resolve(victim.relPath))

            // Tolerated for the grace window, then escalates to a hard block.
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { coordinator.reconcileMetadata() }
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            db
                .metadataProjectionDao()
                .state()
                .shouldNotBeNull()
                .migrationState shouldBe ProjectionStatus.FAILED
        }
}
