package dev.npn.core.sim

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.util.NpnLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The simulation's own checks fail when the library breaks: a harness whose invariants never fire
 * would pass every seed and prove nothing.
 */
class SimulationHarnessTest {
    private fun config() = SimConfig.forSeed(HARNESS_SEED, STEPS).copy(quiesceEvery = STEPS * 2)

    /** Plant a document at the first step, so the library has one to break. */
    private val plant: Simulation.(Int) -> Unit = { step ->
        if (step == 1) {
            val bytes = SimPdf.bytes("planted", "x", PLANTED_SIZE)
            world.read("plant") { replica.session!!.importPdfBytes(bytes, "planted.pdf") }
        }
    }

    @Test
    fun theRebuildOracleSeesALibraryThatDiffers() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    Simulation(testScheduler, config(), afterStep = plant, tamperWithRebuild = { copy ->
                        copy
                            .allFiles()
                            .keys
                            .filter { NpnPaths.indexable(FileMeta(it, NpnPaths.fileName(it), 1, 0, false)) }
                            .forEach(copy::externalDelete)
                    }).run()
                }
            failure.message shouldContain "rebuild oracle"
            failure.message shouldContain "-Dnpn.sim.seed=$HARNESS_SEED"
        }

    @Test
    fun aRewrittenJournalObjectBreaksWriteOnce() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    Simulation(testScheduler, config(), afterStep = { step ->
                        plant(step)
                        if (step == 2) {
                            val path = fs.allFiles().keys.first { JournalPaths.classify(it) is JournalPath.ObjectV2 }
                            world.read("rewrite") {
                                fs.delete(path)
                                fs.writeBytesOnce(path, "{}\n".encodeToByteArray())
                            }
                        }
                    }).run()
                }
            failure.message shouldContain "write-once broken"
        }

    @Test
    fun aLoggedProgrammingErrorFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    Simulation(testScheduler, config(), afterStep = { step ->
                        val broken = IllegalStateException("broken check")
                        if (step == 1) world.log.log(NpnLog.Level.WARN, "Something failed", broken)
                    }).run()
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
