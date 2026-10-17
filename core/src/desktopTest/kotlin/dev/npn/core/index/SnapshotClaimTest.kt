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
 * A snapshot whose manifest names an object it holds none of the events of, as another build's or a
 * faulty one's could: a manifest vouches for an object only with that object's events.
 */
class SnapshotClaimTest {
    private val rig = IndexRig.create()
    private val coordinator =
        rig.coordinator(compaction = rig.compaction(3))

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
