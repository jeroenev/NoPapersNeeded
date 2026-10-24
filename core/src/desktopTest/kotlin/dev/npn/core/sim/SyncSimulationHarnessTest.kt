package dev.npn.core.sim

import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The checks across replicas fail when the replicas break them, and a seed replays: a harness
 * whose invariants never fire, or whose runs differ from one replay to the next, proves nothing.
 */
class SyncSimulationHarnessTest {
    private fun config(steps: Int = STEPS) = SimConfig.forSeed(HARNESS_SEED, steps).copy(quiesceEvery = steps * 2)

    private fun SyncSimulation.anObject(member: Int): String =
        members[member]
            .fs
            .allFiles()
            .keys
            .first { JournalPaths.classify(it) is JournalPath.ObjectV2 }

    /** Every step, its virtual time and outcome, the sync tool's included: two runs of one seed match line for line. */
    @Test
    fun aSeedReplaysTheSameVirtualTimeline() {
        fun once(): List<String> {
            var trace = emptyList<String>()
            runTest { trace = SyncSimulation(testScheduler, config(REPLAY_STEPS)).also { it.run() }.trace.toList() }
            return trace
        }
        once() shouldBe once()
    }

    @Test
    fun twoVersionsOfAWriteOnceNameFailTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    SyncSimulation(testScheduler, config(), afterStep = { step ->
                        if (step == 2) {
                            val path = ".npn/ocr/${"a".repeat(HASH)}.device.tesseract.json"
                            members[0].fs.externalWriteAtomic(path, "{\"one\":1}".encodeToByteArray())
                            members[1].fs.externalWriteAtomic(path, "{\"two\":2}".encodeToByteArray())
                            tool.scan(0, immediate = true)
                        }
                    }).run()
                }
            failure.message shouldContain "two versions of a name written once"
        }

    @Test
    fun aReplicaDeletingAJournalObjectFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    SyncSimulation(testScheduler, config(), afterStep = { step ->
                        if (step == 2) world.read("delete") { members[0].fs.delete(anObject(0)) }
                    }).run()
                }
            failure.message shouldContain "deleted the journal object"
        }

    @Test
    fun aDamagedJournalNoSyncExplainsFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    SyncSimulation(testScheduler, config(), afterStep = { step ->
                        if (step == 2) {
                            members[1].fs.externalOverwriteInPlace(anObject(1), "{}\n".encodeToByteArray())
                            world.run("rescan") { members[1].replica.session!!.rescan() }
                        }
                    }).run()
                }
            failure.message shouldContain "blocks writes as JOURNAL_DAMAGED"
        }

    /**
     * Bytes another replica relinked are accounted for here (a watched folder's importer may delete
     * their source) only once the bytes they became are in this folder too: until then this folder
     * holds the document nowhere.
     */
    @Test
    fun anotherReplicasRelinkAccountsForBytesOnlyWithItsTargetHere() =
        runTest {
            val original = "a".repeat(HASH)
            val baked = SimPdf.bytes("baked elsewhere", "w", BAKED_SIZE)
            val answers = ArrayList<Boolean>()
            SyncSimulation(testScheduler, config(), afterStep = { step ->
                if (step == 1) {
                    val relinked = members[1]
                    val elsewhere = InMemoryLibraryFileSystem(world.clock::nowMillis)
                    val olderBuild =
                        ContentAddressedJournalWriter(elsewhere, "harness", HlcWatermarkAllocator(world.clock))
                    val published =
                        world.read("a relink") {
                            olderBuild.commit(listOf(PendingOp.relink(original, sha256Bytes(baked))))
                        }
                    relinked.fs.externalWriteAtomic(published.relPath, elsewhere.bytesAt(published.relPath)!!)
                    world.read("the relink's ingest") { relinked.replica.session!!.rescan() }
                    answers += world.read("accounted on the replica that folded it") { relinked.accounted(original) }
                    answers += world.read("accounted without its target") { members[0].accounted(original) }
                    members[0].fs.externalWriteAtomic("baked.pdf", baked)
                    answers += world.read("accounted with its target") { members[0].accounted(original) }
                }
            }).run()
            answers shouldBe listOf(true, false, true)
        }

    private companion object {
        const val BAKED_SIZE = 950
        const val HARNESS_SEED = 3L
        const val STEPS = 20
        const val REPLAY_STEPS = 120
        const val HASH = 64
    }
}
