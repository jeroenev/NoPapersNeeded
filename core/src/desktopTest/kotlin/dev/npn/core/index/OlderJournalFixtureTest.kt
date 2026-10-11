package dev.npn.core.index

import dev.npn.core.db.TempIndexDb
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

/** Frozen wire bytes keep compatibility checks independent of this build's encoders. */
class OlderJournalFixtureTest {
    @Test
    fun anOlderSnapshotAndItsRicherObjectConvergeInEitherArrivalOrderAndAfterAnIndexRebuild() =
        runTest {
            for (snapshotFirst in listOf(false, true)) {
                val rig = IndexRig.create()
                try {
                    val coordinator = rig.coordinator()
                    val original = fixture("title-object.jsonl")
                    val snapshot = fixture("older-title-snapshot.jsonl")
                    val arrivals =
                        if (snapshotFirst) {
                            listOf(snapshot to true, original to false)
                        } else {
                            listOf(original to false, snapshot to true)
                        }
                    for ((bytes, isSnapshot) in arrivals) {
                        val path = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = isSnapshot)
                        val file = rig.libRoot.resolve(path)
                        Files.createDirectories(file.parent)
                        Files.write(file, bytes)
                        if (isSnapshot) coordinator.reconcileMetadata() else coordinator.ingestObject(path)
                    }
                    coordinator.reconcileMetadata()
                    rig.accumulator
                        .loadState()
                        .effectiveFieldDef(
                            FieldKeys.TITLE,
                        ).shouldNotBeNull()
                        .perDocument
                        .shouldBeTrue()
                    coordinator.commitAndApply(listOf(PendingOp.settingSet("compatibility-probe", "ok"))).shouldBeTrue()
                    TempIndexDb("npn-frozen-compatibility").use { fresh ->
                        val rebuilt = MetadataAccumulator(rig.fs, fresh.db, installationId = "rebuilt")
                        rebuilt.reconcile()
                        rebuilt
                            .loadState()
                            .effectiveFieldDef(
                                FieldKeys.TITLE,
                            ).shouldNotBeNull()
                            .perDocument
                            .shouldBeTrue()
                    }
                } finally {
                    rig.close()
                }
            }
        }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/journal-compatibility/$name")).use { it.readBytes() }
}
