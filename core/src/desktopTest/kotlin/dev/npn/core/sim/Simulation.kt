package dev.npn.core.sim

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.db.QueueState
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.reason
import kotlinx.coroutines.test.TestCoroutineScheduler
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
     * Finding 1: a rebuilt index has no row for a document in the trash. Rows come only from files
     * a rescan lists, and it never lists `.npn/trash`; the fold projects only documents that have a
     * row (`FoldProjection.fold`). So after `index.db` is dropped (or on a new device) the Trash
     * shows nothing, though the journal and the trash folder still hold the documents, and they can
     * be neither restored nor deleted for good from there. While true, the oracle passes over a
     * trashed row the rebuild lacks.
     */
    const val TRASH_IS_NOT_REBUILT = true

    /**
     * Finding 2: where storage reports no mtime (a SAF provider giving 0), a file replaced by other
     * bytes of the same size keeps its old identity. The rescan, an import's duplicate check and the
     * trash take an unchanged size and mtime as proof the bytes are the ones hashed before
     * (`FileMeta.hasStoredStat`), and with every mtime 0 only the size is left; the watched folder
     * alone hashes in that case (`WatchedFolder.kt`). While true, every PDF the simulation makes has
     * a size no other one has, so a replacement always changes the size.
     */
    const val UNIQUE_PDF_SIZES = true
}

