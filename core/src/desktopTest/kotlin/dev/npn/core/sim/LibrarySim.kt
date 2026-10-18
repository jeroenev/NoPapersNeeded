package dev.npn.core.sim

import androidx.room3.Room
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.openSession
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.journal.JournalOp
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.EpochClock
import dev.npn.core.util.reason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.random.Random

/**
 * One replica of the library in the simulation: its databases (which outlive a session, as the
 * files on a device do), the session open over them, and the scope that session runs in.
 *
 * Single replica for now. A multi-replica sync model adds more of these over their own
 * [InMemoryLibraryFileSystem]s and a sync process that copies files between them (see
 * docs/SIMULATION.md); everything a replica needs is here, apart from the shared world.
 */
internal class SimReplica(
    private val world: SimWorld,
    val installationId: String,
    val fs: InMemoryLibraryFileSystem,
    val ocr: GatedOcrEngine = GatedOcrEngine(),
) {
    val pdf = SimPdfEngine()
    var indexDb: NpnDatabase = world.newIndexDb()
        private set
    var deviceDb: DeviceDatabase = world.newDeviceDb()
        private set
    private var scopeJob: Job? = null

    /** The open session, or null between a crash or close and the next [open]. */
    var session: LibrarySessionImpl? = null
        private set

    /** Open a session over the replica's files and databases and start it; throws what the open threw. */
    fun open() {
        check(session == null) { "A session is already open" }
        val job = SupervisorJob()
        val scope = CoroutineScope(world.dispatcher + job + world.uncaughtHandler(installationId))
        val deps =
            SessionDeps(
                fs = fs,
                db = indexDb,
                deviceDb = deviceDb,
                pdfEngine = pdf,
                ocrEngine = ocr,
                xmpIO = NoXmpIO,
                thumbnailStore = MemoryThumbnailStore(),
                renderParallelism = 2,
                scope = scope,
                settings = SessionSettings(ocrLanguage = { "eng" }),
                log = world.log,
                clock = world.clock,
                installationId = installationId,
                computation = world.dispatcher,
                timeSource = world.scheduler.timeSource,
            )
        val opened = world.run("open $installationId") { openSession(deps, JournalOp.DocUndelete.WRITTEN) }
        val result = opened.getOrElse { failure ->
            job.cancel()
            throw failure
        }
        scopeJob = job
        session = result
        result.start()
    }

    /** Abandon the session without closing it, as a killed process does; files and databases stay. */
    fun crash() {
        scopeJob?.cancel()
        scopeJob = null
        session = null
        // Let the cancelled work unwind (its NonCancellable cleanups, Room's rollbacks).
        world.advance(UNWIND_MILLIS)
    }

    /** Close the session as the app does; the databases stay. */
    fun close() {
        val open = session ?: return
        world.run("close $installationId") { open.close() }
        scopeJob?.cancel()
        scopeJob = null
        session = null
    }

    /** Drop the index, as a user deleting `index.db` (or a schema bump) does; the next [open] rebuilds it. */
    fun dropIndex() {
        check(session == null) { "Close or crash the session first" }
        indexDb.close()
        indexDb = world.newIndexDb()
    }

    /** Close everything for good, at the end of a run. */
    fun dispose() {
        runCatching { close() }
        scopeJob?.cancel()
        indexDb.close()
        deviceDb.close()
    }

    private companion object {
        const val UNWIND_MILLIS = 5_000L
    }
}

/**
 * Everything a simulation run shares: the virtual clock and dispatcher, the log, uncaught failures,
 * and the means of running work on virtual time ([run], [advance]).
 *
 * All time is the [scheduler]'s. The session's scope, its computation dispatcher, the databases'
 * query dispatcher and every operation run on [dispatcher], one [StandardTestDispatcher], so what
 * runs when is decided by the scheduler alone, and the wall clock ([clock]) reads its virtual time.
 */
