package dev.npn.core.sim

import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.app.SplitOutcome
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.model.FieldKeys
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.util.NpnLog
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderFile
import dev.npn.core.watch.WatchedFolderImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import java.nio.file.Path
import kotlin.random.Random

/** The operations the first generator ([SimGenerator]) does not take, each with its weight. */
enum class ExtraKind(
    val weight: Int,
) {
    BAKE(3),
    IMPORT_MULTIPAGE(3),
    SPLIT(3),
    RELINK_MISSING(2),
    RULE_SAVE(3),
    RULE_DELETE(1),
    RULE_TOGGLE(1),
    WATCHED_DROP(3),
    WATCHED_CHUNKED(1),
    WATCHED_KNOWN(1),
    WATCHED_OVERWRITE(1),
    WATCHED_REMOVE(1),
    WATCHED_SWEEP(2),
    HARD_CRASH(2),
    PARTIAL_LISTING(2),
    WITH_ANOTHER(3),
}

/**
 * A watched inbox folder held in memory, outside the library: a scanner app's output folder. Its
 * files are read through the library filesystem's [InMemoryLibraryFileSystem.externalSources] hook,
 * by the path [ExternalSource] names. A delete keeps to the interface's contract (only while the
 * file still holds what was imported), and records as a violation a delete of bytes the library
 * has no document of.
 */
internal class SimWatchedFolder(
    private val name: String,
    private val now: () -> Long,
    /** Whether the library has a document of these bytes: holds them, or a relink superseded them. */
    private val accounted: suspend (hash: String) -> Boolean,
) : WatchedFolder {
    private class Entry(
        var bytes: ByteArray,
        var mtime: Long,
    )

    private val files = sortedMapOf<String, Entry>()
    private val events = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Deletes of a file whose bytes the library held nowhere: the importer threw away a document. */
    val violations = ArrayList<String>()

    val root = "/sim-watched/$name"

    val names: List<String> get() = files.keys.toList()

    fun bytesFor(path: Path): ByteArray? =
        path.toString().takeIf { it.startsWith("$root/") }?.let { files[it.removePrefix("$root/")]?.bytes?.copyOf() }

    fun write(
        file: String,
        bytes: ByteArray,
    ) {
        files[file] = Entry(bytes.copyOf(), now())
        events.tryEmit(Unit)
    }

    /** Write [bytes] as [file] in place, [chunks] pieces [intervalMillis] apart, as a scanner app saving slowly. */
    suspend fun writeInChunks(
        file: String,
        bytes: ByteArray,
        chunks: Int,
        intervalMillis: Long,
    ) {
        val step = (bytes.size + chunks - 1) / chunks
        var written = 0
        while (written < bytes.size) {
            written = minOf(bytes.size, written + step)
            val entry = files.getOrPut(file) { Entry(ByteArray(0), now()) }
            entry.bytes = bytes.copyOf(written)
            entry.mtime = now()
            events.tryEmit(Unit)
            if (written < bytes.size) delay(intervalMillis)
        }
    }

    fun remove(file: String) {
        files.remove(file)
        events.tryEmit(Unit)
    }

    override suspend fun listPdfs(): List<WatchedFolderFile> =
        files.filterKeys { it.endsWith(".pdf") }.map { (file, entry) ->
            WatchedFolderFile(
                "$root/$file",
                file,
                entry.bytes.size.toLong(),
                entry.mtime,
                ExternalSource(Path.of(root, file)),
            )
        }

    override suspend fun delete(
        file: WatchedFolderFile,
        docId: String,
    ): Boolean {
        val entry = files[file.name] ?: return false
        if (!file.stillHolds(docId, entry.bytes.size.toLong(), entry.mtime) { sha256Bytes(entry.bytes) }) return false
        val hash = sha256Bytes(entry.bytes)
        if (!accounted(hash)) {
            violations += "$name deleted '${file.name}' (${hash.take(ID)}), which the library holds nowhere"
        }
        files.remove(file.name)
        events.tryEmit(Unit)
        return true
    }

    override fun changes(): Flow<Unit> = events.asSharedFlow()

    private companion object {
        const val ID = 10
    }
}