/** A broken invariant: the run stops at the first. */
class SimFailure(
    message: String,
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
) {
    val world = SimWorld(scheduler)
    private val random = Random(config.seed)

    val fs =
        InMemoryLibraryFileSystem(
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

    val replica = SimReplica(world, "sim-installation", fs)

    /** Every PDF the simulation made, by hash, so a later step can import its bytes again. */
    val known = HashMap<String, ByteArray>()

    private val trace = ArrayList<String>()
    private val generator = SimGenerator(random, this, OpKind.entries.filterNot { it in config.disabled })

    /** How many times each outcome happened, for the summary. */
    val stats = HashMap<String, Int>()

    private val session: LibrarySessionImpl get() = replica.session ?: throw SimFailure(report("no session is open"))

    fun run() {
        try {
            openReplica()
            for (step in 1..config.steps) {
                world.step = step
                val planned = generator.next(snapshot())
                val outcome = execute(planned)
                trace += "#$step t=${world.scheduler.currentTime} ${planned.text} -> $outcome"
                checkCheap()
                if (step % config.quiesceEvery == 0) quiesceAndCheck()
            }
            quiesceAndCheck()
        } finally {
            replica.dispose()
            world.dispose()
        }
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
                runActs(planned.acts).joinToString(" || ")
            }
        }

    /** Start [acts] together on the open session and drive them all to their end. */
    private fun runActs(acts: List<Planned.Act>): List<String> {
        val open = session
        val running = acts.map { act -> act to world.launch { act.body(open) } }
        running.forEach { (act, deferred) ->
            try {
                world.drive(deferred, act.text)
            } catch (hang: SimHang) {
                fail("operation hung: ${hang.message}")
            }
        }
        return running.map { (act, deferred) ->
            count(act.text.substringBefore(' '))
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val failure = deferred.getCompletionExceptionOrNull()
            when {
                failure == null -> {
                    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                    deferred.getCompleted()
                }

                isBugFailure(failure) -> {
                    fail(
                        "operation '${act.text}' threw ${failure::class.qualifiedName}: ${failure.reason()}\n" +
                            failure.stackTraceToString().lineSequence().take(STACK_LINES).joinToString("\n"),
                    )
                }

                else -> {
                    "threw ${failure::class.simpleName}: ${failure.reason()}"
                }
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
            runCatching { replica.open() }.exceptionOrNull()?.let { fail("opening the library failed again: ${it.reason()}") }
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

    private fun snapshot(): Snapshot {
        val open = session
        return world.read("snapshot") {
            val rows = replica.indexDb.rows("SELECT docId, relPath, trashed, deleted, missing FROM documents ORDER BY docId")
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
    private fun checkCheap() {
        world.uncaught.firstOrNull()?.let { fail("an exception reached a scope's handler: $it") }
        world.log.bugs.firstOrNull()?.let { fail("a bug was logged: $it\n${it.error?.stackTraceToString()?.take(STACK_CHARS)}") }
        fs.writeOnceViolations.firstOrNull()?.let { fail("write-once broken: $it") }
        fs.misnamedObjects().firstOrNull()?.let { fail("journal object not named by its hash: $it") }
        if (replica.session == null) return
        val problems =
            world.read("structure") {
                val out = ArrayList<String>()
                replica.indexDb.rows("SELECT docId, relPath, trashed, deleted, missing FROM documents").forEach { row ->
                    val (docId, relPath) = row[0]!! to row[1]!!
                    val live = row[2] == "0" && row[3] == "0" && row[4] == "0"
                    val sentinel = relPath.startsWith("${NpnPaths.NPN_DIR}/missing/")
                    if (live && !NpnPaths.isDocumentPath(relPath)) out += "live row ${docId.take(ID)} at a path no document may have: '$relPath'"
                    if (sentinel && relPath != missingSentinel(docId)) out += "row ${docId.take(ID)} waits at another row's sentinel '$relPath'"
                }
                val states = QueueState.entries.map { it.column }.toSet()
                replica.deviceDb.rows("SELECT docId, state, attempts FROM ocr_queue").forEach { row ->
                    if (row[1] !in states) out += "OCR queue row ${row[0]!!.take(ID)} claims state '${row[1]}'"
                    if ((row[2]?.toIntOrNull() ?: -1) < 0) out += "OCR queue row ${row[0]!!.take(ID)} has attempts ${row[2]}"
                }
                replica.deviceDb.rows("SELECT docId, attempts FROM ai_enrich_queue WHERE attempts < 0").forEach { row ->
                    out += "enrichment queue row ${row[0]!!.take(ID)} has attempts ${row[1]}"
                }
                out
            }
        problems.firstOrNull()?.let { fail("index structure: $it" + problems.drop(1).joinToString("") { "\n  $it" }) }
    }

    /**
     * Let everything settle (OCR let through, faults cleared, time on past every settle window, one
     * more rescan), then check what holds only at rest: each live document's file holds its bytes,
     * every settled PDF in the folder is a document or a known duplicate, and a fresh index over the
     * same files shows the same library ([rebuildOracle]).
     */
    private fun quiesceAndCheck() {
        trace += "   -- quiescence at t=${world.scheduler.currentTime}"
        fs.faults.disarmAll()
        replica.ocr.openGate()
        world.advance(SETTLE_MILLIS)
        val rescanned = world.run("quiescent rescan") { session.rescan() }
        rescanned.exceptionOrNull()?.let { fail("the quiescent rescan failed: ${it::class.simpleName}: ${it.reason()}") }
        world.advance(AFTER_RESCAN_MILLIS)
        checkCheap()
        val live = world.read("live view") { LibraryView.read(session, replica.indexDb) }
        checkFilesHoldTheirHashes(live)
        rebuildOracle(live)
        replica.ocr.closeGate()
        trace += "   -- quiescent: ${live.live.size} live, ${live.hidden.size} hidden"
    }

    private fun checkFilesHoldTheirHashes(view: LibraryView) {
        val wrong =
            view.live.mapNotNull { (docId, doc) ->
                val bytes = fs.bytesAt(doc.relPath)
                when {
                    bytes == null -> "live ${docId.take(ID)} has no file at '${doc.relPath}'"
                    sha256Bytes(bytes) != docId -> "live ${docId.take(ID)} at '${doc.relPath}' holds ${sha256Bytes(bytes).take(ID)}"
                    else -> null
                }
            }
        if (wrong.isNotEmpty()) fail("files do not hold their documents' bytes:\n  " + wrong.joinToString("\n  "))
        val livePaths = view.live.values.map { it.relPath }.toSet()
        val duplicatePaths = world.read("duplicates") { replica.indexDb.rows("SELECT relPath FROM duplicates").map { it[0]!! }.toSet() }
        val unaccounted =
            fs.allFiles().filter { (path, bytes) ->
                NpnPaths.indexable(FileMeta(path, NpnPaths.fileName(path), bytes.size.toLong(), 0, false)) &&
                    bytes.isNotEmpty() &&
                    path !in livePaths &&
                    path !in duplicatePaths
            }
        if (unaccounted.isNotEmpty()) {
            fail(
                "settled PDFs that are neither a live document nor a known duplicate:\n  " +
                    unaccounted.keys.joinToString("\n  ") { "'$it' (${sha256Bytes(unaccounted.getValue(it)).take(ID)})" },
            )
        }
    }

    /**
     * The rebuild oracle: a second replica, over a copy of the files as they stand and with empty
     * index and device databases, opened and left to fold and rescan to rest, must show the library
     * [live] shows ([diff], which says what is compared and what is device-local).
     */
    private fun rebuildOracle(live: LibraryView) {
        val copy = fs.snapshot()
        val oracle = SimReplica(world, "oracle-installation", copy, GatedOcrEngine().apply { openGate() })
        try {
            runCatching { oracle.open() }.exceptionOrNull()?.let { fail("the rebuild could not open the library: ${it.reason()}") }
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
                    hiddenOnlyLive = { _, hidden -> hidden != Hidden.TRASHED || KnownIssues.TRASH_IS_NOT_REBUILT },
                    bothHold = { docId, a, b -> listOf(a, b).all { path -> fs.bytesAt(path)?.let(::sha256Bytes) == docId } },
                )
            if (differences.isNotEmpty()) fail("rebuild oracle: a fresh index shows another library:\n  " + differences.joinToString("\n  "))
        } finally {
            oracle.dispose()
        }
    }

    // ---- failing --------------------------------------------------------------------------------

    private fun fail(message: String): Nothing = throw SimFailure(report(message))

    private fun report(message: String): String =
        buildString {
            appendLine("Simulation failed at step ${world.step} (t=${world.scheduler.currentTime}ms): $message")
            appendLine()
            appendLine("Replay: ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seed=${config.seed} -Dnpn.sim.steps=${config.steps}")
            appendLine("Config: $config")
            appendLine("Faults armed: ${fs.faults.pending}")
            appendLine()
            appendLine("Trace (last $TRACE_LINES of ${trace.size}):")
            trace.takeLast(TRACE_LINES).forEach { appendLine("  $it") }
            appendLine()
            appendLine("Log (last $LOG_LINES of ${world.log.entries.size}):")
            world.log.entries.takeLast(LOG_LINES).forEach { appendLine("  $it") }
        }

    private companion object {
        const val ID = 10
        const val LATENCY_SALT = 31L
        const val SETTLE_MILLIS = 20L * 60L * 1000L
        const val AFTER_RESCAN_MILLIS = 2L * 60L * 1000L
        const val TRACE_LINES = 120
        const val LOG_LINES = 40
        const val STACK_LINES = 25
        const val STACK_CHARS = 3_000
    }
}
