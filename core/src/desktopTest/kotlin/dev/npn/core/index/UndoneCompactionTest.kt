package dev.npn.core.index

import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.compactAsAnEarlierBuild
import io.kotest.matchers.booleans.shouldBeTrue
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
 * A folder put back from a backup taken before an earlier build compacted it: the objects the
 * compaction replaced are back, and the snapshot that replaced them is gone.
 */
class UndoneCompactionTest {
    private val rig = IndexRig.create()
    private val coordinator = rig.coordinator()

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

    /** Compact every object and snapshot on disk as an earlier build did, and reconcile what it left. */
    private suspend fun compactAll() {
        rig.fs.compactAsAnEarlierBuild(journalFiles<JournalPath.ObjectV2>() + journalFiles<JournalPath.SnapshotV2>())
        coordinator.reconcileMetadata()
    }

    /** The tags a library rebuilt from the folder alone gives [docId]. */
    private suspend fun rebuiltTags(docId: String): Set<String> =
        TempIndexDb("npn-compact-rebuild").use { rebuilt ->
            val state = MetadataAccumulator(rig.fs, rebuilt.db).reconcile().state
            state.shouldNotBeNull().effectiveTags(docId)
        }

    /**
     * The objects hold every event the snapshot did, so its loss is explained: writes go on, and the
     * index forgets the snapshot, as a rebuild from the folder would.
     */
    @Test
    fun aCompactionUndoneByARestoreBlocksNothing() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "tag$it" })
            val objects = journalFiles<JournalPath.ObjectV2>()
            val backup = backUp(objects)
            compactAll()
            val snapshot = journalFiles<JournalPath.SnapshotV2>().single()

            Files.delete(rig.libRoot.resolve(snapshot))
            putBack(backup, objects)
            repeat(2) { coordinator.reconcileMetadata() }

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
        }

    /**
     * After a chain compaction the folder held only the second snapshot. Put back from before the
     * first, it holds every object again: the second snapshot's events are carried through the
     * first, which is gone too.
     */
    @Test
    fun aChainOfCompactionsUndoneByARestoreBlocksNothing() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "a$it" })
            val first = journalFiles<JournalPath.ObjectV2>()
            val firstBackup = backUp(first)
            compactAll()
            tagOneByOne(docId, List(4) { "b$it" })
            val second = journalFiles<JournalPath.ObjectV2>()
            val secondBackup = backUp(second)
            compactAll()

            Files.delete(rig.libRoot.resolve(journalFiles<JournalPath.SnapshotV2>().single()))
            putBack(firstBackup, first)
            putBack(secondBackup, second)
            repeat(2) { coordinator.reconcileMetadata() }

            checkNotNull(rig.db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.READY
            rig.db
                .tagDao()
                .tagsForDocument(docId)
                .size shouldBe 10
            rebuiltTags(docId).size shouldBe 10
        }

    /** A snapshot gone with one of the objects it replaced still blocks writes: its events are not all on disk. */
    @Test
    fun aSnapshotGoneWithOneOfItsObjectsStillBlocksWrites() =
        runTest {
            val docId = aDocument()
            tagOneByOne(docId, List(6) { "tag$it" })
            val objects = journalFiles<JournalPath.ObjectV2>()
            val backup = backUp(objects)
            compactAll()
            val snapshot = journalFiles<JournalPath.SnapshotV2>().single()

            Files.delete(rig.libRoot.resolve(snapshot))
            putBack(backup, objects.drop(1))

            val blocked = assertFailsWith<MetadataWriteBlocked> { coordinator.reconcileMetadata() }
            blocked.message shouldContain snapshot
            checkNotNull(rig.db.metadataProjectionDao().state()).migrationState shouldBe ProjectionStatus.FAILED
        }
}