internal class SimWorld(
    val scheduler: TestCoroutineScheduler,
) {
    val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler, name = "npn-sim")

    /** Virtual wall time: an epoch far enough along that HLCs and file ages look ordinary. */
    val clock = EpochClock { START_EPOCH_MILLIS + scheduler.currentTime }

    /** The step the simulation is at, for the log and for failures. */
    var step = 0

    val log = SimLog({ step }, clock::nowMillis)

    /** Failures that reached a scope's handler: nothing a session runs may end that way. */
    val uncaught = ArrayList<String>()

    fun uncaughtHandler(owner: String) =
        CoroutineExceptionHandler { _, failure ->
            uncaught += "[step $step] $owner: ${failure::class.qualifiedName}: ${failure.reason()}\n" +
                failure.stackTraceToString().lineSequence().take(STACK_LINES).joinToString("\n")
        }

    /** Where operations run: apart from any session, so a crash leaves them to fail as callers do. */
    val operations = CoroutineScope(dispatcher + SupervisorJob() + uncaughtHandler("operation"))

    fun newIndexDb(): NpnDatabase = NpnDatabase.configure(Room.inMemoryDatabaseBuilder<NpnDatabase>(), dispatcher).build()

    fun newDeviceDb(): DeviceDatabase =
        DeviceDatabase.configure(Room.inMemoryDatabaseBuilder<DeviceDatabase>(), dispatcher).build()

    /** Let [millis] of virtual time pass, running everything due on the way. */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** Start [block] as an operation; [drive] or [run] it to its end. */
    fun <T> launch(block: suspend () -> T): Deferred<T> = operations.async { block() }

    /**
     * Advance virtual time until [deferred] completes, in small slices so the operation's end is
     * seen close to when it happens; throws [SimHang] once [budgetMillis] have passed without it.
     */
    fun drive(
        deferred: Deferred<*>,
        what: String,
        budgetMillis: Long = HANG_BUDGET_MILLIS,
    ) {
        scheduler.runCurrent()
        val start = scheduler.currentTime
        while (!deferred.isCompleted) {
            val waited = scheduler.currentTime - start
            if (waited >= budgetMillis) throw SimHang("$what did not finish within ${budgetMillis}ms of virtual time")
            val slice =
                when {
                    waited < FINE_FOR_MILLIS -> FINE_SLICE_MILLIS
                    waited < COARSE_FOR_MILLIS -> COARSE_SLICE_MILLIS
                    else -> LONG_SLICE_MILLIS
                }
            advance(slice)
        }
    }

    /** Run [block] to its end on virtual time and return its outcome. */
    fun <T> run(
        what: String,
        budgetMillis: Long = HANG_BUDGET_MILLIS,
        block: suspend () -> T,
    ): Result<T> {
        val deferred = launch(block)
        drive(deferred, what, budgetMillis)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return deferred.getCompletionExceptionOrNull()?.let { Result.failure(it) } ?: Result.success(deferred.getCompleted())
    }

    /** [run], for a read that must not fail. */
    fun <T> read(
        what: String,
        block: suspend () -> T,
    ): T = run(what, block = block).getOrThrow()

    fun dispose() {
        operations.cancel()
        advance(1)
    }

    companion object {
        /** 2023-11-14, so files written at virtual time 0 have ordinary mtimes. */
        const val START_EPOCH_MILLIS = 1_700_000_000_000L
        const val HANG_BUDGET_MILLIS = 6L * 60L * 60L * 1000L
        private const val FINE_SLICE_MILLIS = 5L
        private const val FINE_FOR_MILLIS = 10_000L
        private const val COARSE_SLICE_MILLIS = 250L
        private const val COARSE_FOR_MILLIS = 5L * 60L * 1000L
        private const val LONG_SLICE_MILLIS = 30_000L
        private const val STACK_LINES = 12
    }
}

/** An operation that never finished on virtual time: a deadlock, or a wait nothing will end. */
class SimHang(
    message: String,
) : IllegalStateException(message)

/** Seeded choices: the generator's own stream, kept apart from the latency and watcher streams. */
internal fun Random.chance(percent: Int): Boolean = nextInt(100) < percent

internal fun <T> Random.pick(items: List<T>): T = items[nextInt(items.size)]
