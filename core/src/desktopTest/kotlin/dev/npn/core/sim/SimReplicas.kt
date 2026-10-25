package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.WriteBlock
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalOp
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import kotlin.random.Random

/**
 * What a seed decides about the devices and the sync tool between them, beside its [SimConfig]
 * (which the first replica keeps): how many replicas, each one's storage, whether it has a watched
 * inbox folder, and how the tool behaves. `-Dnpn.sim.replicas=<n>` overrides the count.
 */
data class SyncConfig(
    val replicas: Int,
    val profiles: List<StorageProfile>,
    val mtimes: List<Boolean>,
    val watched: List<Boolean>,
    val deleteAfterImport: List<Boolean>,
    val options: SyncOptions,
) {
    companion object {
        fun forSeed(config: SimConfig): SyncConfig {
            val random = Random(config.seed xor SYNC_SALT)
            val drawn = MIN_REPLICAS + random.nextInt(MAX_REPLICAS - MIN_REPLICAS + 1)
            val count = System.getProperty("npn.sim.replicas")?.toInt() ?: drawn
            val profiles =
                List(count) { i ->
                    if (i == 0) {
                        config.profile
                    } else {
                        when (random.nextInt(100)) {
                            in 0 until 55 -> StorageProfile.DIRECT_LINKS
                            in 55 until 80 -> StorageProfile.DIRECT_RENAME
                            else -> StorageProfile.SAF
                        }
                    }
                }
            return SyncConfig(
                replicas = count,
                profiles = profiles,
                mtimes =
                    profiles.mapIndexed { i, p ->
                        if (i == 0) config.mtimes else !(p == StorageProfile.SAF && random.nextBoolean())
                    },
                watched = List(count) { random.chance(50) },
                deleteAfterImport = List(count) { random.nextBoolean() },
                options =
                    SyncOptions(
                        tool = random.pick(SyncTool.entries),
                        inPlacePdfPercent = if (random.nextBoolean()) IN_PLACE_PERCENT else 0,
                        duplicatePercent = DUPLICATE_PERCENT,
                    ),
            )
        }

        private const val SYNC_SALT = 0x5_7AC_1L
        private const val MIN_REPLICAS = 2
        private const val MAX_REPLICAS = 4
        private const val IN_PLACE_PERCENT = 15
        private const val DUPLICATE_PERCENT = 10
    }
}

/**
 * The findings of the multi-replica simulation that are written up and not fixed yet, as
 * [KnownIssues] holds the single replica's: a seed that hits one stops there and is counted, so a
 * long run goes on to find what else there is. A fixed one is removed.
 */
object SyncKnownIssues {
    /**
     * Finding 8, the documented cost of `doc-undelete` not being written yet (ARCHITECTURE.md 6.4):
     * the bytes of a document deleted for good that come back (an import, or a file a sync tool
     * delivers) bring it back on the device that imported them, for that session, and in the scan
     * that finds the file, and nowhere else: devices disagree on whether the library shows it, and a
     * device disagrees with a rebuild of its own index. Off once this build writes the op.
     */
    const val DELETED_RETURNS_UNRECORDED = !JournalOp.DocUndelete.WRITTEN

    /** Each finding by the phrases its failure message holds, all of them. */
    val knownFailures: Map<List<String>, String> = emptyMap()

    /** [failure] as a known finding when it is one, else as it is. */
    internal fun classify(failure: SimFailure): SimFailure {
        if (failure.known != null) return failure
        val message = failure.message.orEmpty()
        val known = knownFailures.entries.firstOrNull { it.key.all(message::contains) }?.value ?: return failure
        return SimFailure(message, known)
    }
}

/** What one replica of a [SyncSimulation] shares with the others, and what is its own, for its [Simulation]. */
internal class ReplicaSetup(
    val world: SimWorld,
    val name: String,
    val random: Random,
    val fs: InMemoryLibraryFileSystem,
    val replica: SimReplica,
    val known: HashMap<String, ByteArray>,
    val trace: MutableList<String>,
    val labelBase: Int,
)

