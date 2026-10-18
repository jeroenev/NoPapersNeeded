package dev.npn.core.index

import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.earlierBuildSnapshot
import dev.npn.core.journal.journalObjects
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * A snapshot whose manifest names an object it holds none of the events of, as another build's or a
 * faulty one's could: a manifest vouches for an object only with that object's events.
 */
class SnapshotClaimTest {
    private val rig = IndexRig.create()
    private val coordinator = rig.coordinator()

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
                val state = MetadataAccumulator(rig.fs, rebuilt.db).reconcile().state
                state.shouldNotBeNull().effectiveTags(docId) shouldBe setOf("tag0", "tag1")
            }
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
                .journalObjects()
                .first()
                .relPath
        val hash = (JournalPaths.classify(victim) as JournalPath.ObjectV2).expectedHash
        val bytes = earlierBuildSnapshot(listOf(hash), emptyList())
        val snapshot = rig.libRoot.resolve(JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true))
        Files.createDirectories(snapshot.parent)
        Files.write(snapshot, bytes)
        return docId to victim
    }
}
