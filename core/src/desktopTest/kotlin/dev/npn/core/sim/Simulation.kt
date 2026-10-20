package dev.npn.core.sim

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.db.QueueState
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.reason
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import kotlin.random.Random

/** How one run is set up. Everything a seed decides is derived from it in [SimConfig.forSeed]. */
data class SimConfig(
    val seed: Long,
    val steps: Int,
    val profile: StorageProfile,
    /** False: the provider reports no mtimes (listings and stats carry 0), as SAF can. */
    val mtimes: Boolean,
    val watchDropRate: Double,
    val watchOverflowRate: Double,
    /** Run the expensive invariants (rebuild oracle, files hold their hashes) every this many steps, and at the end. */
    val quiesceEvery: Int = 40,
    /** Operations left out, each for a reason recorded where it is left out ([KnownIssues]). */
    val disabled: Set<OpKind> = KnownIssues.disabledOps,
) {
    companion object {
        /** The storage, watcher and mtime knobs a seed picks, so a seed alone replays a run. */
        fun forSeed(
            seed: Long,
            steps: Int,
        ): SimConfig {
            val random = Random(seed xor CONFIG_SALT)
            val profile =
                when (random.nextInt(100)) {
                    in 0 until 55 -> StorageProfile.DIRECT_LINKS
                    in 55 until 80 -> StorageProfile.DIRECT_RENAME
                    else -> StorageProfile.SAF
                }
            return SimConfig(
                seed = seed,
                steps = steps,
                profile = profile,
                mtimes = !(profile == StorageProfile.SAF && random.nextBoolean()),
                watchDropRate = if (random.chance(30)) 0.2 else 0.0,
                watchOverflowRate = if (random.chance(20)) 0.05 else 0.0,
            )
        }

        private const val CONFIG_SALT = 0x5EED_C0DEL
    }
}

/**
 * Operations and invariants the simulation leaves out for now, each because it found a core bug
 * that is reported but not fixed yet (see docs/SIMULATION.md). A follow-up that fixes one turns
 * its knob back on.
 */
object KnownIssues {
    val disabledOps: Set<OpKind> = emptySet()

    /**
     * Failures that are reported findings, by a phrase of their message. A seed that hits one stops
     * there (its library is broken from then on), and is counted instead of failing the test, so a
     * long run goes on to find what else there is. A new finding is never added here before it is
     * written up; a fixed one is removed.
     */
    val knownFailures: Map<String, String> = emptyMap()
}

/** A broken invariant: the run stops at the first. */
class SimFailure(
    message: String,
    /** The [KnownIssues.knownFailures] entry this failure is, or null for a new one. */
    val known: String? = null,
) : AssertionError(message)

/**
 * One simulation run: a single replica over an [InMemoryLibraryFileSystem], driven [SimConfig.steps]
 * steps by a [SimGenerator] seeded with [SimConfig.seed], with every invariant checked after each
 * step and the expensive ones (the rebuild oracle above all) at quiescence. A failure throws
 * [SimFailure] carrying the seed, the configuration and the trace.
 */
