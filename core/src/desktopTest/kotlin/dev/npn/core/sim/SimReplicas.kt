package dev.npn.core.sim

import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
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
                        journalInPlace = random.chance(JOURNAL_IN_PLACE_SEEDS),
                    ),
            )
        }

        private const val SYNC_SALT = 0x5_7AC_1L
        private const val MIN_REPLICAS = 2
        private const val MAX_REPLICAS = 4
        private const val IN_PLACE_PERCENT = 15
        private const val DUPLICATE_PERCENT = 10
        private const val JOURNAL_IN_PLACE_SEEDS = 15
    }
}

/**
 * The findings of the multi-replica simulation that are written up and not fixed yet, as
 * [KnownIssues] holds the single replica's: a seed that hits one stops there and is counted, so a
 * long run goes on to find what else there is. A fixed one is removed.
 */
object SyncKnownIssues {
    /**
     * Finding 5: the auto-match rules run whenever text is indexed, a rebuild's indexing included
     * (`OcrPipeline`'s `onTextIndexed`), and write what they set into the journal, correcting a
     * value that differs. A rebuilt index, or a device that joins the library, applies today's rules
     * to every document and publishes the result, where the devices that indexed a document before
     * the rule existed never applied it, and a value a user changed by hand is set back: rebuilding
     * the index changes the library. While true, the rebuild oracle passes over a document that
     * differs only by what the rebuild's own session wrote.
     */
    const val REBUILD_RUNS_RULES = true

    /** Each finding by the phrases its failure message holds, all of them. */
    val knownFailures: Map<List<String>, String> =
        mapOf(
            // Finding 6: a device takes a move the sync tool mirrored from another device for a move
            // its user made (ExternalDocumentMoves: the file left the path it was settled at, under
            // the intent it still holds) and journals it as a new storage path, newer than the intent
            // the other device was carrying out. Two devices that disagree on a document's place for
            // a while then move it back and forth for good, a new storagePath each round.
            listOf("do not converge", "\"key\":\"storagePath\"") to
                "finding 6: devices keep moving a document between two paths, " +
                "each taking the other's move for a user's",
        )

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
    /** Runs before an open that failed is tried again: what lets the world mend what made it fail. */
    val beforeReopen: (Throwable) -> Unit = {},
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

/** The block a session's journal coordinator holds on writes, or null; read as a debugger would. */
internal fun LibrarySessionImpl.writeBlock(): MetadataWriteBlocked? =
    BLOCK_FIELD.get(COORDINATOR_FIELD.get(this)) as MetadataWriteBlocked?

private val COORDINATOR_FIELD =
    LibrarySessionImpl::class.java.getDeclaredField("coordinator").apply { isAccessible = true }
private val BLOCK_FIELD = JournalCoordinator::class.java.getDeclaredField("writeBlock").apply { isAccessible = true }

/**
 * Several replicas of one library, each a device with its own folder ([InMemoryLibraryFileSystem]),
 * index and device databases, device and installation id, and its own [Simulation] (generator,
 * background operations, invariants, rebuild oracle), on one virtual clock, with a sync tool
 * ([SimSync]) between their folders. A step is one replica's operation (its [SimGenerator]'s, or an
 * [ExtraPlanner]'s), or one of the tool's.
 *
 * Invariants beyond each replica's own: no sync conflict on a name written once, no replica
 * deleting a journal object or another device's sidecar or marker, device ids never shared, and a
 * replica blocking writes as `JOURNAL_DAMAGED` only after the tool damaged a journal object there
 * or the provider served it a partial listing. At rest every replica is checked against a rebuild of
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
            ).also { it.partialRandom = Random(salted(4)) }
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
                    ) { failure ->
                        reopenAfter(this, failure)
                    },
            )
        val watched = if (sync.watched[index]) SimWatchedFolder(name, world.clock::nowMillis, ::accounted) else null

        private suspend fun accounted(hash: String): Boolean =
            fs.allFiles().values.any { sha256Bytes(it) == hash } ||
                replica.indexDb
                    .rows("SELECT 1 FROM metadata_events WHERE operation = 'relink' AND documentId = '$hash'")
                    .isNotEmpty()

        val extra = ExtraPlanner(Random(salted(6)), sim, name, watched, sync.deleteAfterImport[index], log)

        /** The step at which this replica was last seen at rest with writes allowed. */
        var cleanStep = 0
        var cleanPartials = 0
        var removalsSeen = 0

        init {
            watched?.let { folder -> fs.externalSources = folder::bytesFor }
            replica.onOpen = { session, scope -> extra.startImporter(session, scope) }
        }

        private fun salted(n: Int) = config.seed * SALT + index * REPLICA_SALT + n
    }

    val members = List(sync.replicas) { Member(it) }

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
                // Real time, not virtual: what a slow seed spends its time on, for whoever makes it faster.
                if (took > SLOW_STEP_MILLIS) trace += "   (step $step took ${took}ms of real time)"
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
        members.forEach { member ->
            val block = member.replica.session?.writeBlock() ?: return@forEach
            if (block.block == WriteBlock.JOURNAL_DAMAGED && !explained(member)) {
                fail(
                    member,
                    "${member.name} blocks writes as JOURNAL_DAMAGED, though the sync tool damaged no journal object " +
                        "there and the provider listed nothing partially since step ${member.cleanStep}: " +
                        block.message,
                )
            }
        }
    }

    /**
     * An open refused (a damaged journal over an index with nothing to show refuses the library):
     * when the tool damaged a journal object there, let its deliveries land, which mends it, before
     * the open is tried again. An open the simulation does not explain is tried again as it is.
     */
    private fun reopenAfter(
        member: Member,
        failure: Throwable,
    ) {
        if (failure is MetadataWriteBlocked && explained(member)) {
            trace += "   ${member.name}'s open was refused while the sync tool had a journal object half written; " +
                "letting deliveries land"
            tool.finishInFlight()
        }
    }

    /** Whether something the simulation did explains a damaged journal on [member] since it was last clean. */
    private fun explained(member: Member): Boolean =
        tool.damagedAt[member.index] > member.cleanStep || member.fs.partialListingsServed > member.cleanPartials

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
        members.forEach { it.fs.partialListings = 0 }
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
            member.replica.session?.writeBlock()?.let {
                fail(member, "${member.name} still blocks writes at rest (${it.block}): ${it.message}")
            }
        }
        val views = members.map { it.sim.checkAtRest() }
        members.forEach(::checkOwnNames)
        if (full) checkConvergence(views)
        members.forEach { member ->
            member.cleanStep = world.step
            member.cleanPartials = member.fs.partialListingsServed
        }
    }

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
            val differences =
                diff(a, b, hiddenOnlyLive = { _, _ -> true }, bothHold = ::holds).filterNot {
                    "hidden state differs: live null," in it
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
                        "${member.fs.allFiles().size} files, clean at step ${member.cleanStep}, " +
                        "partial listings ${member.fs.partialListingsServed}, " +
                        "journal damaged at step ${tool.damagedAt[member.index]}",
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
        const val ROUND_LINES = 6
        const val NANOS_PER_MILLI = 1_000_000L
        const val SLOW_STEP_MILLIS = 1_000L
    }
}
