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
 * A folder put back from a backup taken before a compaction: the objects the compaction replaced
 * are back, and the snapshot that replaced them is gone.
 */
class UndoneCompactionTest {
    private val rig = IndexRig.create()
    private val coordinator =
        rig.coordinator(compaction = rig.compaction(3))

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
