package dev.npn.core.sim

import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.PendingOp
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.TestScope
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

    /** The tool delivers every journal object whole, so a journal a library finds damaged is a bug. */
    @Test
    fun aDamagedJournalObjectFailsTheRun() =
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
     * A document whose only file the app deleted shows as missing in the live index and in a rebuild
     * alike, so neither oracle sees it: the check of last copies across the replicas does.
     */
    @Test
    fun theAppDeletingADocumentsLastCopyFailsTheRun() =
        runTest {
            appDeletesTheLastCopy().message shouldContain "the app removed the last copy"
        }

    /**
     * What the outside world took of the same bytes earlier excuses nothing: the latest loss decides,
     * and here that is the app's.
     */
    @Test
    fun theAppDeletingALastCopyAfterAnOutsideLossOfTheSameBytesFailsTheRun() =
        runTest {
            appDeletesTheLastCopy(outsideLossFirst = true).message shouldContain "the app removed the last copy"
        }

    /** The app deleting a copy in its trash excuses nothing for a document the journal does not have there. */
    @Test
    fun theAppDeletingALiveDocumentsLastCopyInItsTrashFailsTheRun() =
        runTest {
            appDeletesTheLastCopy(trashCopyLast = true).message shouldContain "the app removed the last copy"
        }

    /**
     * At step 2, the app imports bytes and then deletes its file: with [outsideLossFirst], after
     * another process wrote the same bytes elsewhere and deleted them; with [trashCopyLast], the
     * last copy it deletes is one it put in its trash, of a document it never trashed.
     */
    private fun TestScope.appDeletesTheLastCopy(
        outsideLossFirst: Boolean = false,
        trashCopyLast: Boolean = false,
    ): SimFailure =
        shouldThrow<SimFailure> {
            SyncSimulation(testScheduler, config(), afterStep = { step ->
                if (step == 2) {
                    val fs = members[0].fs
                    val bytes = SimPdf.bytes("only copy", "v", BAKED_SIZE + 1)
                    if (outsideLossFirst) {
                        fs.externalWriteAtomic("elsewhere.pdf", bytes)
                        fs.externalDelete("elsewhere.pdf")
                    }
                    world.read("import, then delete as the app") {
                        val imported = members[0].replica.session!!.importPdfBytes(bytes, "only.pdf")
                        val trashCopy = "${NpnPaths.TRASH_DIR}/only.pdf"
                        if (trashCopyLast) fs.writeBytesOnce(trashCopy, bytes)
                        fs.delete((imported as ImportOutcome.Imported).relPath)
                        if (trashCopyLast) fs.delete(trashCopy)
                    }
                }
            }).run()
        }

    /**
     * Relinked bytes are accounted for in a folder (a watched folder's importer may delete their
     * source) only once the bytes they finally became, the relink chain followed to its end, are in
     * that folder too, whichever replica folded the relinks: until then that folder holds the
     * document nowhere.
     */
    @Test
    fun aRelinkAccountsForBytesOnlyWithItsFinalTargetHere() =
        runTest {
            val original = "a".repeat(HASH)
            val baked = SimPdf.bytes("baked elsewhere", "w", BAKED_SIZE)
            val rebaked = SimPdf.bytes("baked again elsewhere", "w", BAKED_SIZE + 2)
            val answers = ArrayList<Boolean>()
            SyncSimulation(testScheduler, config(), afterStep = { step ->
                if (step == 1) {
                    val relinked = members[1]
                    val elsewhere = InMemoryLibraryFileSystem(world.clock::nowMillis)
                    val olderBuild =
                        ContentAddressedJournalWriter(elsewhere, "harness", HlcWatermarkAllocator(world.clock))
                    val published =
                        world.read("two relinks") {
                            listOf(
                                olderBuild.commit(listOf(PendingOp.relink(original, sha256Bytes(baked)))),
                                olderBuild.commit(listOf(PendingOp.relink(sha256Bytes(baked), sha256Bytes(rebaked)))),
                            )
                        }
                    published.forEach { relinked.fs.externalWriteAtomic(it.relPath, elsewhere.bytesAt(it.relPath)!!) }
                    world.read("the relinks' ingest") { relinked.replica.session!!.rescan() }
                    answers += world.read("accounted on the replica that folded them") { relinked.accounted(original) }
                    answers += world.read("accounted without a target") { members[0].accounted(original) }
                    members[0].fs.externalWriteAtomic("baked.pdf", baked)
                    answers += world.read("accounted with the first target only") { members[0].accounted(original) }
                    members[0].fs.externalWriteAtomic("rebaked.pdf", rebaked)
                    answers += world.read("accounted with the final target") { members[0].accounted(original) }
                }
            }).run()
            answers shouldBe listOf(false, false, false, true)
        }

    private companion object {
        const val BAKED_SIZE = 950
        const val HARNESS_SEED = 3L
        const val STEPS = 20
        const val REPLAY_STEPS = 120
        const val HASH = 64
    }
}
