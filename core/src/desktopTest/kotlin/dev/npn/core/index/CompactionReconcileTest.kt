package dev.npn.core.index

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.compactAsAnEarlierBuild
import dev.npn.core.journal.journalPaths
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
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
    private val coordinator = rig.coordinator()

    @AfterTest
    fun teardown() = rig.close()

    /** A document tagged with each of [tags], one transaction apiece, reconciled. Returns its id. */
    private suspend fun aDocumentTagged(tags: List<String>): String {
        rig.writePdf("a.pdf", "alpha")
        rig.rescan()
        coordinator.refreshState()
        val docId = db.documentDao().requireRowAtPath("a.pdf").docId
        tagOneByOne(docId, tags)
        return docId
    }

    private suspend fun tagOneByOne(
        docId: String,
        tags: List<String>,
    ) {
        for (tag in tags) {
            clock.advance()
            coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, tag)))
        }
        coordinator.reconcileMetadata()
    }

    /** Compact every object and snapshot on disk as an earlier build did; returns the snapshot's path. */
    private suspend fun compactAll(): String =
        fs.compactAsAnEarlierBuild(fs.journalPaths<JournalPath.ObjectV2>() + fs.journalPaths<JournalPath.SnapshotV2>())

    /** A copy of the library file at [path] outside it, mtime and all. */
    private fun keep(path: String): Path =
        rig.scratchDir().resolve(NpnPaths.fileName(path)).also {
            Files.copy(libRoot.resolve(path), it, COPY_ATTRIBUTES)
        }

    private fun putBack(
        kept: Path,
        path: String,
    ) {
        Files.copy(kept, libRoot.resolve(path), COPY_ATTRIBUTES)
    }

    private suspend fun shouldBeReady() {
        checkNotNull(db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
    }

    private suspend fun shouldStayWritable(docId: String) {
        clock.advance()
        coordinator.commitAndApply(listOf(PendingOp.tagAdd(docId, "after"))).shouldBeTrue()
    }

    private suspend fun v2Count() = fs.journalPaths<JournalPath.ObjectV2>().size

    private suspend fun snapCount() = fs.journalPaths<JournalPath.SnapshotV2>().size

    @Test
    fun aLibraryAnEarlierBuildCompactedFoldsAndStaysWritable() =
        runTest {
            val docId = aDocumentTagged(List(6) { "tag$it" })
            v2Count() shouldBe 6
            val tagsBefore = db.tagDao().tagsForDocument(docId).sorted()

            compactAll()
            snapCount() shouldBe 1
            v2Count() shouldBe 0

            coordinator.reconcileMetadata()
            shouldBeReady()
            db.tagDao().tagsForDocument(docId).sorted() shouldContainExactly tagsBefore
            shouldStayWritable(docId)
        }

    /**
     * Another device's earlier build compacted, and its deletions arrived before its snapshot: the
     * objects are gone and nothing on disk holds their events yet. That blocks writes at once, and
     * the snapshot lifts the block when it arrives.
     */
    @Test
    fun aDeletionThatArrivesBeforeItsSnapshotBlocksWritesUntilTheSnapshotArrives() =
        runTest {
            val docId = aDocumentTagged(List(5) { "tag$it" })
            val snapshot = compactAll()
            val inTransit = keep(snapshot)
            Files.delete(libRoot.resolve(snapshot))

            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }.block shouldBe
                WriteBlock.JOURNAL_DAMAGED
            assertFailsWith<MetadataWriteBlocked> { shouldStayWritable(docId) }
            db.tagDao().tagsForDocument(docId).size shouldBe 5

            putBack(inTransit, snapshot)
            coordinator.reconcileMetadata()

            shouldBeReady()
            db.tagDao().tagsForDocument(docId).size shouldBe 5
            shouldStayWritable(docId)
        }

    /** An object the compaction could not delete is an object like any other: read, kept, and counted once. */
    @Test
    fun aSupersededObjectLeftOnDiskIsKept() =
        runTest {
            val docId = aDocumentTagged(List(6) { "tag$it" })
            val leftover = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = keep(leftover)
            compactAll()
            coordinator.reconcileMetadata()
            putBack(kept, leftover)

            repeat(2) { coordinator.reconcileMetadata() }

            rig.exists(leftover).shouldBeTrue()
            shouldBeReady()
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            shouldStayWritable(docId)
        }

    /**
     * A library put back from before a compaction holds the objects and not the snapshot. The index
     * still remembers the snapshot, but nothing on disk carries the objects' events any more, so
     * they stay, and the folder alone still folds to every event.
     */
    @Test
    fun objectsBackWithoutTheSnapshotThatReplacedThemAreKept() =
        runTest {
            val docId = aDocumentTagged(List(6) { "tag$it" })
            val objects = fs.journalPaths<JournalPath.ObjectV2>()
            val backup = objects.associateWith(::keep)
            compactAll()
            coordinator.reconcileMetadata()

            fs.journalPaths<JournalPath.SnapshotV2>().forEach { Files.delete(libRoot.resolve(it)) }
            backup.forEach { (path, kept) -> putBack(kept, path) }
            coordinator.reconcileMetadata()

            fs.journalPaths<JournalPath.ObjectV2>() shouldContainExactlyInAnyOrder objects
            db.tagDao().tagsForDocument(docId).size shouldBe 6
            TempIndexDb("npn-compact-rebuild").use { rebuilt ->
                val state = MetadataAccumulator(fs, rebuilt.db).reconcile().state
                state.shouldNotBeNull().effectiveTags(docId).size shouldBe 6
            }
        }

    /** A snapshot that no longer reads as what was applied fails the pass. */
    @Test
    fun aChangedSnapshotFailsThePass() =
        runTest {
            aDocumentTagged(List(6) { "tag$it" })
            val snapshot = libRoot.resolve(compactAll())
            coordinator.reconcileMetadata()

            Files.write(snapshot, Files.readAllBytes(snapshot) + "\n".encodeToByteArray())

            assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
        }

    /**
     * After a chain compaction the first snapshot is gone, and the objects it replaced are carried
     * by the one that replaced it: a leftover of them is read as any object, and the loss of the rest
     * stays explained.
     */
    @Test
    fun aSnapshotReplacedInTurnStillAccountsForTheObjectsItReplaced() =
        runTest {
            val docId = aDocumentTagged(List(6) { "a$it" })
            val leftover = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = keep(leftover)
            compactAll()
            coordinator.reconcileMetadata()
            tagOneByOne(docId, List(4) { "b$it" })
            compactAll()
            coordinator.reconcileMetadata()
            snapCount() shouldBe 1

            putBack(kept, leftover)
            repeat(2) { coordinator.reconcileMetadata() }

            rig.exists(leftover).shouldBeTrue()
            shouldBeReady()
            db.tagDao().tagsForDocument(docId).size shouldBe 10
        }

    /**
     * Only a listed source holding every event of a gone one explains its loss: a snapshot the index
     * remembers but the folder lost does not, nor one on disk that names the object without its events.
     */
    @Test
    fun aLossIsExplainedOnlyByAListedSourceHoldingEachOfItsEvents() =
        runTest {
            val dao = db.metadataProjectionDao()
            val objectPath = "${NpnPaths.JOURNAL_DIR}/objects-v2-aa/${"a".repeat(64)}.jsonl"
            val holding = "${NpnPaths.JOURNAL_DIR}/objects-snap-v2-bb/${"b".repeat(64)}.jsonl"
            val namingOnly = "${NpnPaths.JOURNAL_DIR}/objects-snap-v2-cc/${"c".repeat(64)}.jsonl"
            dao.ingest(source(objectPath, SourceKind.OBJECT), listOf(event(1), event(2)))
            dao.ingest(source(holding, SourceKind.SNAPSHOT), listOf(event(1), event(2), event(3)))
            dao.ingest(source(namingOnly, SourceKind.SNAPSHOT), listOf(event(1)))
            val gone = listOf(checkNotNull(dao.source(objectPath)))
            val validator = SourceSetValidator(dao)

            validator.unheldEvents(gone, setOf(holding)) shouldBe null
            validator.unheldEvents(gone, setOf(holding, namingOnly)) shouldBe null
            validator.unheldEvents(gone, setOf(namingOnly)).shouldNotBeNull() shouldContain objectPath
            validator.unheldEvents(gone, emptySet()).shouldNotBeNull() shouldContain objectPath
        }

    /** An event of device `dev-a` with sequence [seq], as the index stores it. */
    private fun event(seq: Long) =
        MetadataEventEntity("dev-a", seq, """{"seq":$seq}""", seq, 0, "dev-a", null, "tag-add")

    private fun source(
        path: String,
        kind: SourceKind,
    ): MetadataSourceEntity {
        val hash = NpnPaths.fileName(path).substringBefore('.')
        return MetadataSourceEntity(path, kind, SourceFingerprint(1, 1, hash), hash, SourceStatus.APPLIED)
    }

    /** An object gone with no snapshot superseding it is damage: writes stop at once, until it is back. */
    @Test
    fun anUnexplainedObjectLossBlocksWritesUntilTheObjectIsBack() =
        runTest {
            val docId = aDocumentTagged(List(4) { "tag$it" })
            val victim = fs.journalPaths<JournalPath.ObjectV2>().first()
            val kept = keep(victim)
            Files.delete(libRoot.resolve(victim))

            assertFailsWith<MetadataWriteBlocked> {
                coordinator.reconcileMetadata()
            }.message.shouldNotBeNull() shouldContain
                victim
            checkNotNull(db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.FAILED

            putBack(kept, victim)
            coordinator.reconcileMetadata()

            shouldBeReady()
            db.tagDao().tagsForDocument(docId).size shouldBe 4
            shouldStayWritable(docId)
        }
}
