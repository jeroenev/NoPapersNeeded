package dev.npn.core.index

import dev.npn.core.app.Compaction
import dev.npn.core.app.JournalCoordinator
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
    private lateinit var libRoot: Path
    private lateinit var fs: JvmLibraryFileSystem
    private val temp = TempIndexDb("npn-compact-db")
    private val db = temp.db
    private lateinit var clock: MutableClock
    private lateinit var indexer: Indexer
    private lateinit var accumulator: MetadataAccumulator

    // A large "now" so that files (real JVM mtimes near epoch-now) are older than the window.
    private val farFuture = 4_000_000_000_000L
    private val window = 1_000L

    @BeforeTest
    fun setup() {
        libRoot = Files.createTempDirectory("npn-compact-lib")
        fs = JvmLibraryFileSystem(libRoot, PathExternalIo)
        clock = MutableClock(1_000L)
        indexer = Indexer(fs, db, clock)
        accumulator = MetadataAccumulator(fs, db, installationId = "test-installation")
    }

    @AfterTest
    fun teardown() {
        libRoot.toFile().deleteRecursively()
        temp.close()
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
            compaction =
                Compaction(
                    SnapshotCompactor(fs, "test-installation"),
                    SnapshotSupport(fs, db.metadataProjectionDao()),
                    threshold = threshold,
                    stabilityWindowMs = window,
                ),
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

    private suspend fun v2Count() = journalFiles<JournalPath.ObjectV2>().size

    private suspend fun snapCount() = journalFiles<JournalPath.SnapshotV2>().size

    /** The paths of the journal files classified as [T]. */
    private suspend inline fun <reified T : JournalPath> journalFiles(): List<String> =
        fs.listAllUnder(NpnPaths.JOURNAL_DIR).map { it.relPath }.filter { JournalPaths.classify(it) is T }

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

    /**
     * A leftover whose bytes changed since (a sync conflict wrote over it) is still swept: its events
     * are in the snapshot on disk, which the pass reads instead of it, so it fails nothing.
     */
    @Test
    fun aSupersededObjectLeftOnDiskWithOtherBytesIsDeletedByTheNextReconcile() =
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
            writePdf("a.pdf", "alpha")
            val coordinator = coordinator(threshold = 3)
            initialScan()
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

/**
 * A folder put back from a backup taken before a compaction: the objects the compaction replaced
 * are back, and the snapshot that replaced them is gone.
 */
class UndoneCompactionTest {
    private val rig = IndexRig.create()
    private val coordinator =
        JournalCoordinator(
            writer = rig.writer(),
            indexer = rig.indexer,
            accumulator = rig.accumulator,
            compaction =
                Compaction(
                    SnapshotCompactor(rig.fs, "test-installation"),
                    SnapshotSupport(rig.fs, rig.db.metadataProjectionDao()),
                    threshold = 3,
                    stabilityWindowMs = 1_000L,
                ),
        )

    // A large "now" so that files (real JVM mtimes near epoch-now) are older than the window.
    private val farFuture = 4_000_000_000_000L

    @AfterTest
    fun teardown() = rig.close()

    /** The id of a document the library indexed and the coordinator folded. */
    private suspend fun aDocument(): String {
        rig.writePdf("a.pdf", "alpha")
        rig.rescan()
        coordinator.refreshState()
        return checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
    }

    /** Tag [docId] with each of [tags], one transaction apiece, then reconcile. */
    private suspend fun tagOneByOne(
        docId: String,
        tags: List<String>,
    ) {
        for (tag in tags) {
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, tag)))
        }
        coordinator.reconcileMetadata()
    }

    /** The paths of the journal files classified as [T]. */
    private suspend inline fun <reified T : JournalPath> journalFiles(): List<String> =
        rig.fs
            .listAllUnder(NpnPaths.JOURNAL_DIR)
            .map { it.relPath }
            .filter { JournalPaths.classify(it) is T }

    /** A copy of the library files at [paths], mtimes and all, as a backup holds them. */
    private fun backUp(paths: List<String>): Path {
        val backup = Files.createTempDirectory("npn-backup")
        for (path in paths) {
            Files.copy(rig.libRoot.resolve(path), backup.resolve(NpnPaths.fileName(path)), COPY_ATTRIBUTES)
        }
        return backup
    }

    /** Put the files at [paths] back from [backup]. */
    private fun putBack(
        backup: Path,
        paths: List<String>,
    ) {
        for (path in paths) {
            Files.copy(backup.resolve(NpnPaths.fileName(path)), rig.libRoot.resolve(path), COPY_ATTRIBUTES)
        }
    }

    /** The tags a library rebuilt from the folder alone gives [docId]. */
    private suspend fun rebuiltTags(docId: String): Set<String> =
        TempIndexDb("npn-compact-rebuild").use { rebuilt ->
            val state = MetadataAccumulator(rig.fs, rebuilt.db, installationId = "rebuild").reconcile().state
            state.shouldNotBeNull().effectiveTags(docId)
        }

    private suspend fun reconcilePastTheGraceWindow() =
        repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT + 1) { coordinator.reconcileMetadata() }

    /**
     * The objects hold every event the snapshot did, so its loss is explained: writes go on past the
     * grace window, and the next compaction replaces the objects again.
     */
    @Test
    fun aCompactionUndoneByARestoreBlocksNothingAndCompactsAgain() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "tag$it" })
            val objects = journalFiles<JournalPath.ObjectV2>()
            val backup = backUp(objects)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            val snapshot = journalFiles<JournalPath.SnapshotV2>().single()

            Files.delete(rig.libRoot.resolve(snapshot))
            putBack(backup, objects)
            reconcilePastTheGraceWindow()

            val dao = rig.db.metadataProjectionDao()
            checkNotNull(dao.state()).migrationState shouldBe ProjectionStatus.READY
            dao.source(snapshot) shouldBe null
            rig.accumulator
                .loadState()
                .effectiveTags(docId)
                .size shouldBe 6
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "after"))).shouldBeTrue()
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .size shouldBe 7
            rebuiltTags(docId).size shouldBe 7

            coordinator.reconcileMetadata()
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            journalFiles<JournalPath.SnapshotV2>() shouldHaveSize 1
            journalFiles<JournalPath.ObjectV2>().shouldBeEmpty()
            coordinator.reconcileMetadata()
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .size shouldBe 7
            rebuiltTags(docId).size shouldBe 7
        }

    /**
     * After a chain compaction the folder held only the second snapshot. Put back from before the
     * first, it holds every object again: the second snapshot's events are carried through the
     * first, which is gone too.
     */
    @Test
    fun aChainOfCompactionsUndoneByARestoreBlocksNothingAndCompactsAgain() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "a$it" })
            val first = journalFiles<JournalPath.ObjectV2>()
            val firstBackup = backUp(first)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            tagOneByOne(docId, List(4) { "b$it" })
            val second = journalFiles<JournalPath.ObjectV2>()
            val secondBackup = backUp(second)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()

            Files.delete(rig.libRoot.resolve(journalFiles<JournalPath.SnapshotV2>().single()))
            putBack(firstBackup, first)
            putBack(secondBackup, second)
            reconcilePastTheGraceWindow()

            checkNotNull(rig.db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .size shouldBe 10
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            journalFiles<JournalPath.ObjectV2>().shouldBeEmpty()
            coordinator.reconcileMetadata()
            rebuiltTags(docId).size shouldBe 10
        }

    /** A snapshot gone with one of the objects it replaced still escalates: its events are not all on disk. */
    @Test
    fun aSnapshotGoneWithOneOfItsObjectsStillHardBlocks() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "tag$it" })
            val objects = journalFiles<JournalPath.ObjectV2>()
            val backup = backUp(objects)
            coordinator.compact(nowMillis = farFuture).shouldNotBeNull()
            val snapshot = journalFiles<JournalPath.SnapshotV2>().single()

            Files.delete(rig.libRoot.resolve(snapshot))
            putBack(backup, objects.drop(1))
            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { coordinator.reconcileMetadata() }

            val blocked = assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            blocked.message shouldContain snapshot
            checkNotNull(rig.db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.FAILED
        }
}

