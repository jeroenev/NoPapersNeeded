package dev.npn.core.sim

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.openSession
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.journal.JournalOp
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.EpochClock
import dev.npn.core.util.IdSource
import dev.npn.core.util.NpnLog
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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * One replica of the library in the simulation: its databases (which outlive a session, as the
 * files on a device do), the session open over them, and the scope that session runs in. Several
 * replicas over their own [InMemoryLibraryFileSystem]s and a sync tool between them are a
 * [SyncSimulation]; everything a replica needs is here, apart from the shared world.
 */
internal class SimReplica(
    private val world: SimWorld,
    val installationId: String,
    val fs: InMemoryLibraryFileSystem,
    val ocr: GatedOcrEngine = GatedOcrEngine(),
    /** Where its sessions draw fresh names: seeded, so a seed replays the names as well. */
    private val ids: IdSource = SimIds(Random(installationId.hashCode().toLong())),
    /** Where its sessions log: the world's log, the entries naming the replica when several run. */
    val log: NpnLog = world.log,
) {
    val pdf = SimPdfEngine(world.sizes, maker = installationId)
    val xmp = SimXmpIO(world.sizes)
    var indexDb: NpnDatabase = world.newIndexDb()
        private set
    var deviceDb: DeviceDatabase = world.newDeviceDb()
        private set
    private var scopeJob: Job? = null

    /** The life of the process the open session runs in; a hard crash ends it ([hardCrash]). */
    var life = SimLife(installationId)
        private set

    /** The open session, or null between a crash or close and the next [open]. */
    var session: LibrarySessionImpl? = null
        private set

    /** Every journal device id this replica's sessions had: what it may write under its own name. */
    val deviceIds = LinkedHashSet<String>()

    /** Run with every session opened, in its scope: what a host starts beside a session (a watched folder). */
    var onOpen: ((LibrarySessionImpl, CoroutineScope) -> Unit)? = null

    private var crashDir: File? = null
    private var hardCrashes = 0

    /** Open a session over the replica's files and databases and start it; throws what the open threw. */
    fun open() {
        check(session == null) { "A session is already open" }
        val job = SupervisorJob()
        val process = SimLife(installationId)
        val scope = CoroutineScope(world.dispatcher + job + world.uncaughtHandler(installationId) + process)
        val deps =
            SessionDeps(
                fs = fs,
                db = indexDb,
                deviceDb = deviceDb,
                pdfEngine = pdf,
                ocrEngine = ocr,
                xmpIO = xmp,
                thumbnailStore = MemoryThumbnailStore(),
                renderParallelism = 2,
                scope = scope,
                settings = SessionSettings(ocrLanguage = { "eng" }),
                log = log,
                clock = world.clock,
                installationId = installationId,
                computation = world.dispatcher,
                timeSource = world.scheduler.timeSource,
                ids = ids,
            )
        val opened = world.run("open $installationId") { openSession(deps, JournalOp.DocUndelete.WRITTEN) }
        val result =
            opened.getOrElse { failure ->
                job.cancel()
                throw failure
            }
        life = process
        scopeJob = job
        session = result
        deviceIds += result.deviceId
        result.start()
        onOpen?.invoke(result, scope)
    }

    /** Start [block] as an operation of this replica's process, so a hard crash stops it with the session. */
    fun <T> launch(block: suspend () -> T): Deferred<T> = world.launch(life, block)

    /** Abandon the session without closing it; its scope is cancelled, so `finally` blocks still run. */
    fun crash() {
        scopeJob?.cancel()
        scopeJob = null
        session = null
        // Let the cancelled work unwind (its NonCancellable cleanups, Room's rollbacks).
        world.advance(UNWIND_MILLIS)
    }

    /**
     * End the process as a kill does: nothing of the session runs again, no `finally` block, no
     * cleanup, nothing cancelled. The databases are what was committed at that instant, taken as
     * images while the process is held between two transactions (a kill inside one would roll it
     * back, which is the state of a moment before), and the next [open] runs over those images.
     * Returns false, with the process let go on as if nothing happened, when no such instant comes
     * within [PROBE_MILLIS] of virtual time (a transaction held open across a long wait).
     */
    fun hardCrash(): Boolean {
        if (session == null) return false
        val dying = life
        dying.pause()
        val dir =
            crashDir ?: Files.createTempDirectory(scratch(), "npn-sim-$installationId").toFile().also { crashDir = it }
        val n = ++hardCrashes
        val indexImage = File(dir, "index-$n.db")
        val deviceImage = File(dir, "device-$n.db")
        val images =
            world.launch {
                indexDb.imageTo(indexImage)
                deviceDb.imageTo(deviceImage)
            }
        val taken =
            try {
                world.drive(images, "the images of a hard crash", PROBE_MILLIS)
                true
            } catch (hang: SimHang) {
                images.cancel(hang.message.orEmpty())
                false
            }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val failed = if (taken) images.getCompletionExceptionOrNull() else null
        if (!taken || failed != null) {
            dying.resume()
            failed?.let { throw it }
            return false
        }
        dying.kill()
        session = null
        scopeJob = null
        ocr.forgetWaiting()
        // The old databases are left as they are, open: the killed process held them.
        indexDb = world.fileIndexDb(indexImage)
        deviceDb = world.fileDeviceDb(deviceImage)
        return true
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
        crashDir?.deleteRecursively()
    }

    private companion object {
        const val UNWIND_MILLIS = 5_000L
        const val PROBE_MILLIS = 2_000L
    }
}

