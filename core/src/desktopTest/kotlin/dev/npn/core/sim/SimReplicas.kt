package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.WriteBlock
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalOp
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.io.File
import kotlin.random.Random

/**
 * What a seed decides about the devices and the sync tool between them, beside its [SimConfig]
 * (which the first replica keeps): how many replicas, each one's storage, whether it has a watched
 * inbox folder, and how the tool behaves.
 */
data class SyncConfig(
    val replicas: Int,
    val profiles: List<StorageProfile>,
    val watched: List<Boolean>,
    val deleteAfterImport: List<Boolean>,
    val options: SyncOptions,
    /**
     * The step at which each replica joins, 0 for one there from the start: a late one starts with
     * an empty folder the sync tool fills, as a second device set up later does, and opens the
     * library on it at once.
     */
    val joinsAt: List<Int> = List(replicas) { 0 },
    /**
     * When set, the replicas sync through one cloud remote, each session running the app's own
     * cloud sync ([SimRemote]), and no folder sync tool runs.
     */
    val cloud: CloudOptions? = null,
    /**
     * When set (and [cloud] is not), the folder sync tool is rclone bisync through one remote
     * ([SimBisync]) instead of a Syncthing-like one: the shape cloud sync would take if the app
     * left the whole folder to bisync.
     */
    val bisync: BisyncOptions? = null,
) {
    companion object {
        fun forSeed(config: SimConfig): SyncConfig {
            val random = Random(config.seed xor SYNC_SALT)
            val count = MIN_REPLICAS + random.nextInt(MAX_REPLICAS - MIN_REPLICAS + 1)
            val profiles =
                List(count) { i ->
                    if (i == 0) {
                        config.profile
                    } else {
                        StorageProfile.draw(random)
                    }
                }
            val options =
                SyncOptions(
                    tool = random.pick(SyncTool.entries),
                    duplicatePercent = DUPLICATE_PERCENT,
                )
            val watched = List(count) { random.chance(50) }
            val deleteAfterImport = List(count) { random.nextBoolean() }
            val joinsAt =
                List(count) { i ->
                    if (i > 0 &&
                        random.chance(LATE_JOIN_PERCENT)
                    ) {
                        1 + random.nextInt(maxOf(1, config.steps / 2))
                    } else {
                        0
                    }
                }
            // Drawn last, so every draw above stays what it was for a seed.
            val cloud =
                if (random.chance(CLOUD_PERCENT)) {
                    CloudOptions(
                        caseInsensitive = random.chance(CASE_INSENSITIVE_PERCENT),
                        reportsHashes = random.nextBoolean(),
                        failPercent = if (random.nextBoolean()) CLOUD_FAIL_PERCENT else 0,
                        reportsDropboxHash = random.nextBoolean(),
                    )
                } else {
                    null
                }
            // Drawn after the cloud, so every draw above stays what it was for a seed, and only on
            // request (-Dnpn.sim.bisync=<percent>): bisync loses documents (docs/SIMULATION.md).
            val bisyncPercent = System.getProperty(BISYNC_PROPERTY)?.toIntOrNull() ?: 0
            val bisync =
                if (cloud == null && bisyncPercent > 0 && random.chance(bisyncPercent)) {
                    BisyncOptions(interruptPercent = if (random.nextBoolean()) BISYNC_INTERRUPT_PERCENT else 0)
                } else {
                    null
                }
            return SyncConfig(
                replicas = count,
                profiles = profiles,
                watched = watched,
                deleteAfterImport = deleteAfterImport,
                options = options,
                joinsAt = joinsAt,
                cloud = cloud,
                bisync = bisync,
            )
        }

        private const val SYNC_SALT = 0x5_7AC_1L
        private const val MIN_REPLICAS = 2
        private const val MAX_REPLICAS = 4
        private const val DUPLICATE_PERCENT = 10
        private const val LATE_JOIN_PERCENT = 25
        private const val CLOUD_PERCENT = 30
        private const val CASE_INSENSITIVE_PERCENT = 30
        private const val CLOUD_FAIL_PERCENT = 3
        private const val BISYNC_PROPERTY = "npn.sim.bisync"
        private const val BISYNC_INTERRUPT_PERCENT = 15
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
    /** Its watched inbox folder, or null where the seed gave it none. */
    val watched: SimWatchedFolder?,
    /** Whether the watched folder's importer deletes what it imported. */
    val deleteAfterImport: Boolean,
    /**
     * The folder sync tool scanning this replica's folder now, at any instant of a step, returning
     * how many paths changed; null where no folder sync tool syncs it (a cloud seed).
     */
    val syncScan: (() -> Int)? = null,
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
 * ([SimSync]) between their folders. A step is one replica's operation (its [SimGenerator]'s), or
 * one of the tool's.
 *
 * Invariants beyond each replica's own: no sync conflict on a name written once, no replica
 * deleting a journal object or another device's sidecar or marker, device ids never shared, and no
 * replica blocking writes as `JOURNAL_DAMAGED`, since the tool delivers every journal object whole
 * and nothing deletes one. At rest every replica is checked against a rebuild of
 * its own folder, whatever it has not received yet; after a full sync (sometimes, and at the end)
 * the folders must be equal and every replica must show the same library. A replica that joins
 * late ([SyncConfig.joinsAt]) takes part from the step it joins, its folder empty until the tool
 * fills it.
 */
internal class SyncSimulation(
    scheduler: TestCoroutineScheduler,
    val config: SimConfig,
    /** Runs after each step, before the invariants across replicas: the harness's own tests break things here. */
    private val afterStep: SyncSimulation.(step: Int) -> Unit = {},
    /** The replicas and the tool, as the seed draws them unless a harness test says otherwise. */
    val sync: SyncConfig = SyncConfig.forSeed(config),
    /** Changes the copy of the files each rebuild oracle opens: the harness's own tests plant a difference. */
    private val tamperWithRebuild: (InMemoryLibraryFileSystem) -> Unit = {},
) {
    val world = SimWorld(scheduler)
    private val random = Random(config.seed xor ORCHESTRA_SALT)

    /** The cloud remote the replicas sync through, where the seed has one instead of a sync tool. */
    val remote: SimRemote? = sync.cloud?.let { SimRemote(world.clock, Random(config.seed xor CLOUD_SALT), it) }

    /** A cloud pass started and not yet seen to its end: whose, and in which of its processes. */
    private class CloudPass(
        val member: SyncSimulation.Member,
        val life: SimLife,
        val pass: Deferred<SyncOutcome>,
    ) {
        /** A hard crash killed the process it ran in: it never runs again, nor ends. */
        val killed: Boolean get() = member.replica.life !== life && !pass.isCompleted
    }

    private val cloudPasses = ArrayList<CloudPass>()

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
                        watchDropRate = config.watchDropRate,
                        watchOverflowRate = config.watchOverflowRate,
                        stamps = world.mtimes,
                    ),
                latency = SimLatency(Random(salted(1))),
                watchRandom = Random(salted(2)),
            )
        val replica =
            SimReplica(world, "sim-$name", fs, ids = SimIds(Random(salted(3))), log = ReplicaLog(name, world.log))
        val watched =
            if (sync.watched[index]) SimWatchedFolder(name, world.clock::nowMillis, ::accounted, fs.losses) else null
        val sim =
            Simulation(
                config.copy(profile = sync.profiles[index]),
                ReplicaSetup(
                    world,
                    name,
                    Random(salted(5)),
                    fs,
                    replica,
                    known,
                    trace,
                    LABEL_STRIDE * index,
                    watched,
                    sync.deleteAfterImport[index],
                    syncScan = if (remote == null) ({ tool.scan(index) }) else null,
                ),
                tamperWithRebuild,
            )

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

        /** The step this replica joins at (0: from the start), and whether it has. */
        val joinsAt = sync.joinsAt[index]
        var joined = false
        var removalsSeen = 0

        init {
            watched?.let { folder -> fs.externalSources = folder::bytesFor }
            replica.cloud = remote
            if (remote != null) fs.isDelivery = { it.startsWith(replica.cloudStaging) }
            // A copy in another replica's folder, or on the cloud remote, keeps the library's: the
            // last copy is the library's last.
            fs.heldElsewhere = { hash, path ->
                members.any { it !== this && it.fs.holdsCopy(hash, except = path) } || remote?.holds(hash) == true ||
                    tool.holds(hash)
            }
        }

        private fun salted(n: Int) = config.seed * SALT + index * REPLICA_SALT + n
    }

    val members = List(sync.replicas) { Member(it) }

    /** The replicas that have joined: the ones that take steps and are checked. */
    private val present: List<Member> get() = members.filter { it.joined }

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
            .mapNotNull { (json) ->
                json?.let { (EventDecoderRegistry.decodeStored(it).op as? JournalOp.Relink)?.newDoc }
            }

    private val peers =
        members.map { member ->
            val short = (config.seed % SHORT_ID).toString(SHORT_RADIX).uppercase()
            SyncPeer(member.index, member.name, "DEV${member.index}$short", member.fs)
        }

    /** rclone bisync, where the seed runs it instead of the Syncthing-like tool. */
    private val bisync: SimBisync? =
        sync.bisync?.let {
            SimBisync(
                world,
                peers,
                Random(config.seed xor TOOL_SALT),
                it,
                trace,
                present =
                    members.map { m ->
                        m.joinsAt ==
                            0
                    },
            )
        }

    val tool: FolderSyncTool =
        bisync ?: SimSync(
            world,
            peers,
            Random(config.seed xor TOOL_SALT),
            sync.options,
            trace,
            present = members.map { it.joinsAt == 0 },
        )

    fun run() {
        try {
            trace += "config: $config"
            trace += "replicas: $sync"
            members.filter { it.joinsAt == 0 }.forEach { open(it) }
            for (step in 1..config.steps) {
                world.step = step
                val started = System.nanoTime()
                members.filter { !it.joined && it.joinsAt == step }.forEach { join(it, step) }
                if (random.chance(SYNC_PERCENT)) {
                    syncStep(step)
                } else {
                    random.pick(present).sim.step(step)
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
            // With -Dnpn.sim.traceDir=<dir>, every run leaves its whole trace there, failing or not.
            System.getProperty(TRACE_DIR_PROPERTY)?.let { dir ->
                File(dir).apply { mkdirs() }.resolve("seed-${config.seed}.txt").writeText(trace.joinToString("\n"))
            }
            members.forEach { it.replica.dispose() }
            world.dispose()
        }
    }

    private fun open(member: Member) {
        member.sim.open()
        member.joined = true
        // A device connects to the library's cloud folder as a user sets one up: a first pass, before
        // anything is added on it, joins it to the library there (or makes the empty folder its own).
        remote?.let { cloud ->
            cloud.faultsOn = false
            try {
                trace += "   cloud: ${member.name} joins the library in the cloud: ${runPass(member)}"
            } finally {
                cloud.faultsOn = true
            }
        }
    }

    /** [member] is given the folder, empty, at [step], and opens the library on it while the tool fills it. */
    private fun join(
        member: Member,
        step: Int,
    ) {
        if (remote == null) tool.join(member.index)
        trace += "#$step t=${world.scheduler.currentTime} ${member.name} joins with an empty folder " +
            "(${tool.pendingDeliveries} delivering)"
        open(member)
    }

    // ---- the sync tool's steps ------------------------------------------------------------------

    private fun syncStep(step: Int) {
        if (remote != null) return cloudStep(step)
        val roll = random.nextInt(100)
        val text =
            when {
                roll < 65 -> {
                    val member = random.pick(present)
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
        present.forEach { it.sim.checkCheap() }
    }

    // ---- cloud sync -----------------------------------------------------------------------------

    /**
     * A step of the cloud: one replica's pass run to its end, or started to run beside whatever
     * comes next (another replica's pass, its own session's work), or time for passes under way.
     */
    private fun cloudStep(step: Int) {
        val roll = random.nextInt(100)
        val text =
            when {
                roll < 60 -> {
                    val member = random.pick(present)
                    "cloud: ${member.name} passes: ${runPass(member)}"
                }

                roll < 85 -> {
                    val member = random.pick(present)
                    startPass(member)
                    "cloud: ${member.name} starts a pass in the background"
                }

                else -> {
                    val millis = random.nextLong(500, LAND_MILLIS)
                    world.advance(millis)
                    "cloud: let passes run for ${millis}ms"
                }
            }
        cloudPasses.removeAll { running ->
            when {
                running.pass.isCompleted -> {
                    trace += "   cloud: ${running.member.name}'s background pass ${outcomeText(running.pass)}"
                    true
                }

                running.killed -> {
                    trace += "   cloud: ${running.member.name}'s background pass died with its process"
                    true
                }

                else -> {
                    false
                }
            }
        }
        trace += "#$step t=${world.scheduler.currentTime} $text (${cloudPasses.size} passes running)"
        present.forEach { it.sim.checkCheap() }
    }

    private fun startPass(member: Member): Deferred<SyncOutcome>? {
        val session = member.replica.session ?: return null
        return member.replica
            .launch { session.sync.syncNow() }
            .also { cloudPasses += CloudPass(member, member.replica.life, it) }
    }

    private fun runPass(member: Member): String {
        val pass = startPass(member) ?: return "no session open"
        world.drive(pass, "cloud pass of ${member.name}")
        cloudPasses.removeAll { it.pass === pass }
        return outcomeText(pass)
    }

    /** Let every pass under way end; one a hard crash killed never does. */
    private fun finishCloudPasses() {
        cloudPasses.toList().forEach { running ->
            if (running.killed) {
                trace += "   cloud: ${running.member.name}'s background pass died with its process"
            } else {
                world.drive(running.pass, "cloud pass of ${running.member.name}")
                trace += "   cloud: ${running.member.name}'s background pass ${outcomeText(running.pass)}"
            }
        }
        cloudPasses.clear()
    }

    /** One pass on every replica in turn; whether none of them changed anything. */
    private fun cloudRound(round: Int): Boolean {
        var quiet = true
        present.forEach { member ->
            val pass = startPass(member) ?: return@forEach
            world.drive(pass, "cloud pass of ${member.name}")
            cloudPasses.clear()
            trace += "   == cloud round $round, ${member.name}: ${outcomeText(pass)}"
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            val outcome = pass.getCompletionExceptionOrNull()?.let { null } ?: pass.getCompleted()
            if (outcome !is SyncOutcome.Completed || outcome.report.changedAnything) quiet = false
        }
        return quiet
    }

    /**
     * A full sync through the cloud: every replica passes in turn and settles, round after round,
     * until a round after the replicas settled in which no pass changed anything. Settling rescans,
     * and a rescan finds what no pass has carried yet (a file another process wrote where no watcher
     * reports it, which no step rescanned since), so only a quiet round after it ends the sync; the
     * checks at rest then read the indexes that settle left, which the quiet round changed nothing
     * in. A remote that fails no call then (faults are off while the simulation comes to rest)
     * leaves nothing to keep them apart.
     */
    private fun cloudFlush() {
        var round = 0
        var busy = 0
        var settled = false
        while (true) {
            val quiet = cloudRound(++round)
            if (quiet && settled) break
            present.forEach { it.sim.settle() }
            settled = true
            if (!quiet && ++busy >= CLOUD_SYNC_ROUNDS) {
                fail(
                    members[0],
                    "the replicas do not converge through the cloud: after $busy rounds, a pass still " +
                        "changes something (the trace has each round's passes)",
                )
            }
        }
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
                    SimNames.isJournalObject(path) -> "deleted the journal object '$path'"
                    SimNames.isWriteOnce(path) && !own -> "deleted '$path', which another device wrote"
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

                    path.startsWith("${NpnPaths.ACKS_DIR}/") -> {
                        ids.any { path.startsWith("${NpnPaths.ACKS_DIR}/$it/") }
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
     * Let the replicas come to rest and check them. Deliveries under way land first, and then a day
     * passes ([passRestoreWindow]). With [full], the tool then syncs everything and the replicas
     * settle, round after round, until a round leaves every folder equal; then every replica must
     * show the same library. Either way, each replica is checked against a rebuild of its own folder:
     * one that is behind is judged on what it has.
     */
    private fun quiesce(full: Boolean) {
        trace += "   == quiescence at t=${world.scheduler.currentTime}${if (full) ", full sync" else ""}"
        val cloud = remote
        if (cloud != null) {
            finishCloudPasses()
            cloud.faultsOn = false
            try {
                passRestoreWindow()
                if (full) cloudFlush() else present.forEach { it.sim.settle() }
                checkAtRest(full)
            } finally {
                cloud.faultsOn = true
            }
            return
        }
        tool.finishInFlight()
        passRestoreWindow()
        if (full) {
            var rounds = 0
            while (true) {
                val moved = tool.flush()
                present.forEach { it.sim.settle() }
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
            present.forEach { it.sim.settle() }
        }
        checkAtRest(full)
    }

    private fun checkAtRest(full: Boolean) {
        present.forEach { member ->
            member.replica.session?.writeBlock?.value?.let {
                fail(member, "${member.name} still blocks writes at rest ($it; the log says why)")
            }
        }
        val views = present.associateWith { it.sim.checkAtRest() }
        present.forEach(::checkOwnNames)
        checkLastCopies()
        if (full) {
            keepGatesWaitingOnRecords(world, members.map { it.fs }, present.map { it.name to it.replica })
                ?.let { (name, why) -> fail(present.first { it.name == name }, why) }
            checkConvergence(views)
            verifiedMissingButHeld(present.map { it.name to it.sim }, present.map { it.fs })?.let { (name, why) ->
                fail(present.first { it.name == name }, why)
            }
            afterFullSync(world, views, { it.replica }) { it.name }?.let { (member, why) -> fail(member, why) }
        }
        // What the rebuild oracles logged: after the run's last rest no step checks it.
        present.forEach { it.sim.checkLog() }
    }

    /**
     * Let [JournalCoordinator.RESTORE_SETTLE_MS] pass with every replica let through, its changes
     * synced once and its folder rescanned, so every trash the replicas know of, and every return of a
     * trashed document's own bytes their rescans found, is older than that by the rescans of settling
     * (whose own wait is the margin): each records the return of a trashed document's own bytes to
     * its path as a restore, as the app does once that window has passed
     * (`JournalCoordinator.recordRestores`), and the live index, a rebuild and the other replicas
     * agree on it. The day runs everything time brings: the
     * 15-minute tick, the queues' backoffs, and a trash purge after any rescan the watch starts. A
     * purge deletes files in the trash and records nothing, so a rebuild's own (its first rescan's)
     * changes nothing it is compared on.
     */
    private fun passRestoreWindow() {
        present.forEach { it.sim.letThrough() }
        // The sync tool runs through the day as well: what each device changed reaches the others
        // before the window passes. Frozen for the day, it would leave another device's trash
        // unannounced, and the file still at its path would pass the window as one come back.
        if (remote != null) {
            cloudRound(0)
        } else {
            present.forEach { tool.scan(it.index) }
            tool.finishInFlight()
        }
        // Each replica looks at its folder as the day begins, as the app does on Android at every
        // resume and a watch does when files land. A file a watcher dropped the event of is rescanned
        // by nothing else, and a trashed document's own bytes found back there first by the rescan after
        // the day started the restore window then: it had not passed by the checks, and the file was
        // neither a live document nor a known duplicate (seed 4101227 at 44dee63c).
        present.forEach { it.sim.rescanAtRest("rescan as the day at rest begins") }
        world.advance(JournalCoordinator.RESTORE_SETTLE_MS)
        trace += "   == past the restore settle window at t=${world.scheduler.currentTime}"
    }

    /**
     * At rest: the app never removes a document's last copy. Every document some replica's index has
     * a row for, which no replica's journal deleted for good or relinked onto other bytes, and which
     * no replica's folder holds a file of (at its path, a duplicate, the trash, a conflict copy), was
     * never taken from a folder by the app while no file in any replica's folder held it. Each folder
     * records every time it lost bytes, when, by whom, and whether no other file there, nor in another
     * replica's folder, still held them ([LossAt.lastAnywhere]): the last copy is the library's, so a
     * copy another replica holds, which the sync tool will bring back, keeps it. An import's staging
     * file and a bake's temporary file are none of the library's copies ([Losses.isNoCopy]). The app
     * taking the library's last copy is a finding whatever happened to the bytes before or after, but
     * in its trash (the trash purge, a delete for good) for a document the journals, all replicas'
     * events together, had in the trash when the loss happened (a restore since, of bytes the purge
     * took, is not the purge's doing), or had restored after a purge already took a copy of it (the
     * user restored past the retention), or had restored before another process took the restored
     * file (the user deleted what they had restored): a device that had not seen the restore yet
     * purges its copy by the retention too. The sync tool only carries out another device's change, whose
     * cause is on that device, and another process is no finding of the app's. What this finds is the
     * app deleting the last copy of a document it still shows, or would: the live index and a rebuild
     * then both hide it as missing, which neither oracle sees.
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

        // The latest of [hash]'s lifecycle events stamped no later than [atMillis].
        fun latestAt(
            hash: String,
            atMillis: Long,
        ) = lifecycle[hash].orEmpty().filter { it.first.physicalMillis <= atMillis }.maxByOrNull { it.first }

        // Whether the latest of [hash]'s lifecycle events stamped no later than [atMillis] is a trash.
        fun trashedAt(
            hash: String,
            atMillis: Long,
        ) = latestAt(hash, atMillis)?.second == "doc-trash"

        // Whether the latest of [hash]'s lifecycle events stamped no later than [atMillis] is a restore
        // whose bytes a device that had not seen it yet purges by the retention, the retention's doing:
        // one made after a purge had already taken a copy of it from the trash, while it was trashed (a
        // purge runs only once the retention is past, so the user restored what the retention had let
        // go), or one whose file another process took since from a document's path (the user deleted
        // what they had restored, before it synced; synced, the sync tool would have carried that
        // delete to every device, or taken their trash copies with the restore's move out of the
        // trash: seed 4100040 at 01d7151f).
        fun restoredPastTheTrash(
            hash: String,
            atMillis: Long,
        ): Boolean {
            val latest = latestAt(hash, atMillis)?.takeIf { it.second == "doc-restore" } ?: return false
            val restored = latest.first.physicalMillis
            return members.any { member ->
                member.fs.losses.recordsOf(hash).any { loss ->
                    when (loss.loss) {
                        Loss.APP_TRASH -> loss.atMillis < restored && trashedAt(hash, loss.atMillis)
                        Loss.OUTSIDE -> loss.atMillis in restored..atMillis && NpnPaths.isDocumentPath(loss.path)
                        Loss.APP, Loss.SYNC -> false
                    }
                }
            }
        }
        val held =
            members.flatMapTo(HashSet()) { member ->
                member.fs
                    .allFiles()
                    .values
                    .map(::sha256Bytes)
            }

        // The app's taking of the library's last copy of a document's bytes that nothing explains.
        fun appLosses(hash: String): List<LossAt> =
            members
                .flatMap { it.fs.losses.recordsOf(hash) }
                .filter { it.lastAnywhere }
                .filter { loss ->
                    when (loss.loss) {
                        Loss.APP -> true
                        Loss.APP_TRASH -> !trashedAt(hash, loss.atMillis) && !restoredPastTheTrash(hash, loss.atMillis)
                        Loss.OUTSIDE, Loss.SYNC -> false
                    }
                }
        val lost =
            (known - gone - held).sorted().filter { !tool.holds(it) && appLosses(it).isNotEmpty() }
        // bisync's own losses (bytes it replaced or deleted that its listing did not show, or that a
        // resync overwrote) are no change it carried for another device: the tool lost them.
        bisync?.let { tool ->
            val clobbered = (known - gone - held).sorted().filter { !tool.holds(it) && it in tool.clobbered.keys }
            if (clobbered.isNotEmpty()) {
                fail(
                    members[0],
                    "bisync removed the last copy of a document no journal deleted or relinked:\n  " +
                        clobbered.joinToString("\n  ") { hash ->
                            "${hash.take(ID)}: ${tool.clobbered[hash]}; " +
                                members.joinToString { "${it.name} ${it.fs.losses.of(hash)}" } +
                                members.joinToString("") { member ->
                                    member.fs.lossesAt(hash).joinToString("") { "\n    ${member.name} $it" }
                                }
                        },
                )
            }
        }
        if (lost.isNotEmpty()) {
            fail(
                members[0],
                "the app removed the last copy of a document no journal deleted or relinked:\n  " +
                    lost.joinToString("\n  ") { hash ->
                        "${hash.take(ID)}: " + members.joinToString { "${it.name} ${it.fs.losses.of(hash)}" } +
                            ", by the app ${appLosses(hash)}" +
                            ", lifecycle " +
                            lifecycle[hash].orEmpty().sortedBy { it.first }.map { "${it.second}@${it.first}" } +
                            members.joinToString("") { member ->
                                member.fs.lossesAt(hash).joinToString("") { "\n    ${member.name} $it" }
                            }
                    },
            )
        }
    }

    /** After a full sync every replica shows the library the first one shows, but for what each alone remembers. */
    private fun checkConvergence(views: Map<Member, LibraryView>) {
        val first = members[0]

        fun holds(
            docId: String,
            a: String,
            b: String,
        ) = listOf(a, b).all { path -> first.fs.bytesAt(path)?.let(::sha256Bytes) == docId }
        views.keys.filter { it != first }.forEach { member ->
            val a = views.getValue(first)
            val b = views.getValue(member)
            // A row only one index holds for a document neither shows is what that replica alone saw
            // (a file gone missing, a deletion it watched): device-local, as for the rebuild oracle.
            // While this build writes no doc-undelete, a document deleted for good that an import
            // brought back shows on the importing device for that session only (ARCHITECTURE.md 6.4).
            val returned =
                if (!KnownIssues.DELETED_RETURNS_UNRECORDED) {
                    emptySet()
                } else {
                    world.read("deleted documents") {
                        first.replica.indexDb
                            .rows("SELECT DISTINCT documentId FROM metadata_events WHERE operation = 'doc-delete'")
                            .mapNotNull { it[0]?.take(ID) }
                            .toSet()
                    }
                }
            val pathsOnly = remote != null && KnownIssues.CLOUD_NAMES_MAY_DIFFER
            if (pathsOnly) {
                diff(a, b, hiddenOnlyLive = { _, _ -> true }, bothHold = ::holds)
                    .filter { it.endsWith(" differs:") || " differs:\n" in it }
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        trace +=
                            "   == cloud: names differ between ${first.name} and ${member.name} (known):\n  " +
                            it.joinToString("\n  ")
                    }
            }
            val differences =
                diff(
                    a.withoutPaths(pathsOnly),
                    b.withoutPaths(pathsOnly),
                    hiddenOnlyLive = { _, _ -> true },
                    bothHold = ::holds,
                ).filterNot { line ->
                    "hidden state differs: live null," in line ||
                        ("is live only in" in line && line.removePrefix("doc ").take(ID) in returned)
                }
            if (differences.isNotEmpty()) {
                fail(
                    member,
                    "replicas diverge after a full sync: ${first.name} (\"live\") and " +
                        "${member.name} (\"rebuilt\") show " +
                        "other libraries over equal folders:\n  " + differences.joinToString("\n  ") +
                        // The report ends with the failing replica's folder and rows; the other side's
                        // are half of the story.
                        "\n\n${first.name}'s side:\n" + first.sim.folderAndRows(),
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
                        "${member.fs.allFiles().size} files" +
                        if (member.joinsAt > 0) ", joined at step ${member.joinsAt}" else "",
                )
            }
            remote?.let { cloud ->
                appendLine("Cloud: ${cloud.options}, ${cloudPasses.size} passes under way, holding:")
                cloud.describe().takeLast(SUMMARY_LINES * 3).forEach { appendLine("  $it") }
            }
            appendLine("Sync: ${tool.pendingDeliveries} deliveries under way, ${tool.conflicts.size} conflict copies")
            tool.conflicts.takeLast(SUMMARY_LINES).forEach { appendLine("  $it") }
            if (tool.undeliverable.isNotEmpty()) {
                appendLine("Undeliverable: ${tool.undeliverable.takeLast(SUMMARY_LINES)}")
            }
        }

    private companion object {
        /** The system property naming a directory to write each run's trace to. */
        const val TRACE_DIR_PROPERTY = "npn.sim.traceDir"
        const val SYNC_PERCENT = 22
        const val FULL_SYNC_PERCENT = 50
        const val FULL_SYNC_ROUNDS = 6
        const val CLOUD_SYNC_ROUNDS = 8
        const val CLOUD_SALT = 0xC10D_5L
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
    }
}

/** What a cloud pass that ended came to, for the trace. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun outcomeText(pass: Deferred<SyncOutcome>): String {
    pass.getCompletionExceptionOrNull()?.let { return "ended by ${it::class.simpleName}: ${it.message}" }
    return when (val outcome = pass.getCompleted()) {
        is SyncOutcome.Completed -> {
            val report = outcome.report
            "down ${report.downloadedDocuments}+${report.downloadedObjects}, " +
                "up ${report.uploadedDocuments}+${report.uploadedObjects}, moved ${report.movedRemote}, " +
                "deleted ${report.deletedRemote}, applied ${report.appliedLocally}" +
                report.conflicts.joinToString(
                    "",
                ) { "\n      conflict ${it.kind} ${it.remotePath} ${it.otherPath.orEmpty()}" } +
                report.errors.joinToString("") { "\n      error ${it.path}: ${it.message}" }
        }

        is SyncOutcome.Failed -> {
            "failed: ${outcome.reason} ${outcome.detail.orEmpty()}"
        }

        else -> {
            outcome.toString()
        }
    }
}
