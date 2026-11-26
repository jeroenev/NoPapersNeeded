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
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
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

    /** A run whose every replica is there from the start, so a test can reach into any of them at its first steps. */
    private fun TestScope.allFromTheStart(afterStep: SyncSimulation.(step: Int) -> Unit): SyncSimulation {
        val config = config()
        val sync = SyncConfig.forSeed(config).let { it.copy(joinsAt = List(it.replicas) { 0 }) }
        return SyncSimulation(testScheduler, config, afterStep, sync)
    }

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

    /**
     * A replica given the folder later starts with nothing, is filled by the tool while the app is
     * open on it, and at the end shows the library the others show: the final full sync's
     * convergence check passes, and its last quiescence counts some live documents, as many as the
     * first replica's. While this build writes no doc-undelete
     * ([KnownIssues.DELETED_RETURNS_UNRECORDED]) a document deleted for good that an import brought
     * back shows on the importing replica alone, which the convergence check excuses; the counts
     * then may differ by it, so they are compared only once that is fixed.
     */
    @Test
    fun aReplicaJoiningLateWithAnEmptyFolderConvergesWithTheOthers() =
        runTest {
            val base = config(JOIN_STEPS)
            val sync =
                SyncConfig.forSeed(base).let {
                    it.copy(
                        joinsAt =
                            List(it.replicas) { i ->
                                if (i ==
                                    0
                                ) {
                                    0
                                } else {
                                    JOIN_AT
                                }
                            },
                    )
                }
            val simulation = SyncSimulation(testScheduler, base, sync = sync)
            simulation.run()
            simulation.trace.any { "r1 joins with an empty folder" in it }.shouldBeTrue()

            fun liveAtTheEnd(name: String) =
                simulation.trace
                    .last { "[$name] quiescent: " in it }
                    .substringAfter("quiescent: ")
                    .substringBefore(" live")
                    .toInt()
            val first = liveAtTheEnd("r0")
            first shouldBeGreaterThan 0
            (1 until sync.replicas).forEach {
                val live = liveAtTheEnd("r$it")
                live shouldBeGreaterThan 0
                if (!KnownIssues.DELETED_RETURNS_UNRECORDED) live shouldBe first
            }
        }

    @Test
    fun twoVersionsOfAWriteOnceNameFailTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        if (step == 2) {
                            val path = ".npn/ocr/${"a".repeat(HASH)}.device.tesseract.json"
                            members[0].fs.externalWriteAtomic(path, "{\"one\":1}".encodeToByteArray())
                            members[1].fs.externalWriteAtomic(path, "{\"two\":2}".encodeToByteArray())
                            tool.scan(0, immediate = true)
                        }
                    }.run()
                }
            failure.message shouldContain "two versions of a name written once"
        }

    @Test
    fun aReplicaDeletingAJournalObjectFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        if (step == 2) world.read("delete") { members[0].fs.delete(anObject(0)) }
                    }.run()
                }
            failure.message shouldContain "deleted the journal object"
        }

    /** The tool delivers every journal object whole, so a journal a library finds damaged is a bug. */
    @Test
    fun aDamagedJournalObjectFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        if (step == 2) {
                            members[1].fs.externalOverwriteInPlace(anObject(1), "{}\n".encodeToByteArray())
                            world.run("rescan") { members[1].replica.session!!.rescan() }
                        }
                    }.run()
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

    /** What the outside world took of the same bytes before the app took the last copy excuses nothing. */
    @Test
    fun theAppDeletingALastCopyAfterAnOutsideLossOfTheSameBytesFailsTheRun() =
        runTest {
            val failure = appDeletesTheLastCopy(outsideLoss = OutsideLoss.BEFORE)
            failure.message shouldContain "the app removed the last copy"
        }

    /** Nor does what the outside world took of them afterwards: the app's loss of the last copy stands. */
    @Test
    fun anOutsideLossOfTheSameBytesAfterTheAppDeletedTheLastCopyExcusesNothing() =
        runTest {
            val failure = appDeletesTheLastCopy(outsideLoss = OutsideLoss.AFTER)
            failure.message shouldContain "the app removed the last copy"
        }

    /** The app deleting a copy in its trash excuses nothing for a document the journal does not have there. */
    @Test
    fun theAppDeletingALiveDocumentsLastCopyInItsTrashFailsTheRun() =
        runTest {
            appDeletesTheLastCopy(trashCopyLast = true).message shouldContain "the app removed the last copy"
        }

    /**
     * A copy another replica holds at another path keeps the library's: the app taking this folder's
     * last copy then is no finding, and here another process takes the other one afterwards.
     */
    @Test
    fun theAppDeletingACopyAnotherReplicaHoldsIsNoFinding() =
        runTest {
            val bytes = SimPdf.bytes("held elsewhere", "v", BAKED_SIZE + 3)
            val losses = ArrayList<LossAt>()
            allFromTheStart { step ->
                if (step == 2) {
                    members[1].fs.externalWriteAtomic("elsewhere.pdf", bytes)
                    world.read("import, then delete as the app") {
                        val imported = members[0].replica.session!!.importPdfBytes(bytes, "here.pdf")
                        members[0].fs.delete((imported as ImportOutcome.Imported).relPath)
                    }
                    members[1].fs.externalDelete("elsewhere.pdf")
                    losses += members[0].fs.losses.recordsOf(sha256Bytes(bytes))
                }
            }.run()
            losses.single { it.loss == Loss.APP }.lastAnywhere shouldBe false
        }

    /** A copy at the same path in another folder keeps nothing: the sync tool carries the app's deletion there. */
    @Test
    fun theAppDeletingACopyAnotherReplicaHoldsAtTheSamePathFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    allFromTheStart { step ->
                        if (step == 2) {
                            val bytes = SimPdf.bytes("same path", "v", BAKED_SIZE + 4)
                            val other = members[1].fs
                            world.read("import, then delete as the app") {
                                val imported = members[0].replica.session!!.importPdfBytes(bytes, "same.pdf")
                                val path = (imported as ImportOutcome.Imported).relPath
                                other.externalWriteAtomic(path, bytes)
                                members[0].fs.delete(path)
                                other.bySyncTool { other.externalDelete(path) }
                            }
                        }
                    }.run()
                }
            failure.message shouldContain "the app removed the last copy"
        }

    /**
     * A bake's temporary file holds output no journal names until the bake's relink: the app deleting
     * it, after a failed bake, takes no document's copy, though the same bytes are a document.
     */
    @Test
    fun theAppDeletingABakesTemporaryFileIsNoFinding() =
        runTest {
            val bytes = SimPdf.bytes("baked twice", "v", BAKED_SIZE + 5)
            val losses = ArrayList<LossAt>()
            allFromTheStart { step ->
                if (step == 2) {
                    val fs = members[0].fs
                    val path =
                        world.read("import as the app") {
                            val imported = members[0].replica.session!!.importPdfBytes(bytes, "baked.pdf")
                            (imported as ImportOutcome.Imported).relPath
                        }
                    fs.externalDelete(path)
                    val temp = NpnPaths.Temp.bakePath("other.pdf")
                    world.read("a bake's output, discarded") {
                        fs.writeBytesOnce(temp, bytes)
                        fs.delete(temp)
                    }
                    losses += fs.losses.recordsOf(sha256Bytes(bytes))
                }
            }.run()
            losses.map { it.loss } shouldBe listOf(Loss.OUTSIDE)
        }

    /** When another process writes the bytes elsewhere and deletes them again, if it does. */
    private enum class OutsideLoss { NONE, BEFORE, AFTER }

    /**
     * At step 2, the app imports bytes and then deletes its file: with [outsideLoss], another
     * process writes the same bytes elsewhere and deletes them, before or after; with
     * [trashCopyLast], the last copy it deletes is one it put in its trash, of a document it never
     * trashed.
     */
    private fun TestScope.appDeletesTheLastCopy(
        outsideLoss: OutsideLoss = OutsideLoss.NONE,
        trashCopyLast: Boolean = false,
    ): SimFailure =
        shouldThrow<SimFailure> {
            allFromTheStart { step ->
                if (step == 2) {
                    val fs = members[0].fs
                    val bytes = SimPdf.bytes("only copy", "v", BAKED_SIZE + 1)

                    fun writeAndDeleteElsewhere() {
                        fs.externalWriteAtomic("elsewhere.pdf", bytes)
                        fs.externalDelete("elsewhere.pdf")
                    }
                    if (outsideLoss == OutsideLoss.BEFORE) writeAndDeleteElsewhere()
                    world.read("import, then delete as the app") {
                        val imported = members[0].replica.session!!.importPdfBytes(bytes, "only.pdf")
                        val trashCopy = "${NpnPaths.TRASH_DIR}/only.pdf"
                        if (trashCopyLast) fs.writeBytesOnce(trashCopy, bytes)
                        fs.delete((imported as ImportOutcome.Imported).relPath)
                        if (trashCopyLast) fs.delete(trashCopy)
                    }
                    if (outsideLoss == OutsideLoss.AFTER) writeAndDeleteElsewhere()
                }
            }.run()
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
            allFromTheStart { step ->
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
            }.run()
            answers shouldBe listOf(false, false, false, true)
        }

    private companion object {
        const val BAKED_SIZE = 950
        const val HARNESS_SEED = 3L
        const val STEPS = 20
        const val REPLAY_STEPS = 120
        const val JOIN_STEPS = 60
        const val JOIN_AT = 20
        const val HASH = 64
    }
}