/** Memory-backed `/dev/shm` where there is one, so the databases a hard crash leaves cost no disk. */
private fun scratch(): Path =
    Path.of("/dev/shm").takeIf { Files.isDirectory(it) && Files.isWritable(it) }
        ?: Path.of(System.getProperty("java.io.tmpdir"))

/** Write the committed state of this database to [file], as a crash would leave it on disk. */
private suspend fun RoomDatabase.imageTo(file: File) =
    useWriterConnection { connection ->
        connection.usePrepared("VACUUM INTO ?") { statement ->
            statement.bindText(1, file.absolutePath)
            statement.step()
        }
    }

/**
 * Everything a simulation run shares: the virtual clock and dispatcher, the log, uncaught failures,
 * and the means of running work on virtual time ([run], [advance]).
 *
 * All time is the [scheduler]'s. The session's scope, its computation dispatcher, the databases'
 * query dispatcher and every operation run on [dispatcher], one [SimDispatcher] over a
 * [StandardTestDispatcher], so what runs when is decided by the scheduler alone, and the wall clock
 * ([clock]) reads its virtual time.
 */
internal class SimWorld(
    val scheduler: TestCoroutineScheduler,
) {
    val dispatcher: CoroutineDispatcher = SimDispatcher(StandardTestDispatcher(scheduler, name = "npn-sim"))

    /** Virtual wall time: an epoch far enough along that HLCs and file ages look ordinary. */
    val clock = EpochClock { START_EPOCH_MILLIS + scheduler.currentTime }

    /** The step the simulation is at, for the log and for failures. */
    var step = 0

    val log = SimLog({ step }, clock::nowMillis)

    /** The sizes of the PDFs the engines make, one per content, across every replica. */
    val sizes = SimSizes()

    /**
     * The documents a device on an older build baked in place (`EXT_OLDER_BUILD_BAKE`), across every
     * replica's generator: each is baked so at most once, so no two replicas still showing it fork it.
     */
    val olderBuildBaked = HashSet<String>()

    /** Failures that reached a scope's handler: nothing a session runs may end that way. */
    val uncaught = ArrayList<String>()

    fun uncaughtHandler(owner: String) =
        CoroutineExceptionHandler { _, failure ->
            uncaught += "[step $step] $owner: ${failure::class.qualifiedName}: ${failure.reason()}\n" +
                failure
                    .stackTraceToString()
                    .lineSequence()
                    .take(STACK_LINES)
                    .joinToString("\n")
        }

    /** Where operations run: apart from any session, so a crash leaves them to fail as callers do. */
    val operations = CoroutineScope(dispatcher + SupervisorJob() + uncaughtHandler("operation"))

    fun newIndexDb(): NpnDatabase =
        NpnDatabase.configure(Room.inMemoryDatabaseBuilder<NpnDatabase>(), dispatcher).build()

    fun newDeviceDb(): DeviceDatabase =
        DeviceDatabase.configure(Room.inMemoryDatabaseBuilder<DeviceDatabase>(), dispatcher).build()

    /** An index over a database file, the image a hard crash left. */
    fun fileIndexDb(file: File): NpnDatabase =
        NpnDatabase
            .configure(Room.databaseBuilder<NpnDatabase>(name = file.absolutePath), dispatcher)
            .addCallback(NoSync)
            .build()

    fun fileDeviceDb(file: File): DeviceDatabase =
        DeviceDatabase
            .configure(Room.databaseBuilder<DeviceDatabase>(name = file.absolutePath), dispatcher)
            .addCallback(NoSync)
            .build()

    /** No fsync for a database the simulation keeps in a file only to carry it over a crash. */
    private object NoSync : RoomDatabase.Callback() {
        override suspend fun onOpen(connection: SQLiteConnection) = connection.execSQL("PRAGMA synchronous = OFF")
    }

    /** Let [millis] of virtual time pass, running everything due on the way. */
    fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /** Start [block] as an operation (in [context], a process's [SimLife]); [drive] or [run] it to its end. */
    fun <T> launch(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> T,
    ): Deferred<T> = operations.async(context) { block() }

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
        val deferred = launch(block = block)
        drive(deferred, what, budgetMillis)
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return deferred.getCompletionExceptionOrNull()?.let { Result.failure(it) }
            ?: Result.success(deferred.getCompleted())
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
