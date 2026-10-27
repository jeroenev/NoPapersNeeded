package dev.npn.core.sim

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.util.NpnLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Each replica's own checks fail when its library breaks: a harness whose invariants never fire
 * would pass every seed and prove nothing.
 */
class SimulationHarnessTest {
    /** A run whose every replica is there from the start, so a test can reach into any of them at its first steps. */
    private fun TestScope.allFromTheStart(
        tamperWithRebuild: (InMemoryLibraryFileSystem) -> Unit = {},
        afterStep: SyncSimulation.(step: Int) -> Unit,
    ): SyncSimulation {
        val config = SimConfig.forSeed(HARNESS_SEED, STEPS).copy(quiesceEvery = STEPS * 2)
        val sync = SyncConfig.forSeed(config).let { it.copy(joinsAt = List(it.replicas) { 0 }) }
        return SyncSimulation(testScheduler, config, afterStep, sync, tamperWithRebuild)
    }

    /** Plant a document in the first replica at the first step, so the library has one to break. */
    private fun SyncSimulation.plant(step: Int) {
        if (step == 1) {
            val bytes = SimPdf.bytes("planted", "x", PLANTED_SIZE)
            world.read("plant") { members[0].replica.session!!.importPdfBytes(bytes, "planted.pdf") }
        }
    }

    @Test
    fun theRebuildOracleSeesALibraryThatDiffers() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart(
                        tamperWithRebuild = { copy ->
                            copy
                                .allFiles()
                                .keys
                                .filter { NpnPaths.indexable(FileMeta(it, NpnPaths.fileName(it), 1, 0, false)) }
                                .forEach(copy::externalDelete)
                        },
                    ) { step -> plant(step) }.run()
                }
            failure.message shouldContain "rebuild oracle"
            failure.message shouldContain "-Dnpn.sim.seed=$HARNESS_SEED"
        }

    @Test
    fun aRewrittenJournalObjectBreaksWriteOnce() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        plant(step)
                        if (step == 2) {
                            val replica = members[0]
                            val path =
                                replica.fs
                                    .allFiles()
                                    .keys
                                    .first { JournalPaths.classify(it) is JournalPath.ObjectV2 }
                            // Taken away by another process, then written again as the app.
                            replica.fs.externalDelete(path)
                            world.read("rewrite") { replica.fs.writeBytesOnce(path, "{}\n".encodeToByteArray()) }
                            // Checked at once, as the replica's next step would.
                            replica.sim.checkCheap()
                        }
                    }.run()
                }
            failure.message shouldContain "write-once broken"
        }

    @Test
    fun aLoggedProgrammingErrorFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        val broken = IllegalStateException("broken check")
                        if (step == 1) world.log.log(NpnLog.Level.WARN, "Something failed", broken)
                    }.run()
                }
            failure.message shouldContain "a bug was logged"
        }

    @Test
    fun storageWithoutAPublicationPrimitiveRefusesTheLibrary() =
        runTest {
            val world = SimWorld(testScheduler)
            val fs = InMemoryLibraryFileSystem(world.clock::nowMillis, SimFsOptions(profile = StorageProfile.NONE))
            val replica = SimReplica(world, "no-primitive", fs)
            try {
                shouldThrow<VerifiedPublicationUnavailableException> { replica.open() }
            } finally {
                replica.dispose()
                world.dispose()
            }
        }

    private companion object {
        const val HARNESS_SEED = 7L
        const val STEPS = 30
        const val PLANTED_SIZE = 900
    }
}
