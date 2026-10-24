package dev.npn.core.sim

import dev.npn.core.app.ImportOutcome
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.toEventEntity
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.xmp.BakeDisplacedException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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

    /**
     * The at-rest checks pass over a trashed document's bytes back at its path within the restore
     * settle window only when another replica trashed it or something outside the app put them back
     * since. This replica's own trash whose file the app itself put back (a move undone after a
     * publication that failed but landed) is a finding: the next rescan past the window would
     * un-trash it. A single replica's rescan shows such a file for that scan in the live index and
     * in a rebuild alike, so the exemption is checked here directly.
     */
    @Test
    fun aTrashTheAppItselfUndidIsNoSettlingReturn() =
        runTest {
            var appPutBack: Set<Pair<String, String>>? = null
            var outsidePutBack: Set<Pair<String, String>>? = null
            var trashed: Pair<String, String>? = null
            Simulation(testScheduler, config(), afterStep = { step ->
                if (step == STEPS) {
                    fs.faults.disarmAll()
                    val bytes = SimPdf.bytes("trashed", "y", PLANTED_SIZE + 1)
                    val (docId, from) =
                        world.read("import and trash") {
                            val session = replica.session!!
                            val imported = session.importPdfBytes(bytes, "trashed.pdf")
                            val docId = imported.shouldBeInstanceOf<ImportOutcome.Imported>().docId
                            session.docLifecycle.trashDoc(docId)
                            docId to
                                replica.indexDb
                                    .rows("SELECT trashedOrigPath FROM documents WHERE docId = '$docId'")
                                    .single()[0]!!
                        }
                    trashed = from to docId
                    world.read("the app puts the file back") { fs.writeBytesOnce(from, bytes) }
                    appPutBack = settlingReturns()
                    fs.externalWriteAtomic(from, bytes)
                    outsidePutBack = settlingReturns()
                }
            }).run()
            appPutBack.shouldNotBeNull() shouldNotContain trashed
            outsidePutBack.shouldNotBeNull() shouldContain trashed
        }

    /**
     * A bake undone while a third file sits at its path is a race with whoever wrote that file: the
     * outside world (another process, the sync tool) may, the app may not, since its own import,
     * rename, restore or storage-path move taking the freed name is one the bake lock should have
     * kept out. Whoever made the last write there decides, even when that file is gone by the time
     * the failure is logged (the bake looks at the path again before it logs).
     */
    @Test
    fun aBakeDisplacedByAFileTheAppWroteFailsTheRun() =
        runTest {
            fun Simulation.displaceABake(
                byApp: Boolean,
                goneByTheLog: Boolean = false,
            ) {
                val bytes = SimPdf.bytes("displacing", "z", PLANTED_SIZE + 2)
                if (byApp) {
                    world.read("the app writes at the bake's path") { fs.writeBytesOnce(DISPLACED, bytes) }
                } else {
                    fs.externalWriteAtomic(DISPLACED, bytes)
                }
                if (goneByTheLog) fs.externalMove(DISPLACED, "moved away.pdf")
                world.log.log(
                    NpnLog.Level.WARN,
                    "Restoring $DISPLACED after a failed bake failed; the original is kept",
                    BakeDisplacedException(DISPLACED, "$DISPLACED holds another file"),
                )
            }
            for (goneByTheLog in listOf(false, true)) {
                Simulation(testScheduler, config(), afterStep = { step ->
                    if (step == 1) displaceABake(byApp = false, goneByTheLog = goneByTheLog)
                }).run()
                val failure =
                    shouldThrow<SimFailure> {
                        Simulation(testScheduler, config(), afterStep = { step ->
                            if (step == 1) displaceABake(byApp = true, goneByTheLog = goneByTheLog)
                        }).run()
                    }
                failure.message shouldContain "a bug was logged"
            }
        }

    /**
     * A trash moves the document's file before its commit stamps the trash, and the stamp is an HLC,
     * which a device whose clock runs ahead pushes past this one's clock: the outside world putting
     * the bytes back after the move counts as their return from outside, though it is earlier than
     * the trash's stamp.
     */
    @Test
    fun aReturnAfterTheTrashsMoveButBeforeItsStampIsASettlingReturn() =
        runTest {
            var returned: Set<Pair<String, String>>? = null
            var trashed: Pair<String, String>? = null
            var stampAhead = false
            Simulation(testScheduler, config(), afterStep = { step ->
                if (step == STEPS) {
                    fs.faults.disarmAll()
                    val elsewhere = InMemoryLibraryFileSystem(world.clock::nowMillis)
                    val ahead =
                        ContentAddressedJournalWriter(
                            elsewhere,
                            "clock ahead",
                            HlcWatermarkAllocator(EpochClock { world.clock.nowMillis() + HOUR_MILLIS }),
                        )
                    val remote = world.read("a device whose clock runs ahead") { ahead.commit(listOf(PendingOp.tagStyle("ahead", "#00ff00", null))) }
                    fs.externalWriteAtomic(remote.relPath, elsewhere.bytesAt(remote.relPath)!!)
                    val bytes = SimPdf.bytes("trashed ahead", "y", PLANTED_SIZE + 5)
                    val (docId, from) =
                        world.read("ingest, import and trash") {
                            val session = replica.session!!
                            session.rescan()
                            val docId = session.importPdfBytes(bytes, "trashed.pdf").shouldBeInstanceOf<ImportOutcome.Imported>().docId
                            session.docLifecycle.trashDoc(docId)
                            docId to
                                replica.indexDb
                                    .rows("SELECT trashedOrigPath FROM documents WHERE docId = '$docId'")
                                    .single()[0]!!
                        }
                    trashed = from to docId
                    fs.externalWriteAtomic(from, bytes)
                    val trashedAt =
                        world.read("the trash's stamp") {
                            replica.indexDb
                                .rows("SELECT trashedAt FROM documents WHERE docId = '$docId'")
                                .single()[0]!!
                                .toLong()
                        }
                    stampAhead = trashedAt > fs.lastWriteAt(from)!!.atMillis
                    returned = settlingReturns()
                }
            }).run()
            stampAhead shouldBe true
            returned.shouldNotBeNull() shouldContain trashed
        }

    /**
     * A relink an older build wrote, with no copies, that the index folded and nothing copied after
     * (what a watch ingest left before the session copied there too) fails the check of relink
     * copies, before the quiescent rescan would write them.
     */
    @Test
    fun aBareRelinkNothingCopiedFailsTheRun() =
        runTest {
            val failure =
                shouldThrow<SimFailure> {
                    Simulation(testScheduler, config(), afterStep = { step ->
                        if (step == STEPS) {
                            fs.faults.disarmAll()
                            world.read("a bare relink, folded without its copies") {
                                val session = replica.session!!
                                val old =
                                    session
                                        .importPdfBytes(SimPdf.bytes("old", "u", PLANTED_SIZE + 3), "old.pdf")
                                        .shouldBeInstanceOf<ImportOutcome.Imported>()
                                        .docId
                                val new =
                                    session
                                        .importPdfBytes(SimPdf.bytes("new", "u", PLANTED_SIZE + 4), "new.pdf")
                                        .shouldBeInstanceOf<ImportOutcome.Imported>()
                                        .docId
                                session.meta.addTag(listOf(old), "kept")
                                val elsewhere = InMemoryLibraryFileSystem(world.clock::nowMillis)
                                val olderBuild =
                                    ContentAddressedJournalWriter(
                                        elsewhere,
                                        "older",
                                        HlcWatermarkAllocator(world.clock),
                                    )
                                val relink = olderBuild.commit(listOf(PendingOp.relink(old, new))).relPath
                                val read = StorageV2Reader.read(relink, elsewhere.bytesAt(relink)!!)
                                read.shouldBeInstanceOf<JournalSourceRead.Accepted>().events.forEach {
                                    replica.indexDb.metadataProjectionDao().insertEvent(it.toEventEntity())
                                }
                            }
                        }
                    }).run()
                }
            failure.message shouldContain "relink targets lack what an older build shows them"
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
        const val DISPLACED = "baked.pdf"
        const val HARNESS_SEED = 7L
        const val STEPS = 30
        const val PLANTED_SIZE = 900
        const val HOUR_MILLIS = 60L * 60L * 1000L
    }
}