/** The world's log, each entry naming the replica it came from. */
internal class ReplicaLog(
    private val name: String,
    private val log: NpnLog,
) : NpnLog {
    override fun log(
        level: NpnLog.Level,
        message: String,
        error: Throwable?,
    ) = log.log(level, "[$name] $message", error)
}

/**
 * Several replicas of one library, each a device with its own folder ([InMemoryLibraryFileSystem]),
 * index and device databases, device and installation id, and its own [Simulation] (generator,
 * background operations, invariants, rebuild oracle), on one virtual clock, with a sync tool
 * ([SimSync]) between their folders. A step is one replica's operation (its [SimGenerator]'s, or an
 * [ExtraPlanner]'s), or one of the tool's.
 *
 * Invariants beyond each replica's own: no sync conflict on a name written once, no replica
 * deleting a journal object or another device's sidecar or marker, device ids never shared, and no
 * replica blocking writes as `JOURNAL_DAMAGED`, since the tool delivers every journal object whole
 * and nothing deletes one. At rest every replica is checked against a rebuild of
 * its own folder, whatever it has not received yet; after a full sync (sometimes, and at the end)
 * the folders must be equal and every replica must show the same library.
 */
internal class SyncSimulation(
    scheduler: TestCoroutineScheduler,
    val config: SimConfig,
    /** Runs after each step, before the invariants across replicas: the harness's own tests break things here. */
    private val afterStep: SyncSimulation.(step: Int) -> Unit = {},
) {
    val world = SimWorld(scheduler)
    val sync = SyncConfig.forSeed(config)
    private val random = Random(config.seed xor ORCHESTRA_SALT)

    /** Every step and what it came to, as a trace file holds it. */
    val trace = ArrayList<String>()
    private val known = HashMap<String, ByteArray>()

    inner class Member(
        val index: Int,
    ) {
        val name = "r$index"
        val fs =
            InMemoryLibraryFileSystem(
                now = world.clock::nowMillis,
                options =
                    SimFsOptions(
                        profile = sync.profiles[index],
                        mtimes = sync.mtimes[index],
                        watchDropRate = config.watchDropRate,
                        watchOverflowRate = config.watchOverflowRate,
                    ),
                latency = SimLatency(Random(salted(1))),
                watchRandom = Random(salted(2)),
            )
        val log = ReplicaLog(name, world.log)
        val replica = SimReplica(world, "sim-$name", fs, ids = SimIds(Random(salted(3))), log = log)
        val sim =
            Simulation(
                world.scheduler,
                config.copy(profile = sync.profiles[index], mtimes = sync.mtimes[index]),
                setup =
                    ReplicaSetup(
                        world,
                        name,
                        Random(salted(5)),
                        fs,
                        replica,
                        known,
                        trace,
                        LABEL_STRIDE * index,
                    ),
            )
        val watched = if (sync.watched[index]) SimWatchedFolder(name, world.clock::nowMillis, ::accounted) else null

        /**
         * Whether the library holds [hash]: a file here has the bytes, or a relink superseded them and
         * the bytes they finally became, the relink chain followed to its end, are in this folder. A
         * relink this replica or another folded counts alike: the import may find the bytes in place,
         * a duplicate, and the sync tool then deliver the bake that replaced them there before the
         * bake's relink, so the original is gone here as on the baking device, and its baked bytes
         * are here instead. Without those bytes here this folder holds the document nowhere.
         */
        internal suspend fun accounted(hash: String): Boolean {
            val held = fs.allFiles().values.mapTo(HashSet(), ::sha256Bytes)
            return hash in held || members.any { member -> finalTargets(member, hash).any { it in held } }
        }

        val extra = ExtraPlanner(Random(salted(6)), sim, name, watched, sync.deleteAfterImport[index], log)

        var removalsSeen = 0

        init {
            watched?.let { folder -> fs.externalSources = folder::bytesFor }
            replica.onOpen = { session, scope -> extra.startImporter(session, scope) }
        }

        private fun salted(n: Int) = config.seed * SALT + index * REPLICA_SALT + n
    }

    val members = List(sync.replicas) { Member(it) }

    /**
     * The documents [hash]'s relink chains in [member]'s index end at: followed from [hash] through
     * every relink to the documents no relink superseded. Empty when [hash] was never relinked, or
     * every chain loops.
     */
    private suspend fun finalTargets(
        member: Member,
        hash: String,
    ): Set<String> {
        val seen = hashSetOf(hash)
        val finals = HashSet<String>()
        var frontier = relinkTargets(member, hash)
        while (frontier.isNotEmpty()) {
            val next = ArrayList<String>()
            for (target in frontier) {
                if (!seen.add(target)) continue
                val onward = relinkTargets(member, target)
                if (onward.isEmpty()) finals += target else next += onward
            }
            frontier = next
        }
        return finals
    }

    /** The documents [member]'s index has [hash] relinked to. */
    private suspend fun relinkTargets(
        member: Member,
        hash: String,
    ): List<String> =
        member.replica.indexDb
            .rows("SELECT canonicalJson FROM metadata_events WHERE operation = 'relink' AND documentId = '$hash'")
            .mapNotNull { (json) -> json?.let { RELINK_TARGET.find(it)?.groupValues?.get(1) } }

    val tool =
        SimSync(
            world,
            members.map { member ->
                val short = (config.seed % SHORT_ID).toString(SHORT_RADIX).uppercase()
                SyncPeer(member.index, member.name, "DEV${member.index}$short", member.fs)
            },
            Random(config.seed xor TOOL_SALT),
            sync.options,
            trace,
        )

    fun run() {
        try {
            runSteps()
        } catch (failure: SimFailure) {
            throw SyncKnownIssues.classify(failure)
        }
    }

    private fun runSteps() {
        try {
            trace += "config: $config"
            trace += "replicas: $sync"
            members.forEach { it.sim.open() }
            for (step in 1..config.steps) {
                world.step = step
                val started = System.nanoTime()
                if (random.chance(SYNC_PERCENT)) {
                    syncStep(step)
                } else {
                    val member = random.pick(members)
                    val extra = if (random.chance(EXTRA_PERCENT)) member.extra.next(member.sim.snapshot()) else null
                    member.sim.step(step, extra ?: member.sim.nextPlanned())
                }
                afterStep(step)
                checkAcross()
                if (step % config.quiesceEvery == 0) quiesce(full = random.chance(FULL_SYNC_PERCENT))
                val took = (System.nanoTime() - started) / NANOS_PER_MILLI
                // Real time, so kept out of the trace a replay compares: where a slow seed spends its time.
                if (took > SLOW_STEP_MILLIS) println("seed ${config.seed}: step $step took ${took}ms of real time")
            }
            quiesce(full = true)
        } finally {
            System.getProperty(Simulation.TRACE_DIR_PROPERTY)?.let { dir ->
                File(dir).apply { mkdirs() }.resolve("seed-${config.seed}.txt").writeText(trace.joinToString("\n"))
            }
            members.forEach { it.replica.dispose() }
            world.dispose()
        }
    }

    // ---- the sync tool's steps ------------------------------------------------------------------

    private fun syncStep(step: Int) {
        val roll = random.nextInt(100)
        val text =
            when {
                roll < 65 -> {
                    val member = random.pick(members)
                    val changed = tool.scan(member.index)
                    "sync: scan ${member.name}, $changed path(s) changed"
                }

                roll < 80 -> {
                    val changed = members.sumOf { tool.scan(it.index) }
                    "sync: scan every device, $changed path(s) changed"
                }

                else -> {
                    val millis = random.nextLong(500, LAND_MILLIS)
                    world.advance(millis)
                    "sync: let deliveries land for ${millis}ms"
                }
            }
        trace += "#$step t=${world.scheduler.currentTime} $text (${tool.pendingDeliveries} delivering)"
        members.forEach { it.sim.checkCheap() }
    }

    // ---- invariants across replicas -------------------------------------------------------------

    private fun checkAcross() {
        tool.violations.firstOrNull()?.let {
            fail(members[0], "the sync tool met two versions of a name written once: $it")
        }
        members.forEach { member ->
            member.watched
                ?.violations
                ?.firstOrNull()
                ?.let { fail(member, "watched folder: $it") }
            checkRemovals(member)
        }
        val owners = HashMap<String, String>()
        members.forEach { member ->
            member.replica.deviceIds.forEach { id ->
                owners.put(id, member.name)?.let { other ->
                    fail(member, "device id $id is used by $other and ${member.name}")
                }
            }
        }
        // The tool delivers every journal object whole, by a rename, and nothing deletes one: a
        // library that finds its journal damaged found a bug.
        members.forEach { member ->
            val block =
                member.replica.session
                    ?.writeBlock
                    ?.value
            if (block == WriteBlock.JOURNAL_DAMAGED) {
                fail(member, "${member.name} blocks writes as JOURNAL_DAMAGED (the log says why)")
            }
        }
    }

    /** A replica deletes no journal object, and no sidecar or device marker but its own. */
    private fun checkRemovals(member: Member) {
        val removals = member.fs.appRemovals
        while (member.removalsSeen < removals.size) {
            val path = removals[member.removalsSeen++]
            val own = path in member.fs.appPublished
            val problem =
                when {
                    SimSync.isJournalObject(path) -> "deleted the journal object '$path'"
                    SimSync.writeOnceName(path) && !own -> "deleted '$path', which another device wrote"
                    else -> null
                }
            problem?.let { fail(member, "${member.name} $it") }
        }
    }

    /** At rest: what a replica wrote under a device's name in the library is under its own. */
    private fun checkOwnNames(member: Member) {
        val ids = member.replica.deviceIds
        member.fs.appPublished.forEach { path ->
            val ownName =
                when {
                    path.startsWith("${NpnPaths.OCR_DIR}/") -> {
                        ids.any { path.contains(it) }
                    }

                    path.startsWith("${NpnPaths.DEVICES_DIR}/") -> {
                        val marker = NpnPaths.fileName(path).startsWith(member.replica.installationId)
                        marker && ids.any { path.startsWith("${NpnPaths.DEVICES_DIR}/$it/") }
                    }

                    else -> {
                        true
                    }
                }
            if (!ownName) {
                fail(member, "${member.name} wrote '$path' under a name not its own (device ids: $ids)")
            }
        }
    }

    // ---- quiescence -----------------------------------------------------------------------------

    /**
     * Let the replicas come to rest and check them. Deliveries under way land first. With [full], the
     * tool then syncs everything and the replicas settle, round after round, until a round leaves
     * every folder equal; then every replica must show the same library. Either way, each replica is
     * checked against a rebuild of its own folder: one that is behind is judged on what it has.
     */
    private fun quiesce(full: Boolean) {
        trace += "   == quiescence at t=${world.scheduler.currentTime}${if (full) ", full sync" else ""}"
        tool.finishInFlight()
        if (full) {
            var rounds = 0
            while (true) {
                val moved = tool.flush()
                members.forEach { it.sim.settle() }
                tool.finishInFlight()
                val differences = tool.differences()
                trace += "   == sync round ${rounds + 1}: $moved path(s) moved, " +
                    "${differences.size} differ after settling" +
                    differences.take(ROUND_LINES).joinToString("") { "\n        $it" }
                if (differences.isEmpty()) break
                if (++rounds >= FULL_SYNC_ROUNDS) {
                    fail(
                        members[0],
                        "the replicas do not converge: after $rounds rounds of a full sync and settling, " +
                            "their folders still " +
                            "differ:\n  " + differences.take(DIFF_LINES).joinToString("\n  "),
                    )
                }
            }
        } else {
            members.forEach { it.sim.settle() }
        }
        members.forEach { member ->
            member.replica.session?.writeBlock?.value?.let {
                fail(member, "${member.name} still blocks writes at rest ($it; the log says why)")
            }
        }
        val views = members.map { it.sim.checkAtRest() }
        members.forEach(::checkOwnNames)
        checkLastCopies()
        if (full) checkConvergence(views)
    }

    /**
     * At rest: the app never removes a document's last copy. Every document some replica's index has
     * a row for, which no replica's journal deleted for good or relinked onto other bytes, is held by
     * a file in some replica's folder (its path, a duplicate, the trash, a conflict copy), or what
     * last took its bytes away explains it. Each folder records every time it lost bytes, when, by
     * whom, and whether no other file there still held them; the latest loss of the last of them in
     * a folder, on any replica, decides, the sync tool's left out, since it only
     * carries out another device's change, whose cause is on that device. Another process explains
     * the loss; the app in its trash (the trash purge, a delete for good) explains it only for a
     * document the journals, all replicas' events together, had in the trash when the loss happened
     * (a restore since, of bytes the purge took, is not the purge's doing); nothing explains the app
     * deleting it elsewhere. A document nothing ever took bytes from is not counted. What is
     * left is the app deleting the last copy of a document it still shows, or would: the live index
     * and a rebuild then both hide it as missing, which neither oracle sees.
     */
    private fun checkLastCopies() {
        val known = HashSet<String>()
        val gone = HashSet<String>()
        // Per document, its doc-trash and doc-restore events over every replica's, by HLC.
        val lifecycle = HashMap<String, MutableSet<Pair<Hlc, String>>>()
        members.forEach { member ->
            world.read("the documents ${member.name} knows") {
                val db = member.replica.indexDb
                db.rows("SELECT docId FROM documents").mapNotNullTo(known) { it[0] }
                db
                    .rows(
                        "SELECT DISTINCT documentId FROM metadata_events WHERE operation IN ('doc-delete', 'relink')",
                    ).mapNotNullTo(gone) { it[0] }
                db
                    .rows(
                        "SELECT documentId, operation, hlcPhysical, hlcCounter, hlcDevice FROM metadata_events " +
                            "WHERE operation IN ('doc-trash', 'doc-restore')",
                    ).forEach { row ->
                        val hlc = Hlc(row[HLC_PHYSICAL]!!.toLong(), row[HLC_COUNTER]!!.toInt(), row[HLC_DEVICE]!!)
                        lifecycle.getOrPut(row[0]!!) { HashSet() } += hlc to row[1]!!
                    }
            }
        }

        // Whether the latest of [hash]'s lifecycle events stamped no later than [atMillis] is a trash.
        fun trashedAt(
            hash: String,
            atMillis: Long,
        ) = lifecycle[hash]
            .orEmpty()
            .filter { it.first.physicalMillis <= atMillis }
            .maxByOrNull { it.first }
            ?.second ==
            "doc-trash"
        val held =
            members.flatMapTo(HashSet()) { member ->
                member.fs
                    .allFiles()
                    .values
                    .map(::sha256Bytes)
            }
        val lost =
            (known - gone - held).sorted().filter { hash ->
                val latest = latestLoss(hash)
                when (latest?.loss) {
                    Loss.APP -> true
                    Loss.APP_TRASH -> !trashedAt(hash, latest.atMillis)
                    Loss.OUTSIDE, Loss.SYNC, null -> false
                }
            }
        if (lost.isNotEmpty()) {
            fail(
                members[0],
                "the app removed the last copy of a document no journal deleted or relinked:\n  " +
                    lost.joinToString("\n  ") { hash ->
                        "${hash.take(ID)}: " + members.joinToString { "${it.name} ${it.fs.losses.of(hash)}" } +
                            ", latest ${latestLoss(hash)}" +
                            ", lifecycle " +
                            lifecycle[hash].orEmpty().sortedBy { it.first }.map { "${it.second}@${it.first}" } +
                            members.joinToString("") { member ->
                                member.fs.lossesAt(hash).joinToString("") { "\n    ${member.name} $it" }
                            }
                    },
            )
        }
    }

    /**
     * The latest time some replica lost the last of the bytes hashing to [hash] in its folder, the
     * sync tool's losses left out; at one instant the app's own loss counts as the latest. A copy the
     * app removed while another file there still held the bytes (a duplicate) took no document away;
     * an import's staging file counts neither way ([InMemoryLibraryFileSystem.losses]).
     */
    private fun latestLoss(hash: String): LossAt? =
        members
            .flatMap { it.fs.losses.recordsOf(hash) }
            .filter { it.loss != Loss.SYNC && it.lastHere }
            .maxWithOrNull(compareBy<LossAt> { it.atMillis }.thenBy { it.loss == Loss.APP })

    /** After a full sync every replica shows the library the first one shows, but for what each alone remembers. */
    private fun checkConvergence(views: List<LibraryView>) {
        val first = members[0]

        fun holds(
            docId: String,
            a: String,
            b: String,
        ) = listOf(a, b).all { path -> first.fs.bytesAt(path)?.let(::sha256Bytes) == docId }
        members.drop(1).forEach { member ->
            val a = views[0]
            val b = views[member.index]
            // A row only one index holds for a document neither shows is what that replica alone saw
            // (a file gone missing, a deletion it watched): device-local, as for the rebuild oracle.
            // While this build writes no doc-undelete, a document deleted for good that an import
            // brought back shows on the importing device for that session only (ARCHITECTURE.md 6.4).
            val returned =
                if (!SyncKnownIssues.DELETED_RETURNS_UNRECORDED) {
                    emptySet()
                } else {
                    world.read("deleted documents") {
                        first.replica.indexDb
                            .rows("SELECT DISTINCT documentId FROM metadata_events WHERE operation = 'doc-delete'")
                            .mapNotNull { it[0]?.take(ID) }
                            .toSet()
                    }
                }
            val differences =
                diff(a, b, hiddenOnlyLive = { _, _ -> true }, bothHold = ::holds).filterNot { line ->
                    "hidden state differs: live null," in line ||
                        ("is live only in" in line && line.removePrefix("doc ").take(ID) in returned)
                }
            if (differences.isNotEmpty()) {
                fail(
                    member,
                    "replicas diverge after a full sync: ${first.name} (\"live\") and " +
                        "${member.name} (\"rebuilt\") show " +
                        "other libraries over equal folders:\n  " + differences.joinToString("\n  "),
                )
            }
        }
    }

    // ---- failing --------------------------------------------------------------------------------

    private fun fail(
        member: Member,
        message: String,
    ): Nothing = member.sim.fail("$message\n\n${summary()}")

    private fun summary(): String =
        buildString {
            appendLine("Replicas: $sync")
            members.forEach { member ->
                appendLine(
                    "  ${member.name}: ${member.replica.installationId}, device ids ${member.replica.deviceIds}, " +
                        "${member.fs.allFiles().size} files",
                )
            }
            appendLine("Sync: ${tool.pendingDeliveries} deliveries under way, ${tool.conflicts.size} conflict copies")
            tool.conflicts.takeLast(SUMMARY_LINES).forEach { appendLine("  $it") }
            if (tool.undeliverable.isNotEmpty()) {
                appendLine("Undeliverable: ${tool.undeliverable.takeLast(SUMMARY_LINES)}")
            }
        }

    private companion object {
        const val SYNC_PERCENT = 22
        const val EXTRA_PERCENT = 25
        const val FULL_SYNC_PERCENT = 50
        const val FULL_SYNC_ROUNDS = 6
        const val LAND_MILLIS = 30_000L
        const val LABEL_STRIDE = 4_000
        const val SALT = 31L
        const val REPLICA_SALT = 1_000_003L
        const val ORCHESTRA_SALT = 0x0C4E_57L
        const val TOOL_SALT = 0x7001L
        const val SHORT_ID = 46_656L
        const val SHORT_RADIX = 36
        const val DIFF_LINES = 30
        const val SUMMARY_LINES = 10
        const val ID = 10
        const val ROUND_LINES = 6
        const val NANOS_PER_MILLI = 1_000_000L
        const val SLOW_STEP_MILLIS = 1_000L

        // The columns of an HLC in a metadata_events query of the last-copies check.
        const val HLC_PHYSICAL = 2
        const val HLC_COUNTER = 3
        const val HLC_DEVICE = 4
        val RELINK_TARGET = Regex("\"newDoc\":\"([0-9a-f]{64})\"")
    }
}