/**
 * A snapshot an older build compacted: it re-encodes every event it carries from its own index, so
 * the title's `field-def` comes back without `perDocument`, a key that build does not know. That copy
 * is the same event, and the index keeps the one with the flag, whichever arrives first.
 */
class OlderBuildSnapshotTest {
    private val rig = IndexRig.create()
    private val coordinator = rig.coordinator()
    private val title = FieldKeys.seedDefinitions().single { it.key == FieldKeys.TITLE }

    @AfterTest
    fun teardown() = rig.close()

    /** Another device's object defining the title with `perDocument`, on disk and read by nobody yet. */
    private suspend fun titleObject(): PublishedTransaction {
        rig.clock.advance()
        return checkNotNull(rig.writer("device-two").commit(listOf(PendingOp.fieldDefSet(title))))
    }

    /** The snapshot an older build compacts [x] into: x's events without `perDocument`, superseding x. */
    private fun olderBuildSnapshot(x: PublishedTransaction) {
        val events =
            x.events.map { event ->
                val op = event.op as JournalOp.FieldDefSet
                event.copy(op = op.copy(def = op.def?.copy(perDocument = false)))
            }
        val bytes =
            SnapshotCodec.encodeSnapshot(
                SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, listOf(x.contentHash)),
                events,
            )
        val snapshot = rig.libRoot.resolve(JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true))
        Files.createDirectories(snapshot.parent)
        Files.write(snapshot, bytes)
    }

    /** Move [x]'s file out of the library; returns where it went. */
    private fun takeAway(x: PublishedTransaction): Path =
        Files.createTempDirectory("npn-in-transit").resolve(NpnPaths.fileName(x.relPath)).also {
            Files.move(rig.libRoot.resolve(x.relPath), it)
        }

    private suspend fun shouldKeepPerDocument(accumulator: MetadataAccumulator = rig.accumulator) {
        checkNotNull(accumulator.loadState().effectiveFieldDef(FieldKeys.TITLE)).perDocument.shouldBeTrue()
    }

    private suspend fun shouldProjectPerDocument() {
        rig.db
            .fieldDefDao()
            .all()
            .single { it.fieldKey == FieldKeys.TITLE }
            .perDocument
            .shouldBeTrue()
    }

    @Test
    fun theSnapshotAfterTheObjectKeepsTheFlag() =
        runTest {
            coordinator.refreshState()
            val x = titleObject()
            coordinator.reconcileMetadata()
            olderBuildSnapshot(x)

            repeat(2) { coordinator.reconcileMetadata() }

            rig.exists(x.relPath).shouldBeTrue()
            shouldKeepPerDocument()
            shouldProjectPerDocument()
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.settingSet("probe", "1"))).shouldBeTrue()
        }

    @Test
    fun theObjectAfterTheSnapshotRestoresTheFlag() =
        runTest {
            coordinator.refreshState()
            val x = titleObject()
            val inTransit = takeAway(x)
            olderBuildSnapshot(x)
            coordinator.reconcileMetadata()
            checkNotNull(rig.accumulator.loadState().effectiveFieldDef(FieldKeys.TITLE)).perDocument.shouldBeFalse()

            Files.move(inTransit, rig.libRoot.resolve(x.relPath))
            repeat(2) { coordinator.reconcileMetadata() }

            rig.exists(x.relPath).shouldBeTrue()
            shouldKeepPerDocument()
            shouldProjectPerDocument()
        }

    /** The folder watch reads the object alone, onto the fold it has. */
    @Test
    fun theObjectTheWatchReadsAfterTheSnapshotRestoresTheFlag() =
        runTest {
            coordinator.refreshState()
            val x = titleObject()
            val inTransit = takeAway(x)
            olderBuildSnapshot(x)
            coordinator.reconcileMetadata()

            Files.move(inTransit, rig.libRoot.resolve(x.relPath))
            coordinator.ingestObject(x.relPath)

            shouldProjectPerDocument()
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.settingSet("probe", "1"))).shouldBeTrue()
            shouldKeepPerDocument()
            coordinator.reconcileMetadata()
            shouldProjectPerDocument()
        }

    /** A fresh index reads the snapshot first: its bucket sorts before the objects'. */
    @Test
    fun aFreshIndexWithBothListedKeepsTheFlag() =
        runTest {
            val x = titleObject()
            olderBuildSnapshot(x)

            coordinator.refreshState()

            shouldKeepPerDocument()
            shouldProjectPerDocument()
            TempIndexDb("npn-older-build").use { fresh ->
                val accumulator = MetadataAccumulator(rig.fs, fresh.db, installationId = "fresh")
                accumulator.reconcile()
                shouldKeepPerDocument(accumulator)
            }
        }
}