internal class Simulation(
    scheduler: TestCoroutineScheduler,
    val config: SimConfig,
    /** Runs after each step, before its invariants: the harness's own tests break the library here. */
    private val afterStep: Simulation.(step: Int) -> Unit = {},
    /** Changes the copy of the files the rebuild oracle opens: the harness's own tests plant a difference. */
    private val tamperWithRebuild: (InMemoryLibraryFileSystem) -> Unit = {},
    /** Set when this is one replica of several ([SyncSimulation]): what it shares with them, and its own parts. */
    private val setup: ReplicaSetup? = null,
) {
    val world = setup?.world ?: SimWorld(scheduler)
    private val random = setup?.random ?: Random(config.seed)

    val fs =
        setup?.fs ?: InMemoryLibraryFileSystem(
            now = world.clock::nowMillis,
            options =
                SimFsOptions(
                    profile = config.profile,
                    mtimes = config.mtimes,
                    watchDropRate = config.watchDropRate,
                    watchOverflowRate = config.watchOverflowRate,
                ),
            latency = SimLatency(Random(config.seed * LATENCY_SALT + 1)),
            watchRandom = Random(config.seed * LATENCY_SALT + 2),
        )

    val replica =
        setup?.replica
            ?: SimReplica(world, "sim-installation", fs, ids = SimIds(Random(config.seed * LATENCY_SALT + 3)))

    /** Every PDF the simulation made, by hash, so a later step can import its bytes again. */
    val known = setup?.known ?: HashMap()

    private val trace = setup?.trace ?: ArrayList()
    private val label = setup?.let { "[${it.name}] " }.orEmpty()
    private val generator =
        SimGenerator(
            random,
            this,
            OpKind.entries.filterNot { it in config.disabled },
            labelBase = setup?.labelBase ?: 0,
            remoteDevice = setup?.let { "remote-device-${it.name}" } ?: "remote-device",
        )

    /** How many times each outcome happened, for the summary. */
    val stats = HashMap<String, Int>()

    private val session: LibrarySessionImpl get() = replica.session ?: throw SimFailure(report("no session is open"))

    fun run() {
        try {
            open()
            for (step in 1..config.steps) {
                world.step = step
                step(step)
                if (step % config.quiesceEvery == 0) quiesceAndCheck()
            }
            quiesceAndCheck()
        } finally {
            // With -Dnpn.sim.traceDir=<dir>, every run leaves its whole trace there, failing or not.
            System.getProperty(TRACE_DIR_PROPERTY)?.let { dir ->
                File(dir).apply { mkdirs() }.resolve("seed-${config.seed}.txt").writeText(trace.joinToString("\n"))
            }
            replica.dispose()
            world.dispose()
        }
    }

    // ---- the phases of a run, for a SyncSimulation driving several replicas ---------------------

    fun open() = openReplica()

    /** One step: [planned] (by default the generator's next), its outcome traced, and the cheap invariants. */
    fun step(
        step: Int,
        planned: Planned = generator.next(snapshot()),
    ) {
        val outcome = execute(planned)
        trace += "#$step t=${world.scheduler.currentTime} $label${planned.text} -> $outcome"
        harvest()
        afterStep(step)
        checkCheap()
    }

    /** The generator's next step for this replica, for a planner that runs it beside one of its own. */
    fun nextPlanned(): Planned = generator.next(snapshot())

    /** A hard crash: the killed process's background operations are dropped, never awaited; then reopen. */
    fun hardCrashAndReopen(): String {
        if (!replica.hardCrash()) return "no instant between transactions; not crashed"
        inFlight.forEach { (text, _) -> trace += "   background '$text' -> killed with its process" }
        inFlight.clear()
        openReplica()
        return "killed, reopened"
    }

    // ---- executing steps ------------------------------------------------------------------------

    private fun execute(planned: Planned): String =
        when (planned) {
            is Planned.Control -> {
                count(planned.text.substringBefore(' '))
                planned.body()
            }

            is Planned.Act -> {
                runActs(listOf(planned)).single()
            }

            is Planned.Group -> {
                planned.during.forEach { (at, control) ->
                    world.launch {
                        delay(at)
                        trace += "   at t=${world.scheduler.currentTime}: ${control.text} -> ${control.body()}"
                    }
                }
                runActs(planned.acts).joinToString(" || ")
            }

            is Planned.Background -> {
                val open = session
                inFlight += planned.act.text to replica.launch { planned.act.body(open) }
                "started"
            }
        }

    /** Operations [Planned.Background] started, until [harvest] finds them done. */
    private val inFlight = ArrayList<Pair<String, Deferred<String>>>()

    /** Record the end of every background operation that ended, failing on one that ended in a bug. */
    private fun harvest() {
        val done = inFlight.filter { it.second.isCompleted }
        inFlight.removeAll(done)
        done.forEach { (text, deferred) -> trace += "   background '$text' -> ${outcomeOf(text, deferred)}" }
    }

    /** Drive every background operation to its end, as quiescence needs. */
    private fun finishBackground() {
        inFlight.toList().forEach { (text, deferred) ->
            try {
                world.drive(deferred, text)
            } catch (hang: SimHang) {
                fail("background operation hung: ${hang.message}")
            }
        }
        harvest()
    }

    /** Start [acts] together on the open session and drive them all to their end. */
    private fun runActs(acts: List<Planned.Act>): List<String> {
        val open = session
        val running = acts.map { act -> act to replica.launch { act.body(open) } }
        running.forEach { (act, deferred) ->
            try {
                world.drive(deferred, act.text)
            } catch (hang: SimHang) {
                fail("operation hung: ${hang.message}")
            }
        }
        return running.map { (act, deferred) -> outcomeOf(act.text, deferred) }
    }

    /** What an operation that ended came to; one that ended in a bug fails the run. */
    private fun outcomeOf(
        text: String,
        deferred: Deferred<String>,
    ): String {
        count(text.substringBefore(' '))
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val failure = deferred.getCompletionExceptionOrNull()
        return when {
            failure == null -> {
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                deferred.getCompleted()
            }

            isBugFailure(failure) -> {
                fail(
                    "operation '$text' threw ${failure::class.qualifiedName}: ${failure.reason()}\n" +
                        failure
                            .stackTraceToString()
                            .lineSequence()
                            .take(STACK_LINES)
                            .joinToString("\n"),
                )
            }

            else -> {
                "threw ${failure::class.simpleName}: ${failure.reason()}"
            }
        }
    }

    private fun count(what: String) = stats.merge(what, 1, Int::plus)

    // ---- the process's lifecycle ----------------------------------------------------------------

    /** Open the replica's session; an open an injected fault failed is retried once the faults are gone. */
    private fun openReplica() {
        val first = runCatching { replica.open() }
        first.exceptionOrNull()?.let { failure ->
            if (!failure.isInjected() && isBugFailure(failure)) fail("opening the library failed: ${failure.reason()}")
            trace += "   open failed (${failure.reason()}); faults cleared, opening again"
            fs.faults.disarmAll()
            setup?.beforeReopen?.invoke(failure)
            runCatching { replica.open() }.exceptionOrNull()?.let {
                fail(
                    "opening the library failed again: ${it.reason()}",
                )
            }
        }
    }

    fun crashAndReopen(): String {
        replica.crash()
        openReplica()
        return "reopened"
    }

    fun closeAndReopen(): String {
        val closed = runCatching { replica.close() }
        closed.exceptionOrNull()?.let { if (isBugFailure(it)) fail("closing the session failed: ${it.reason()}") }
        openReplica()
        return "reopened"
    }

    fun dropIndexAndReopen(crash: Boolean): String {
        if (crash) replica.crash() else replica.close()
        replica.dropIndex()
        openReplica()
        return if (crash) "crashed, dropped, reopened" else "closed, dropped, reopened"
    }

    // ---- what the generator sees ----------------------------------------------------------------

    fun snapshot(): Snapshot {
        val open = session
        return world.read("snapshot") {
            val rows =
                replica.indexDb.rows(
                    "SELECT docId, relPath, trashed, deleted, missing FROM documents ORDER BY docId",
                )

            fun docs(predicate: (List<String?>) -> Boolean) = rows.filter(predicate).map { SimDoc(it[0]!!, it[1]!!) }
            Snapshot(
                live = docs { it[2] == "0" && it[3] == "0" && it[4] == "0" },
                trashed = docs { it[2] == "1" && it[3] == "0" },
                deleted = docs { it[3] == "1" },
                fields = open.fieldRegistry.current(),
                tags = replica.indexDb.rows("SELECT DISTINCT tag FROM document_tags ORDER BY tag").map { it[0]!! },
            )
        }
    }

    // ---- invariants -----------------------------------------------------------------------------

    /** The invariants cheap enough for every step. */
    fun checkCheap() {
        fs.writeOnceViolations.firstOrNull()?.let { fail("write-once broken: $it") }
        fs.misnamedObjects().firstOrNull()?.let { fail("journal object not named by its hash: $it") }
        world.uncaught.firstOrNull()?.let { fail("an exception reached a scope's handler: $it") }
        world.log.bugs.firstOrNull()?.let {
            fail("a bug was logged: $it\n${it.error?.stackTraceToString()?.take(STACK_CHARS)}")
        }
        if (replica.session == null) return
        val problems =
            world.read("structure") {
                val out = ArrayList<String>()
                replica.indexDb.rows("SELECT docId, relPath, trashed, deleted, missing FROM documents").forEach { row ->
                    val (docId, relPath) = row[0]!! to row[1]!!
                    val live = row[2] == "0" && row[3] == "0" && row[4] == "0"
                    val sentinel = relPath.startsWith("${NpnPaths.NPN_DIR}/missing/")
                    if (live &&
                        !NpnPaths.isDocumentPath(relPath)
                    ) {
                        out +=
                            "live row ${docId.take(ID)} at a path no document may have: '$relPath'\n" +
                            lifecycleEvents(docId, replica.indexDb)
                    }
                    if (sentinel &&
                        relPath != missingSentinel(docId)
                    ) {
                        out +=
                            "row ${docId.take(ID)} waits at another row's sentinel '$relPath'"
                    }
                }
                val states = QueueState.entries.map { it.column }.toSet()
                replica.deviceDb.rows("SELECT docId, state, attempts FROM ocr_queue").forEach { row ->
                    if (row[1] !in states) out += "OCR queue row ${row[0]!!.take(ID)} claims state '${row[1]}'"
                    if ((row[2]?.toIntOrNull() ?: -1) <
                        0
                    ) {
                        out += "OCR queue row ${row[0]!!.take(ID)} has attempts ${row[2]}"
                    }
                }
                replica.deviceDb.rows("SELECT docId, attempts FROM ai_enrich_queue WHERE attempts < 0").forEach { row ->
                    out += "enrichment queue row ${row[0]!!.take(ID)} has attempts ${row[1]}"
                }
                out
            }
        problems.firstOrNull()?.let {
            fail("index structure: $it" + problems.drop(1).joinToString("") { more -> "\n  $more" })
        }
    }

    /**
     * Let everything settle (OCR let through, faults cleared, time on past every settle window, one
     * more rescan), then check what holds only at rest: each live document's file holds its bytes,
     * every settled PDF in the folder is a document or a known duplicate, and a fresh index over the
     * same files shows the same library ([rebuildOracle]).
     */
    private fun quiesceAndCheck() {
        settle()
        checkAtRest()
    }

    /** The first half of [quiesceAndCheck]: everything let through and settled, and one more rescan. */
    fun settle() {
        trace += "   -- ${label}quiescence at t=${world.scheduler.currentTime}"
        fs.faults.disarmAll()
        replica.ocr.openGate()
        finishBackground()
        world.advance(SETTLE_MILLIS)
        // A fault a concurrent step armed while it ran (Planned.Group.during) may have landed since.
        fs.faults.disarmAll()
        val rescanned = world.run("quiescent rescan") { session.rescan() }
        rescanned.exceptionOrNull()?.let {
            fail(
                "the quiescent rescan failed: ${it::class.simpleName}: ${it.reason()}",
            )
        }
        world.advance(AFTER_RESCAN_MILLIS)
    }

    /** The second half of [quiesceAndCheck]: the invariants at rest; returns the library the replica shows. */
    fun checkAtRest(): LibraryView {
        checkCheap()
        val live = world.read("live view") { LibraryView.read(session, replica.indexDb) }
        checkFilesHoldTheirHashes(live)
        checkRowStats()
        rebuildOracle(live)
        replica.ocr.closeGate()
        trace += "   -- ${label}quiescent: ${live.live.size} live, ${live.hidden.size} hidden"
        return live
    }

    private fun checkFilesHoldTheirHashes(view: LibraryView) {
        val wrong =
            view.live.mapNotNull { (docId, doc) ->
                val bytes = fs.bytesAt(doc.relPath)
                when {
                    bytes == null -> "live ${docId.take(ID)} has no file at '${doc.relPath}'"

                    sha256Bytes(
                        bytes,
                    ) != docId -> "live ${docId.take(ID)} at '${doc.relPath}' holds ${sha256Bytes(bytes).take(ID)}"

                    else -> null
                }
            }
        if (wrong.isNotEmpty()) {
            val paths =
                view.live.values
                    .map { it.relPath }
                    .filter { path -> wrong.any { "'$path'" in it } }
            val history = paths.joinToString("") { path -> "\n  '$path': " + fs.historyOf(path).joinToString("; ") }
            fail("files do not hold their documents' bytes:\n  " + wrong.joinToString("\n  ") + history)
        }
        val livePaths =
            view.live.values
                .map { it.relPath }
                .toSet()
        val duplicatePaths =
            world.read("duplicates") {
                replica.indexDb
                    .rows("SELECT relPath FROM duplicates")
                    .map { it[0]!! }
                    .toSet()
            }
        // Bytes a relink superseded (a bake's original) are no document: a rescan gives them no row.
        // Nor, while this build writes no doc-undelete, are the bytes of a document deleted for good
        // that came back: a rescan shows such a file for that scan only (ARCHITECTURE.md 6.4).
        val operations = if (SyncKnownIssues.DELETED_RETURNS_UNRECORDED) "('relink', 'doc-delete')" else "('relink')"
        val superseded =
            world.read("superseded") {
                replica.indexDb
                    .rows("SELECT DISTINCT documentId FROM metadata_events WHERE operation IN $operations")
                    .mapNotNull { it[0] }
                    .toSet()
            }
        val unaccounted =
            fs.allFiles().filter { (path, bytes) ->
                NpnPaths.indexable(FileMeta(path, NpnPaths.fileName(path), bytes.size.toLong(), 0, false)) &&
                    bytes.isNotEmpty() &&
                    path !in livePaths &&
                    path !in duplicatePaths &&
                    sha256Bytes(bytes) !in superseded
            }
        if (unaccounted.isNotEmpty()) {
            fail(
                "settled PDFs that are neither a live document nor a known duplicate:\n  " +
                    unaccounted.keys.joinToString(
                        "\n  ",
                    ) { "'$it' (${sha256Bytes(unaccounted.getValue(it)).take(ID)})" },
            )
        }
    }

    /**
     * Every live row stores its file's size and mtime, the proof by stat a rescan, a trash or an import
     * takes from it. A file whose stat moved with its bytes unchanged (a touch) was hashed by the
     * quiescent rescan, which stores the stat it found. A row without its file is
     * [checkFilesHoldTheirHashes]'s to report.
     */
    private fun checkRowStats() {
        val rows =
            world.read("row stats") {
                replica.indexDb.rows(
                    "SELECT docId, relPath, size || ',' || mtime FROM documents " +
                        "WHERE trashed = 0 AND deleted = 0 AND missing = 0",
                )
            }
        val stale =
            rows.mapNotNull { (docId, relPath, stored) ->
                val stat = fs.statAt(relPath!!) ?: return@mapNotNull null
                val actual = "${stat.size},${stat.mtimeMillis}"
                if (stored == actual) {
                    null
                } else {
                    "live ${docId!!.take(ID)} at '$relPath' stores size,mtime $stored; its file has $actual"
                }
            }
        if (stale.isNotEmpty()) fail("rows whose stored stat is not their file's:\n  " + stale.joinToString("\n  "))
    }

    /**
     * The rebuild oracle: a second replica, over a copy of the files as they stand and with empty
     * index and device databases, opened and left to fold and rescan to rest, must show the library
     * [live] shows ([diff], which says what is compared and what is device-local).
     */
    private fun rebuildOracle(live: LibraryView) {
        val copy = fs.snapshot().also(tamperWithRebuild)
        val oracle = SimReplica(world, "oracle-installation", copy, GatedOcrEngine().apply { openGate() })
        try {
            runCatching { oracle.open() }.exceptionOrNull()?.let {
                fail(
                    "the rebuild could not open the library: ${it.reason()}",
                )
            }
            val opened = oracle.session!!
            world.read("rebuild's initial rescan") { opened.awaitInitialRescan() }
            world.advance(AFTER_RESCAN_MILLIS)
            val rebuilt = world.read("rebuilt view") { LibraryView.read(opened, oracle.indexDb) }
            // A row hidden by a file that went missing, or a tombstone of a document deleted for good,
            // is a past observation the index keeps and a rebuild cannot know of: device-local.
            val differences =
                diff(
                    live,
                    rebuilt,
                    hiddenOnlyLive = { _, hidden -> hidden != Hidden.TRASHED },
                    bothHold = {
                        docId,
                        a,
                        b,
                        ->
                        listOf(a, b).all { path -> fs.bytesAt(path)?.let(::sha256Bytes) == docId }
                    },
                )
            // Each differing document with the journal events one index folded and the other did not.
            val ids = live.live.keys + rebuilt.live.keys + live.hidden.keys + live.trashed.keys + rebuilt.trashed.keys
            val explained =
                differences.associateWith { line ->
                    val prefix = Regex("^(?:trashed )?doc ([0-9a-f]+) ").find(line)?.groupValues?.get(1)
                    val id = prefix?.let { ids.first { docId -> docId.startsWith(prefix) } }
                    id?.let { eventsOf(it, oracle, copy) }.orEmpty()
                }
            // A deleted document's file back in the folder: a rebuild's rescan shows it.
            val remaining =
                explained.filterNot { (line, _) ->
                    SyncKnownIssues.DELETED_RETURNS_UNRECORDED && "(live index: DELETED)" in line
                }
            if (remaining.isNotEmpty()) {
                fail(
                    "rebuild oracle: a fresh index shows another library:\n  " +
                        remaining.keys.joinToString("\n  ") + "\n" + remaining.values.joinToString(""),
                )
            }
        } finally {
            oracle.dispose()
        }
    }

    /** [eventsDiffer] for [docId], between this replica's index and [oracle]'s over [copy]. */
    private fun eventsOf(
        docId: String,
        oracle: SimReplica,
        copy: InMemoryLibraryFileSystem,
    ): String = world.read("events of $docId") { eventsDiffer(docId, replica.indexDb, oracle.indexDb, fs, copy) }

    // ---- failing --------------------------------------------------------------------------------

    fun fail(message: String): Nothing =
        throw SimFailure(
            report(message),
            KnownIssues.knownFailures.entries
                .firstOrNull { message.contains(it.key) }
                ?.value,
        )

    private fun report(message: String): String =
        buildString {
            appendLine("Simulation failed at step ${world.step} (t=${world.scheduler.currentTime}ms) $label: $message")
            appendLine()
            appendLine(
                "Replay: ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' " +
                    "-Dnpn.sim.seed=${config.seed} -Dnpn.sim.steps=${config.steps}",
            )
            appendLine("Config: $config")
            appendLine("Faults armed: ${fs.faults.pending}")
            appendLine()
            appendLine("Trace (last $TRACE_LINES of ${trace.size}):")
            trace.takeLast(TRACE_LINES).forEach { appendLine("  $it") }
            appendLine()
            appendLine("Log (last $LOG_LINES of ${world.log.entries.size}):")
            world.log.entries
                .takeLast(LOG_LINES)
                .forEach { appendLine("  $it") }
            appendLine()
            appendLine("Library files (outside .npn/, and the trash):")
            fs
                .allFiles()
                .filterKeys {
                    !it.startsWith(
                        "${NpnPaths.NPN_DIR}/",
                    ) || it.startsWith("${NpnPaths.TRASH_DIR}/")
                }.forEach { (path, bytes) ->
                    appendLine("  '$path' ${sha256Bytes(bytes).take(ID)} (${bytes.size} bytes)")
                }
            appendLine()
            appendLine("Index rows (docId, relPath, trashed, deleted, missing, size, mtime):")
            val rows =
                runCatching {
                    world
                        .run("rows for the report", budgetMillis = REPORT_READ_MILLIS) {
                            replica.indexDb.rows(
                                "SELECT docId, relPath, trashed, deleted, missing, size, mtime " +
                                    "FROM documents ORDER BY relPath",
                            )
                        }.getOrThrow()
                }
            rows.getOrNull()?.forEach { row -> appendLine("  ${row[0]!!.take(ID)} ${row.drop(1)}") }
                ?: appendLine("  (could not be read: ${rows.exceptionOrNull()?.reason()})")
        }

    companion object {
        /** The system property naming a directory to write each run's trace to. */
        const val TRACE_DIR_PROPERTY = "npn.sim.traceDir"
        private const val ID = 10
        private const val LATENCY_SALT = 31L
        private const val SETTLE_MILLIS = 20L * 60L * 1000L
        private const val AFTER_RESCAN_MILLIS = 2L * 60L * 1000L
        private const val TRACE_LINES = 120
        private const val LOG_LINES = 40
        private const val STACK_LINES = 25
        private const val STACK_CHARS = 3_000
        private const val REPORT_READ_MILLIS = 60_000L
    }
}
