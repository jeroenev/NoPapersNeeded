package dev.npn.core.sim

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.LibraryVerificationReport
import dev.npn.core.app.VerificationProblemKind
import dev.npn.core.db.QueueState
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalOp
import dev.npn.core.ocr.OcrSidecarName
import dev.npn.core.util.reason
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * After a full sync, a document Verify library reports missing on a replica is gone from every
 * folder: one any replica's folder holds at a document path is one the sync tool has brought every
 * replica, so a report of it missing is a false alarm. Returns the replica and why, or null.
 */
internal fun verifiedMissingButHeld(
    replicas: List<Pair<String, Simulation>>,
    folders: List<InMemoryLibraryFileSystem>,
): Pair<String, String>? {
    val held =
        folders
            .flatMap { fs -> fs.allFiles().filter { (path, _) -> NpnPaths.isDocumentPath(path) }.entries }
            .associate { (path, bytes) -> sha256Bytes(bytes) to path }
    return replicas.firstNotNullOfOrNull { (name, sim) ->
        sim.lastVerification
            ?.problems
            ?.mapNotNull { it.docId }
            ?.firstOrNull { it in held }
            ?.let { docId ->
                name to "verification on $name reports ${docId.take(VERIFY_ID)} missing after a full sync, " +
                    "while a folder holds its bytes at '${held.getValue(docId)}'"
            }
    }
}

private const val VERIFY_ID = 10

/** How one run is set up. Everything a seed decides is derived from it in [SimConfig.forSeed]. */
data class SimConfig(
    val seed: Long,
    val steps: Int,
    val profile: StorageProfile,
    val watchDropRate: Double,
    val watchOverflowRate: Double,
    /** Run the expensive invariants (rebuild oracle, files hold their hashes) every this many steps, and at the end. */
    val quiesceEvery: Int = 40,
) {
    companion object {
        /** The storage and watcher knobs a seed picks, so a seed alone replays a run. */
        fun forSeed(
            seed: Long,
            steps: Int,
        ): SimConfig {
            val random = Random(seed xor CONFIG_SALT)
            return SimConfig(
                seed = seed,
                steps = steps,
                profile = StorageProfile.draw(random),
                watchDropRate = if (random.chance(30)) 0.2 else 0.0,
                watchOverflowRate = if (random.chance(20)) 0.05 else 0.0,
            )
        }

        private const val CONFIG_SALT = 0x5EED_C0DEL
    }
}

/**
 * The findings of the simulation that are written up and not fixed yet (docs/SIMULATION.md): a
 * switch for what the app does not do yet, and the failures that are reported findings. A seed that
 * hits one stops there (its library is broken from then on), and is counted instead of failing the
 * test, so a long run goes on to find what else there is. A new finding is never added here before
 * it is written up; a fixed one is removed.
 */
object KnownIssues {
    /**
     * This build writes no `doc-undelete` yet (`JournalOp.DocUndelete.WRITTEN` is false; ARCHITECTURE.md
     * 6.4), the documented cost of keeping builds from before the op working: the bytes of a document
     * deleted for good that come back (an import, or a file a sync tool delivers) bring it back on the
     * device that imported them, for that session, and in the scan that finds the file, and nowhere
     * else: devices disagree on whether the library shows it, and a device disagrees with a rebuild of
     * its own index. Off once this build writes the op.
     */
    const val DELETED_RETURNS_UNRECORDED = !JournalOp.DocUndelete.WRITTEN

    /**
     * Cloud sync settles each document's name per pass, one rename at a time, and a rename waits
     * while its name is taken here: two documents that swapped names, or a chain of renames whose
     * last name another document holds, stay apart, so replicas syncing through the cloud can end
     * with the same documents under other file names (never other documents, metadata or
     * lifecycle). A full-sync convergence check of cloud seeds then compares the libraries without
     * the documents' paths, and only traces the paths that differ. Off once renames are settled with
     * a temporary name (or by the journal naming every path).
     */
    const val CLOUD_NAMES_MAY_DIFFER = true