/**
 * A snapshot whose manifest names an object it holds none of the events of, as another build's or a
 * faulty one's could: a manifest vouches for an object only with that object's events.
 */
class SnapshotClaimTest {
    private val rig = IndexRig.create()
    private val coordinator =
        JournalCoordinator(
            writer = rig.writer(),
            indexer = rig.indexer,
            accumulator = rig.accumulator,
            compaction =
                Compaction(
                    SnapshotCompactor(rig.fs, "test-installation"),
                    SnapshotSupport(rig.fs, rig.db.metadataProjectionDao()),
                    threshold = 3,
                    stabilityWindowMs = 1_000L,
                ),
        )

    @AfterTest
    fun teardown() = rig.close()

    /** Such a snapshot deletes nothing, and the folder alone still folds every event of the object. */
    @Test
    fun aSnapshotListingAnObjectWithoutItsEventsDeletesNothing() =
        runTest {
            val (docId, victim) = anObjectAndASnapshotThatOnlyNamesIt()

            repeat(2) { coordinator.reconcileMetadata() }

            rig.exists(victim).shouldBeTrue()
            checkNotNull(rig.db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            TempIndexDb("npn-compact-rebuild").use { rebuilt ->
                val state = MetadataAccumulator(rig.fs, rebuilt.db, installationId = "rebuild").reconcile().state
                state.shouldNotBeNull().effectiveTags(docId) shouldBe setOf("tag0", "tag1")
            }
            // A live object again: the next compaction carries it.
            SnapshotSupport(rig.fs, rig.db.metadataProjectionDao())
                .compactionSources()
                .first
                .map { it.relPath } shouldContain victim
        }

    /** The index keeps no claim it found false: the object's loss is a loss, as with no snapshot. */
    @Test
    fun anObjectASnapshotOnlyNamesIsMissedWhenItGoes() =
        runTest {
            val (_, victim) = anObjectAndASnapshotThatOnlyNamesIt()
            coordinator.reconcileMetadata()

            rig.deleteFile(victim)

            repeat(SourceSetValidator.PENDING_SUPERSESSION_LIMIT) { coordinator.reconcileMetadata() }
            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }.message shouldContain victim
        }

    /**
     * A document tagged in two transactions, and beside them a snapshot whose manifest supersedes
     * one of those objects but holds none of its events. Returns the document and that object.
     */
    private suspend fun anObjectAndASnapshotThatOnlyNamesIt(): Pair<String, String> {
        rig.writePdf("a.pdf", "alpha")
        rig.rescan()
        coordinator.refreshState()
        val docId = checkNotNull(rig.db.documentDao().rowAtPath("a.pdf")).docId
        repeat(2) { i ->
            rig.clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "tag$i")))
        }
        coordinator.reconcileMetadata()
        val victim =
            rig.fs
                .listAllUnder(NpnPaths.JOURNAL_DIR)
                .map { it.relPath }
                .first { JournalPaths.classify(it) is JournalPath.ObjectV2 }
        val hash = (JournalPaths.classify(victim) as JournalPath.ObjectV2).expectedHash
        val bytes =
            SnapshotCodec.encodeSnapshot(
                SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, listOf(hash)),
                emptyList(),
            )
        val snapshot = rig.libRoot.resolve(JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true))
        Files.createDirectories(snapshot.parent)
        Files.write(snapshot, bytes)
        return docId to victim
    }
}
