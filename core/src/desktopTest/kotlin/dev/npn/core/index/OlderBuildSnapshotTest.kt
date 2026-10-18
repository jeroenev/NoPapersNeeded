package dev.npn.core.index

import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.PublishedTransaction
import dev.npn.core.journal.earlierBuildSnapshot
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test

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
        return rig.writer("device-two").commit(listOf(PendingOp.fieldDefSet(title)))
    }

    /** The snapshot an older build compacts [x] into: x's events without `perDocument`, superseding x. */
    private fun olderBuildSnapshot(x: PublishedTransaction) {
        val events =
            x.events.map { event ->
                val op = event.op as JournalOp.FieldDefSet
                event.copy(op = op.copy(def = op.def?.copy(perDocument = false)))
            }
        val bytes = earlierBuildSnapshot(listOf(x.contentHash), events)
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
                val accumulator = MetadataAccumulator(rig.fs, fresh.db)
                accumulator.reconcile()
                shouldKeepPerDocument(accumulator)
            }
        }
}