    /** Each finding by the phrases its failure message holds, all of them. */
    val knownFailures: Map<List<String>, String> = emptyMap()

    /** The finding [message] reports, or null for a new failure. */
    fun classify(message: String): String? = knownFailures.entries.firstOrNull { it.key.all(message::contains) }?.value
}

/** A broken invariant: the run stops at the first. */
class SimFailure(
    message: String,
    /** The [KnownIssues.knownFailures] entry this failure is, or null for a new one. */
    val known: String? = null,
) : AssertionError(message)

/**
 * One replica of a [SyncSimulation]: its session driven step by step by a [SimGenerator], every
 * invariant of its own checked after each step, and the expensive ones (the rebuild oracle above
 * all) at quiescence. A failure throws [SimFailure] carrying the seed, the configuration and the
 * trace.
 */
internal class Simulation(
    val config: SimConfig,
    /** What it shares with the other replicas, and its own parts. */
    setup: ReplicaSetup,
    /** Changes the copy of the files the rebuild oracle opens: the harness's own tests plant a difference. */
    private val tamperWithRebuild: (InMemoryLibraryFileSystem) -> Unit,
) {
    val world = setup.world
    val fs = setup.fs
    val replica = setup.replica

    /** Every PDF the simulation made, by hash, so a later step can import its bytes again. */
    val known = setup.known

    private val trace = setup.trace
    private val label = "[${setup.name}] "

    private val generator =
        SimGenerator(setup.random, this, setup.labelBase, setup.watched, setup.deleteAfterImport, setup.syncScan)

    init {
        replica.onOpen = generator::startImporter
    }

    private val session: LibrarySessionImpl get() = replica.session ?: throw SimFailure(report("no session is open"))

    // ---- the phases of a run, for the SyncSimulation driving the replicas -----------------------

    fun open() = openReplica()

    /** One step: the generator's next, its outcome traced, and the cheap invariants. */
    fun step(step: Int) {
        val planned = generator.next(snapshot())
        val outcome = execute(planned)
        trace += "#$step t=${world.scheduler.currentTime} $label${planned.text} -> $outcome"
        harvest()
        checkCheap()
    }

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

    // ---- the process's lifecycle ----------------------------------------------------------------

    /** Open the replica's session; an open an injected fault failed is retried once the faults are gone. */
    private fun openReplica() {
        val first = runCatching { replica.open() }
        first.exceptionOrNull()?.let { failure ->
            if (!failure.isInjected() && isBugFailure(failure)) fail("opening the library failed: ${failure.reason()}")
            trace += "   open failed (${failure.reason()}); faults cleared, opening again"
            fs.faults.disarmAll()
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
        fs.publications.violations
            .firstOrNull()
            ?.let { fail("trash stat not recorded as moved: $it") }
        fs.publications.ackViolations
            .firstOrNull()
            ?.let { fail("an acknowledgment record breaks its rules: $it") }
        fs.deleteAuthority.violations
            .firstOrNull()
            ?.let { fail("a delete without authority: $it") }
        checkLog()
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

    /** Faults cleared, OCR let through and every background operation ended: where settling starts. */
    fun letThrough() {
        fs.faults.disarmAll()
        replica.ocr.openGate()
        finishBackground()
    }

    /**
     * Let everything settle ([letThrough], time on past every settle window, one more rescan), for
     * [checkAtRest]. The restore settle window is the quiescence's to pass first
     * ([SyncSimulation.quiesce]), once for every replica.
     */
    fun settle() {
        trace += "   -- ${label}quiescence at t=${world.scheduler.currentTime}"
        letThrough()
        world.advance(SETTLE_MILLIS)
        // A fault a concurrent step armed while it ran (Planned.Group.during) may have landed since.
        fs.faults.disarmAll()
        rescanAtRest("quiescent rescan")
        // A rescan reads the journal whole, so once it succeeded nothing refuses writes.
        session.writeBlock.value?.let { fail("writes stay blocked ($it) after a rescan that succeeded") }
        world.advance(AFTER_RESCAN_MILLIS)
    }

    /** A rescan at rest, [what] the trace calls it: one that fails, faults cleared, is a finding. */
    fun rescanAtRest(what: String) {
        val rescanned = world.run(what) { session.rescan() }
        rescanned.exceptionOrNull()?.let { fail("the $what failed: ${it::class.simpleName}: ${it.reason()}") }
    }

    /**
     * Nothing reached a scope's handler and nothing logged is a bug. Part of [checkCheap], and run
     * again once the checks at rest are done: a rebuild oracle's own session logs into the same
     * world, and after the last of them no step's [checkCheap] follows.
     */
    fun checkLog() {
        world.uncaught.firstOrNull()?.let { fail("an exception reached a scope's handler: $it") }
        world.log.bugs.firstOrNull()?.let {
            fail("a bug was logged: $it\n${it.error?.stackTraceToString()?.take(STACK_CHARS)}")
        }
    }

    /**
     * The invariants at rest, once [settle]d: each live document's file holds its bytes, every
     * settled PDF in the folder is a document or a known duplicate, and a fresh index over the same
     * files shows the same library ([rebuildOracle]). Returns the library the replica shows.
     */
    fun checkAtRest(): LibraryView {
        checkCheap()
        val live = world.read("live view") { LibraryView.read(session, replica.indexDb) }
        checkFilesHoldTheirHashes(live)
        checkTrashEntries()
        world.read("the fold") { lingeringOriginals(fs, replica.indexDb.fold(), live) }?.let { fail(it) }
        checkRowStats()
        // Every operation ended, and the rescans settled what a crash or a failure cut short.
        world.read("open intents") { session.intents.all() }.takeIf { it.isNotEmpty() }?.let { open ->
            fail(
                "operations still noted as under way at rest: ${open.joinToString {
                    "${it.kind} of ${it.docId.take(
                        ID,
                    )}"
                }}",
            )
        }
        checkVerification(live)
        rebuildOracle(live)
        replica.ocr.closeGate()
        trace += "   -- ${label}quiescent: ${live.live.size} live, ${live.hidden.size} hidden"
        return live
    }

    /**
     * The last report [checkVerification] read at rest, for the checks across replicas after a full
     * sync ([SyncSimulation]).
     */
    var lastVerification: LibraryVerificationReport? = null
        private set

    /**
     * Verify library holds at rest: nothing but documents whose files are gone from this folder, and
     * every one of those. The journal objects are whole and the folder's documents hold their bytes,
     * so any other problem is a false alarm. A document it reports missing is one no file at a
     * document path here holds (a rescan would have found it), never under an internal name, and one
     * the library lists as missing is always reported. A document the index has no row of that it
     * reports missing must not be one whose last copy here the app took itself: the journal would
     * then show a document the app removed (a split's part it took back, say). The outside world and
     * the sync tool, carrying another device's change, are the injected faults this allows.
     */
    private fun checkVerification(view: LibraryView) {
        val report = world.read("verify library") { session.maintenance.verifyLibrary() }
        lastVerification = report
        val wrong =
            report.problems.filter {
                it.kind != VerificationProblemKind.DOCUMENT_MISSING &&
                    it.kind != VerificationProblemKind.DOCUMENT_KNOWN_MISSING
            }
        if (wrong.isNotEmpty()) fail("verification found damage at rest:\n  " + wrong.joinToString("\n  "))
        report.problems.firstOrNull { it.relPath.orEmpty().startsWith("${NpnPaths.NPN_DIR}/") }?.let {
            fail("verification named a document by an internal path: $it")
        }
        val held =
            fs
                .allFiles()
                .filter { (path, _) -> NpnPaths.isDocumentPath(path) }
                .entries
                .associate { (path, bytes) -> sha256Bytes(bytes) to path }
        for (problem in report.problems) {
            val docId = problem.docId ?: fail("verification reported a document with no id: $problem")
            held[docId]?.let { fail("verification reports ${docId.take(ID)} missing while '$it' holds its bytes") }
            if (problem.kind == VerificationProblemKind.DOCUMENT_MISSING && !problem.openable) {
                fs.losses.recordsOf(docId).lastOrNull { it.lastHere }?.takeIf { it.loss == Loss.APP }?.let {
                    fail(
                        "verification reports ${docId.take(ID)} missing, which the journal shows but the " +
                            "app took from '${it.path}' at t=${it.atMillis}",
                    )
                }
            }
        }
        val reported = report.problems.mapNotNullTo(HashSet()) { it.docId }
        view.hidden
            .filterValues { it == Hidden.MISSING }
            .keys
            .firstOrNull { it !in reported && it !in supersededDocs() }
            ?.let { fail("verification passed over ${it.take(ID)}, which the library lists as missing") }
    }

    /** Every document a relink superseded, as this replica's index recorded the events. */
    private fun supersededDocs(): Set<String> =
        world.read("superseded") {
            replica.indexDb
                .rows("SELECT DISTINCT documentId FROM metadata_events WHERE operation = 'relink'")
                .mapNotNull { it[0] }
                .toSet()
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
        val operations = if (KnownIssues.DELETED_RETURNS_UNRECORDED) "('relink', 'doc-delete')" else "('relink')"
        val superseded =
            world.read("superseded") {
                replica.indexDb
                    .rows("SELECT DISTINCT documentId FROM metadata_events WHERE operation IN $operations")
                    .mapNotNull { it[0] }
                    .toSet()
            }
        val back = filesBackWithTheirTrashStat()
        val unaccounted =
            fs.allFiles().filter { (path, bytes) ->
                NpnPaths.indexable(FileMeta(path, NpnPaths.fileName(path), bytes.size.toLong(), 0, false)) &&
                    bytes.isNotEmpty() &&
                    path !in livePaths &&
                    path !in duplicatePaths &&
                    sha256Bytes(bytes) !in superseded &&
                    path !in back
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

    /** [filesBackWithTheirTrashStat] over this replica's fold and last scan report. */
    private fun filesBackWithTheirTrashStat(): Set<String> {
        val (fold, reported) =
            world.read("files back") {
                replica.indexDb.fold() to
                    session.ingest.lastScanReport
                        .first()
                        ?.filesBack
                        .orEmpty()
            }
        return filesBackWithTheirTrashStat(fs, fold, reported).getOrElse { fail(it.message.orEmpty()) }
    }

    /**
     * Every file in the trash holds the bytes of the document its name names ([NpnPaths.trashPath]
     * carries the hash's first characters), and every keep store entry the bytes its name is the
     * hash of ([NpnPaths.Keep.path]: written once, content-addressed). One that holds other bytes is
     * a file the trash took under a document's name, such as a file another process put at the
     * document's path between
     * the trash's check of it and the move: hidden from the library for good, as no rescan indexes
     * the trash and the purge and a restore both pass it by. A name that carries no hash (a sync
     * tool's conflict copy, say) is not judged.
     */
    private fun checkTrashEntries() {
        val foreign =
            fs.allFiles().filter { (path, bytes) ->
                val prefix =
                    NpnPaths.Keep.parse(path)?.first
                        ?: NpnPaths
                            .trashHashPrefix(NpnPaths.fileName(path))
                            ?.takeIf { path.startsWith("${NpnPaths.TRASH_DIR}/") && NpnPaths.Temp.kindOf(path) == null }
                prefix != null && !sha256Bytes(bytes).startsWith(prefix)
            }
        if (foreign.isNotEmpty()) {
            fail(
                "files in the trash or the keep store hold bytes no document of their name has:\n  " +
                    foreign.keys.joinToString("\n  ") { path ->
                        "'$path' (${sha256Bytes(foreign.getValue(path)).take(ID)}): " +
                            fs.historyOf(path).joinToString("; ")
                    },
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
        // No OCR engine: the rebuild runs no OCR, so it publishes no sidecar of a document the
        // folder has one of, and no page of it ever waits on a gate.
        val oracle = SimReplica(world, "oracle-installation", copy, GatedOcrEngine(installed = false))
        try {
            runCatching { oracle.open() }.exceptionOrNull()?.let {
                fail(
                    "the rebuild could not open the library: ${it.reason()}",
                )
            }
            val opened = oracle.session!!
            world.read("rebuild's initial rescan") { opened.awaitInitialRescan() }
            world.advance(AFTER_RESCAN_MILLIS)
            checkRebuildPublished(oracle, copy)
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
                    KnownIssues.DELETED_RETURNS_UNRECORDED && "(live index: DELETED)" in line
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

    /**
     * The rebuild publishes nothing but its device marker, and its own OCR sidecar of a document
     * [copy] held no sidecar of: the live replica's runs of that document failed until its queue gave
     * up, and the rebuild's fresh queue runs it once (its engine not installed, it keeps the text
     * layer). Its trash purge publishes nothing. Anything else it published (a restore it recorded,
     * a move, a field seeding) is a write the live replica owed the library and did not make.
     */
    private fun checkRebuildPublished(
        oracle: SimReplica,
        copy: InMemoryLibraryFileSystem,
    ) {
        val deviceId = oracle.session!!.deviceId
        val marker = "${NpnPaths.DEVICES_DIR}/$deviceId/${oracle.installationId}"
        val sidecarsHeld =
            copy
                .allFiles()
                .keys
                .filter { it.startsWith("${NpnPaths.OCR_DIR}/") && it !in copy.appPublished }
                .mapNotNullTo(HashSet()) { OcrSidecarName.parse(NpnPaths.fileName(it))?.hash }

        fun ownSidecarOfANewDocument(path: String): Boolean {
            if (!path.startsWith("${NpnPaths.OCR_DIR}/")) return false
            val name = OcrSidecarName.parse(NpnPaths.fileName(path)) ?: return false
            return OcrSidecarName.writer(name.writerKey).deviceId == deviceId && name.hash !in sidecarsHeld
        }
        // Its own acknowledgment records vouch for nothing a live replica owed: they say what it applied.
        val ownAcks = "${NpnPaths.ACKS_DIR}/$deviceId/"
        val unexpected =
            copy.appPublished.filterNot {
                it.startsWith(marker) || it.startsWith(ownAcks) ||
                    ownSidecarOfANewDocument(it)
            }
        if (unexpected.isNotEmpty()) {
            fail(
                "the rebuild published what the live replica owed the library:\n  " +
                    unexpected.sorted().joinToString("\n  ") { path ->
                        "'$path': " +
                            copy
                                .bytesAt(path)
                                ?.decodeToString()
                                ?.trim()
                                ?.take(PUBLISHED_CHARS)
                    },
            )
        }
    }

    /** [eventsDiffer] for [docId], between this replica's index and [oracle]'s over [copy]. */
    private fun eventsOf(
        docId: String,
        oracle: SimReplica,
        copy: InMemoryLibraryFileSystem,
    ): String = world.read("events of $docId") { eventsDiffer(docId, replica.indexDb, oracle.indexDb, fs, copy) }

    // ---- failing --------------------------------------------------------------------------------

    fun fail(message: String): Nothing = throw SimFailure(report(message), KnownIssues.classify(message))

    private fun report(message: String): String =
        buildString {
            appendLine("Simulation failed at step ${world.step} (t=${world.scheduler.currentTime}ms) $label: $message")
            appendLine()
            // The seed and the step count decide everything else (SimConfig.forSeed, SyncConfig.forSeed).
            appendLine(
                "Replay: ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*' " +
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
            append(folderAndRows())
        }

    /** This replica's library files (outside `.npn/`, and the trash) and its index rows, for a failure report. */
    fun folderAndRows(): String =
        buildString {
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
        private const val ID = 10
        private const val SETTLE_MILLIS = 20L * 60L * 1000L
        private const val AFTER_RESCAN_MILLIS = 2L * 60L * 1000L
        private const val TRACE_LINES = 120
        private const val LOG_LINES = 40
        private const val STACK_LINES = 25
        private const val STACK_CHARS = 3_000
        private const val REPORT_READ_MILLIS = 60_000L
        private const val PUBLISHED_CHARS = 600
    }
}