/**
 * Plans the operations [SimGenerator] does not: bake in place, split, the relink of a missing
 * document to other bytes, edits of the auto-match rules, the watched inbox folder, a hard crash
 * and a partial directory listing. Like the first generator it draws every choice from its own
 * seeded [random] and describes each step fully in its text.
 */
internal class ExtraPlanner(
    private val random: Random,
    private val sim: Simulation,
    private val name: String,
    /** This replica's watched folder, or null where the seed gave it none. */
    private val watched: SimWatchedFolder?,
    /** Whether the watched folder's importer deletes what it imported. */
    private val deleteAfterImport: Boolean,
    private val log: NpnLog,
) {
    private val kinds =
        ExtraKind.entries.filter { kind ->
            (kind !in WATCHED_KINDS || watched != null) &&
                (kind != ExtraKind.PARTIAL_LISTING || sim.fs.options.profile.saf)
        }
    private val totalWeight = kinds.sumOf { it.weight }
    private var made = 0

    /**
     * Shared by every importer of this replica's folder in one process, so two sweeps never
     * overlap. A process's own: a killed one may die holding it.
     */
    private var sweepLock = Mutex()

    /** The continuous importer a host runs beside the session, started in its scope. */
    fun startImporter(
        session: LibrarySessionImpl,
        scope: CoroutineScope,
    ) {
        sweepLock = Mutex()
        val folder = watched ?: return
        importer(session, folder).start(scope, POLL_MILLIS, sim.world.scheduler.timeSource)
    }

    private fun importer(
        session: LibrarySessionImpl,
        folder: SimWatchedFolder,
    ) = WatchedFolderImporter(
        ingest = session,
        folder = folder,
        deleteAfterImport = { deleteAfterImport },
        log = log,
        sweepLock = sweepLock,
        clock = sim.world.clock,
    )

    fun next(s: Snapshot): Planned? {
        repeat(ATTEMPTS) {
            plan(pick(), s)?.let { planned -> return planned }
        }
        return null
    }

    private fun pick(): ExtraKind {
        var roll = random.nextInt(totalWeight)
        for (kind in kinds) {
            roll -= kind.weight
            if (roll < 0) return kind
        }
        return kinds.last()
    }

    /** How each kind is planned; every kind has one (checked at construction). */
    private val planners: Map<ExtraKind, (Snapshot) -> Planned?> =
        mapOf(
            ExtraKind.BAKE to { s -> s.live.pickOrNull()?.let(::bake) },
            ExtraKind.IMPORT_MULTIPAGE to { _ -> importMultipage() },
            ExtraKind.SPLIT to { s ->
                s.live
                    .filter { doc -> pagesOf(doc) > 1 }
                    .pickOrNull()
                    ?.let(::split)
            },
            ExtraKind.RELINK_MISSING to ::relinkMissing,
            ExtraKind.RULE_SAVE to { _ -> saveRule() },
            ExtraKind.RULE_DELETE to { _ -> ruleAct("delete") { session, id -> session.rules.delete(id) } },
            ExtraKind.RULE_TOGGLE to { _ -> ruleToggle() },
            ExtraKind.WATCHED_DROP to { _ -> watchedDrop(newPdf()) },
            ExtraKind.WATCHED_CHUNKED to { _ -> watchedChunked() },
            ExtraKind.WATCHED_KNOWN to
                { _ ->
                    sim.known.keys
                        .sorted()
                        .pickOrNull()
                        ?.let { hash -> watchedDrop(sim.known.getValue(hash)) }
                },
            ExtraKind.WATCHED_OVERWRITE to { _ -> watched?.names?.pickOrNull()?.let(::watchedOverwrite) },
            ExtraKind.WATCHED_REMOVE to { _ -> watched?.names?.pickOrNull()?.let(::watchedRemove) },
            ExtraKind.WATCHED_SWEEP to { _ -> watchedSweep() },
            ExtraKind.HARD_CRASH to
                { _ -> Planned.Control("hard crash (killed) and reopen") { sim.hardCrashAndReopen() } },
            ExtraKind.PARTIAL_LISTING to { _ -> partialListing() },
            ExtraKind.WITH_ANOTHER to ::withAnother,
        )

    init {
        check(planners.keys == ExtraKind.entries.toSet()) { "Every ExtraKind needs a planner" }
    }

    private fun plan(
        kind: ExtraKind,
        s: Snapshot,
    ): Planned? = planners.getValue(kind)(s)

    private fun pagesOf(doc: SimDoc): Int = sim.known[doc.docId]?.let(SimPdf::pagesOf) ?: 1

    private fun ruleToggle(): Planned.Act {
        val on = random.nextBoolean()
        return ruleAct("enabled=$on") { session, id -> session.rules.setEnabled(id, on) }
    }

    // ---- documents ------------------------------------------------------------------------------

    private fun bake(doc: SimDoc) =
        Planned.Act("bake ${doc.short()} in place") { session ->
            when (val outcome = session.content.bakeInPlace(doc.docId)) {
                BakeOutcome.Baked -> "baked"
                is BakeOutcome.Failed -> "failed: ${outcome.reason}"
            }
        }

    private fun newPdf(pages: Int = 1): ByteArray {
        val label = "x$name-${made++}"
        val text = if (random.chance(60)) "text of $label ${random.pick(WORDS)}" else null
        val bytes = SimPdf.bytes(label, text, sim.world.sizes.sizeOf(label), pages = pages)
        sim.known[sha256Bytes(bytes)] = bytes
        return bytes
    }

    private fun importMultipage(): Planned.Act {
        val bytes = newPdf(pages = random.nextInt(2, MAX_PAGES + 1))
        val file = random.pick(NAMES)
        return Planned.Act(
            "import ${SimPdf.pagesOf(bytes)}-page ${sha256Bytes(bytes).take(ID)} as '$file'",
        ) { session ->
            when (val outcome = session.importPdfBytes(bytes, file)) {
                is ImportOutcome.Imported -> "imported ${outcome.docId.take(ID)} at '${outcome.relPath}'"
                is ImportOutcome.Duplicate -> "duplicate of ${outcome.existingDocId.take(ID)}"
                is ImportOutcome.Failed -> "failed: ${outcome.reason}"
            }
        }
    }

    private fun split(doc: SimDoc): Planned.Act {
        val pages = SimPdf.pagesOf(sim.known.getValue(doc.docId))
        val cuts = (1 until pages).filter { random.nextBoolean() }.ifEmpty { listOf(1 + random.nextInt(pages - 1)) }
        val bounds = listOf(0) + cuts + listOf(pages)
        val segments = bounds.zipWithNext { from, to -> from until to }
        return Planned.Act("split ${doc.short()} into $segments") { session ->
            when (val outcome = session.maintenance.splitPdf(doc.docId, segments)) {
                is SplitOutcome.Done -> "parts ${outcome.partDocIds.joinToString { it.take(ID) }}"
                is SplitOutcome.Failed -> "failed: ${outcome.reason}"
            }
        }
    }

    private fun relinkMissing(s: Snapshot): Planned.Act? {
        val missing =
            sim.world
                .read("missing rows") {
                    sim.replica.indexDb.rows(
                        "SELECT docId FROM documents WHERE missing = 1 AND trashed = 0 AND deleted = 0 ORDER BY docId",
                    )
                }.map { it[0]!! }
        val old = missing.pickOrNull() ?: return null
        val target = s.live.pickOrNull() ?: return null
        return Planned.Act("relink missing ${old.take(ID)} to ${target.short()}") { session ->
            session.maintenance.applyRelink(old, target.docId)
            "done"
        }
    }

    // ---- auto-match rules -----------------------------------------------------------------------

    private fun saveRule(): Planned.Act {
        val id = "rule-${random.nextInt(RULES)}"
        val onName = random.chance(30)
        val rule =
            AutoMatchRule(
                id = id,
                name = "Rule $id ${random.nextInt(LABELS)}",
                enabled = random.chance(85),
                field = if (onName) RuleField.FileName else RuleField.Content,
                algorithm =
                    if (onName) {
                        MatchAlgorithm.ANY_WORD
                    } else {
                        random.pick(
                            listOf(MatchAlgorithm.ANY_WORD, MatchAlgorithm.EXACT),
                        )
                    },
                pattern = if (onName) random.pick(listOf("scan", "invoice", "Letter")) else random.pick(WORDS),
                tags = listOfNotNull(random.pick(TAGS).takeIf { random.chance(60) }),
                sets =
                    if (random.chance(60)) {
                        mapOf(
                            random.pick(listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, "project")) to
                                random.pick(WORDS),
                        )
                    } else {
                        emptyMap()
                    },
                position = random.nextInt(RULES),
            )
        return Planned.Act("save rule $rule") { session ->
            session.rules.save(rule)
            "saved"
        }
    }

    private fun ruleAct(
        what: String,
        body: suspend (LibrarySessionImpl, String) -> Unit,
    ): Planned.Act {
        val id = "rule-${random.nextInt(RULES)}"
        return Planned.Act("rule $id $what") { session ->
            body(session, id)
            "done"
        }
    }

    // ---- the watched folder ---------------------------------------------------------------------

    private fun watchedDrop(bytes: ByteArray): Planned.Control? {
        val folder = watched ?: return null
        val file = random.pick(NAMES)
        return Planned.Control(
            "watched folder of $name: write ${sha256Bytes(bytes).take(ID)} as '$file'",
            instant = true,
        ) {
            folder.write(file, bytes)
            "done"
        }
    }

    private fun watchedChunked(): Planned.Control? {
        val folder = watched ?: return null
        val bytes = newPdf()
        val file = random.pick(NAMES)
        val chunks = random.nextInt(2, CHUNKS)
        val interval = random.nextLong(200, CHUNK_INTERVAL_MILLIS)
        val text = "watched folder of $name: write ${sha256Bytes(
            bytes,
        ).take(ID)} as '$file' in $chunks chunks ${interval}ms apart"
        return Planned.Control(text, instant = true) {
            sim.world.launch { folder.writeInChunks(file, bytes, chunks, interval) }
            "started"
        }
    }

    private fun watchedOverwrite(file: String): Planned.Control {
        val bytes = newPdf()
        return Planned.Control(
            "watched folder of $name: overwrite '$file' with ${sha256Bytes(bytes).take(ID)}",
            instant = true,
        ) {
            watched?.write(file, bytes)
            "done"
        }
    }

    private fun watchedRemove(file: String) =
        Planned.Control("watched folder of $name: remove '$file'", instant = true) {
            watched?.remove(file)
            "done"
        }

    private fun watchedSweep(): Planned.Act? {
        val folder = watched ?: return null
        return Planned.Act("sweep the watched folder once (deleteAfterImport=$deleteAfterImport)") { session ->
            importer(session, folder).scanOnce().toString()
        }
    }

    // ---- the process and the storage ------------------------------------------------------------

    private fun partialListing(): Planned.Control {
        val count = random.nextInt(1, PARTIAL_MAX)
        return Planned.Control(
            "the provider's next $count listing(s) come back partial (still loading)",
            instant = true,
        ) {
            sim.fs.partialListings += count
            "armed"
        }
    }

    private fun withAnother(s: Snapshot): Planned? {
        val own =
            listOf(
                ExtraKind.BAKE,
                ExtraKind.SPLIT,
                ExtraKind.RULE_SAVE,
                ExtraKind.RELINK_MISSING,
                ExtraKind.WATCHED_SWEEP,
            ).filter { it in kinds }
                .let { plan(random.pick(it), s) as? Planned.Act }
                ?: return null
        val other = sim.nextPlanned() as? Planned.Act ?: return null
        return Planned.Group(listOf(own, other))
    }

    private fun <T> List<T>.pickOrNull(): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun SimDoc.short() = "${docId.take(ID)}@'$relPath'"

    private companion object {
        const val ATTEMPTS = 10
        const val ID = 8
        const val MAX_PAGES = 4
        const val RULES = 4
        const val LABELS = 5
        const val CHUNKS = 6
        const val CHUNK_INTERVAL_MILLIS = 3_000L
        const val PARTIAL_MAX = 4
        const val POLL_MILLIS = 60_000L
        val NAMES = listOf("scan.pdf", "invoice.pdf", "Letter.pdf", "scan (1).pdf")
        val WATCHED_KINDS =
            setOf(
                ExtraKind.WATCHED_DROP,
                ExtraKind.WATCHED_CHUNKED,
                ExtraKind.WATCHED_KNOWN,
                ExtraKind.WATCHED_OVERWRITE,
                ExtraKind.WATCHED_REMOVE,
                ExtraKind.WATCHED_SWEEP,
            )
        val WORDS = listOf("ACME", "Globex", "Initech", "Umbrella Corp")
        val TAGS = listOf("tax", "bank", "receipts", "todo")
    }
}
