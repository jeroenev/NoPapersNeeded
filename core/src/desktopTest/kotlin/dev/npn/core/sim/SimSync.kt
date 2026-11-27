package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** The file sync tool a seed runs, by the names it gives its temporary files and conflict copies. */
enum class SyncTool {
    /** `.syncthing.<name>.tmp`, then `<base>.sync-conflict-<date>-<time>-<device>.<ext>`. */
    SYNCTHING,

    /** `.<name>.~<hex>`, then `<base> (conflicted copy <date> <time>).<ext>`. */
    NEXTCLOUD,

    /** `<name>.!sync`, and Syncthing-style conflict copies. */
    RESILIO,
}

/** How a seed's sync tool behaves. Everything here is drawn from the seed ([SyncConfig.forSeed]). */
data class SyncOptions(
    val tool: SyncTool,
    /** The share of deliveries made a second time, as a tool that re-pulls a file it already has does. */
    val duplicatePercent: Int,
)

/** What the simulation asks of the tool between the replicas' folders: [SimSync], or [SimBisync]. */
internal interface FolderSyncTool {
    /** Conflict copies made, for the trace and the summary. */
    val conflicts: List<String>

    /** Conflicts on names that must be written once: never expected. */
    val violations: List<String>

    /** Deliveries the tool could not make (a file where a folder must be), for the report. */
    val undeliverable: List<String>

    val pendingDeliveries: Int

    /** Look at what changed on [peer] and carry it to the others: over virtual time, or at once when [immediate]. */
    fun scan(
        peer: Int,
        immediate: Boolean = false,
    ): Int

    /** Share the folder with [peer], which had none, from now on. */
    fun join(peer: Int)

    /** Let everything under way land. */
    fun finishInFlight()

    /** Sync everything at once, round after round, until a round changes nothing. */
    fun flush(): Int

    /** The paths whose files differ between the devices sharing the folder. */
    fun differences(): List<String>

    /** Whether the tool itself keeps a copy of [hash]'s bytes (a remote of its own), which the library still has. */
    fun holds(hash: String): Boolean = false
}

/** One version of one path, as the tool's index holds it: its bytes (null for a deletion), mtime and version vector. */
internal class SyncVersion(
    val bytes: ByteArray?,
    val mtime: Long,
    val vector: Map<Int, Long>,
    /** The device that made it. */
    val origin: Int,
) {
    fun dominates(other: Map<Int, Long>): Boolean = other.all { (peer, n) -> (vector[peer] ?: 0) >= n }
}

/** One device's folder, as the sync tool sees it. */
internal class SyncPeer(
    val index: Int,
    val name: String,
    /** The short device id the tool puts in a conflict copy's name. */
    val deviceShort: String,
    val fs: InMemoryLibraryFileSystem,
)

/**
 * A file sync tool between the replicas' folders, as Syncthing and its kind behave: a [scan] of one
 * device records what changed there since its last scan as new versions (a version vector per path)
 * and sends each to every other device, where it lands after a seeded delay, so deliveries arrive out
 * of order, now and then twice, under the tool's temporary name first and then renamed into place,
 * so every file lands whole, as every tool of this kind (Syncthing, Nextcloud, Dropbox, Resilio)
 * delivers one. Journal objects, OCR sidecars, device markers, staging files and PDFs travel alike;
 * a deletion travels as a version too. A delivery a device's own unscanned change, or
 * a version it has that does not descend from it, contends with is a conflict: identical bytes
 * merge, a modification beats a deletion, and otherwise the newer mtime (then the higher device id)
 * keeps the name while the other is kept as a conflict copy, which then syncs like any new file.
 * Modification times travel with the bytes. A device the folder is shared with later ([join]) is
 * sent what the others hold, over the same seeded delays, as the tool fills a folder it was just
 * given; until then it is scanned by nothing and sent nothing.
 *
 * A conflict on a write-once name under `.npn/` is a violation of the library's naming
 * ([violations]): two writers published different bytes under one name.
 */
internal class SimSync(
    private val world: SimWorld,
    val peers: List<SyncPeer>,
    private val random: Random,
    val options: SyncOptions,
    private val trace: MutableList<String>,
    /** Which devices share the folder from the start; the others [join] later. */
    present: List<Boolean> = peers.map { true },
) : FolderSyncTool {
    /** What the tool last recorded at each path of each device: its local index. */
    private val known = peers.map { HashMap<String, SyncVersion>() }

    /** Which devices share the folder now. */
    private val present = present.toBooleanArray()

    /** The indices of the devices that share the folder now. */
    private val sharing: List<Int> get() = peers.indices.filter { present[it] }

    /** Version counters, one per device. */
    private val counters = LongArray(peers.size)

    /** Paths a delivery is transferring to each device now. */
    private val transferring = peers.map { HashSet<String>() }

    private val inFlight = ArrayList<Pair<String, Deferred<Unit>>>()

    override val conflicts = ArrayList<String>()

    override val violations = ArrayList<String>()

    override val undeliverable = ArrayList<String>()

    override val pendingDeliveries: Int get() = inFlight.count { !it.second.isCompleted }

    // ---- scanning -------------------------------------------------------------------------------

    /**
     * Record what changed on [peer] since its last scan and send it to the other devices: at once
     * when [immediate] (each delivery atomic, no delay), else as seeded deliveries over virtual time.
     * Returns how many paths changed.
     */
    override fun scan(
        peer: Int,
        immediate: Boolean,
    ): Int {
        if (!present[peer]) return 0
        val fs = peers[peer].fs
        val index = known[peer]
        val files = fs.allFiles().filterKeys(::synced)
        val changes = ArrayList<Pair<String, SyncVersion>>()
        files.forEach { (path, bytes) ->
            val recorded = index[path]
            if (recorded?.bytes == null || !recorded.bytes.contentEquals(bytes)) {
                changes +=
                    path to
                    SyncVersion(
                        bytes.copyOf(),
                        fs.mtimeAt(path) ?: world.clock.nowMillis(),
                        bump(recorded?.vector, peer),
                        peer,
                    )
            }
        }
        index.forEach { (path, recorded) ->
            if (recorded.bytes != null && path !in files) {
                changes += path to SyncVersion(null, world.clock.nowMillis(), bump(recorded.vector, peer), peer)
            }
        }
        changes.sortBy { it.first }
        changes.forEach { (path, version) ->
            index[path] = version
            send(peer, path, version, immediate)
        }
        return changes.size
    }

    /** Send [version] of [path], as [from] holds it, to every other device. */
    private fun send(
        from: Int,
        path: String,
        version: SyncVersion,
        immediate: Boolean,
    ) {
        sharing.filter { it != from }.forEach { to ->
            if (immediate) {
                apply(to, path, version, immediate = true)
            } else {
                schedule(to, path, version, duplicate = false)
                if (random.chance(options.duplicatePercent)) schedule(to, path, version, duplicate = true)
            }
        }
    }

    private fun schedule(
        to: Int,
        path: String,
        version: SyncVersion,
        duplicate: Boolean,
    ) {
        val roll = random.nextInt(100)
        val wait =
            when {
                roll < 70 -> random.nextLong(100, 3_000)
                roll < 95 -> random.nextLong(3_000, 60_000)
                else -> random.nextLong(60_000, 10L * 60_000)
            }
        val transferMillis = random.nextLong(0, TRANSFER_MILLIS)
        val what = "${if (duplicate) "again " else ""}${version.describe()} '$path' to ${peers[to].name}"
        inFlight +=
            what to
            world.launch {
                delay(wait)
                deliver(to, path, version, transferMillis, duplicate)
            }
    }

    // ---- delivering -----------------------------------------------------------------------------

    private suspend fun deliver(
        to: Int,
        path: String,
        version: SyncVersion,
        transferMillis: Long,
        duplicate: Boolean,
    ) {
        val fs = peers[to].fs
        // One transfer of a path to a device at a time: they share its temporary name.
        while (path in transferring[to]) delay(BUSY_POLL_MILLIS)
        transferring[to] += path
        try {
            transfer(to, path, version, transferMillis, duplicate, fs)
        } finally {
            transferring[to] -= path
        }
    }

    /** Write [version]'s bytes under the tool's temporary name, and [transferMillis] later put them in place. */
    private suspend fun transfer(
        to: Int,
        path: String,
        version: SyncVersion,
        transferMillis: Long,
        duplicate: Boolean,
        fs: InMemoryLibraryFileSystem,
    ) {
        val bytes = version.bytes
        if (bytes == null) {
            apply(to, path, version, immediate = false, duplicate = duplicate)
            return
        }
        val temp = tempName(path)
        if (!placeable(fs, temp)) {
            undeliverable += "${peers[to].name}: '$path'"
            return
        }
        fs.bySyncTool { fs.externalWriteAtomic(temp, bytes, version.mtime) }
        if (transferMillis > 0) delay(transferMillis)
        // The temporary file is the tool's; if it went meanwhile, the next pull starts over.
        if (fs.bytesAt(temp) == null) return
        apply(to, path, version, immediate = false, duplicate = duplicate, temp = temp)
        fs.bySyncTool { fs.externalDelete(temp) }
    }

    /**
     * Bring [version] of [path] to device [to], resolving it against what that device has: its
     * recorded version, and its file as it is now (changed since the last scan, or not). [temp] holds
     * the bytes when they were transferred to a temporary file first.
     */
    private fun apply(
        to: Int,
        path: String,
        version: SyncVersion,
        immediate: Boolean,
        duplicate: Boolean = false,
        temp: String? = null,
    ) {
        val fs = peers[to].fs
        if (fs.isDirectory(path) || !placeable(fs, path)) {
            undeliverable += "${peers[to].name}: '$path'"
            return
        }
        val recorded = known[to][path]
        val current = fs.bytesAt(path)
        // Before it replaces a file the tool checks it was not changed since its last scan; a file
        // gone since is no change it can see (its deletion is announced at the next scan).
        val localChange = current != null && !same(current, recorded?.bytes)
        if (recorded != null && !localChange && recorded.dominates(version.vector)) {
            // It has this version or a later one. A duplicate puts the same bytes there again.
            if (duplicate && current != null &&
                same(current, version.bytes)
            ) {
                put(fs, path, version, temp)
            }
            return
        }
        val concurrent = localChange || (recorded != null && !version.dominates(recorded.vector))
        if (!concurrent) {
            put(fs, path, version, temp)
            known[to][path] = version
            return
        }
        val localVector = if (localChange) bump(recorded?.vector, to) else recorded!!.vector
        val joined = join(localVector, version.vector)
        when {
            same(current, version.bytes) -> {
                known[to][path] = SyncVersion(current?.copyOf(), version.mtime, joined, version.origin)
            }

            version.bytes == null -> {
                // A modification beats a deletion: the file stays, and goes back to the others.
                known[to][path] =
                    SyncVersion(current!!.copyOf(), fs.mtimeAt(path) ?: version.mtime, bump(joined, to), to)
                send(to, path, known[to].getValue(path), immediate)
            }

            current == null -> {
                // What it recorded here is not on disk any more: the delivery puts the file there,
                // and a device holding the recorded version meets this one as a conflict of its own.
                put(fs, path, version, temp)
                // A version of its own, so no other content ever has its vector.
                known[to][path] = SyncVersion(version.bytes.copyOf(), version.mtime, bump(joined, to), to)
            }

            else -> {
                conflict(to, path, version, current, joined, immediate, temp)
            }
        }
    }

    /** Both sides changed [path]: the newer keeps the name, the other becomes a conflict copy beside it. */
    private fun conflict(
        to: Int,
        path: String,
        version: SyncVersion,
        current: ByteArray,
        joined: Map<Int, Long>,
        immediate: Boolean,
        temp: String?,
    ) {
        val fs = peers[to].fs
        val remote = version.origin
        val localMtime = fs.mtimeAt(path) ?: 0L
        val remoteWins =
            version.mtime > localMtime ||
                (version.mtime == localMtime && peers[remote].deviceShort > peers[to].deviceShort)
        val loser = if (remoteWins) peers[to] else peers[remote]
        val copy = conflictName(path, loser.deviceShort)
        if (SimNames.isWriteOnce(path)) {
            violations += "step ${world.step}: conflicting versions of the write-once '$path' " +
                "(${sha256Bytes(current).take(ID)} on ${peers[to].name}, " +
                "${sha256Bytes(version.bytes!!).take(ID)} from ${peers[remote].name})"
        }
        // A name the app made for one file's bytes carries their hash; other bytes under it later (a
        // bake in place) are that document's, not a second invention of the name.
        val token = SimNames.nameToken(path)
        val bothInvented =
            listOf(fs to current, peers[remote].fs to version.bytes!!).all { (at, bytes) ->
                appPlaced(at, path, bytes) && (token == null || sha256Bytes(bytes).startsWith(token))
            }
        if (SimNames.isInvented(path) && bothInvented) {
            violations += "step ${world.step}: two devices gave different files the name '$path' the app invented " +
                "(${sha256Bytes(current).take(ID)} on ${peers[to].name}, " +
                "${sha256Bytes(version.bytes).take(ID)} from ${peers[remote].name}): " +
                "${fs.historyOf(path).joinToString("; ")} | ${peers[remote].fs.historyOf(path).joinToString("; ")}"
        }
        if (remoteWins) {
            fs.bySyncTool { fs.externalMove(path, copy) }
            put(fs, path, version, temp)
            known[to][path] = SyncVersion(version.bytes.copyOf(), version.mtime, bump(joined, to), to)
        } else {
            if (temp !=
                null
            ) {
                fs.bySyncTool { fs.externalMove(temp, copy) }
            } else {
                fs.bySyncTool { fs.externalWriteAtomic(copy, version.bytes, version.mtime) }
            }
            known[to][path] = SyncVersion(current.copyOf(), localMtime, bump(joined, to), to)
        }
        conflicts += "step ${world.step}: '$path' on ${peers[to].name}, ${loser.name}'s copy kept as '$copy'"
        trace += "   sync: conflict on '$path' at ${peers[to].name}; ${loser.name}'s version kept as '$copy'"
        send(to, path, known[to].getValue(path), immediate)
    }

    /**
     * Whether the app itself, not the sync tool or another process, put [bytes] at [path] in [fs]: a
     * change its history ([InMemoryLibraryFileSystem.historyOf]) records an app call making.
     */
    private fun appPlaced(
        fs: InMemoryLibraryFileSystem,
        path: String,
        bytes: ByteArray,
    ): Boolean {
        val made = " -> ${sha256Bytes(bytes).take(ID)}"
        return fs.historyOf(path).any { it.endsWith(made) && !it.endsWith(" external$made") }
    }

    /** Put [version]'s bytes (or its deletion) at [path], from [temp] when they were transferred there. */
    private fun put(
        fs: InMemoryLibraryFileSystem,
        path: String,
        version: SyncVersion,
        temp: String?,
    ) {
        val bytes = version.bytes
        when {
            bytes == null -> {
                fs.bySyncTool { fs.externalDelete(path) }
            }

            temp != null && fs.bytesAt(temp) != null -> {
                fs.bySyncTool { fs.externalMove(temp, path) }
            }

            else -> {
                fs.bySyncTool { fs.externalWriteAtomic(path, bytes, version.mtime) }
            }
        }
    }

    // ---- joining --------------------------------------------------------------------------------

    /**
     * Share the folder with [peer] from now on, its folder empty: the tool sends it the latest
     * version every other device holds of each path (the one that descends from all the others',
     * or the newest where none does, as a conflict would resolve), each over a seeded delay, so the
     * folder fills over virtual time while the app may already be open on it.
     */
    override fun join(peer: Int) {
        check(!present[peer]) { "${peers[peer].name} shares the folder already" }
        val others = sharing
        present[peer] = true
        val paths = others.flatMap { known[it].keys }.toSortedSet()
        for (path in paths) {
            val held = others.mapNotNull { from -> known[from][path]?.let { from to it } }
            val (from, version) =
                held.firstOrNull { (_, candidate) -> held.all { (_, other) -> candidate.dominates(other.vector) } }
                    ?: held.maxWith(
                        compareBy<Pair<Int, SyncVersion>> { it.second.mtime }.thenBy { peers[it.first].deviceShort },
                    )
            if (version.bytes == null) continue
            trace += "   sync: sending ${version.describe()} '$path' to ${peers[peer].name}, which joined"
            schedule(peer, path, SyncVersion(version.bytes, version.mtime, version.vector, from), duplicate = false)
        }
    }

    // ---- quiescence -----------------------------------------------------------------------------

    /** Let every delivery under way land, however long it waits. */
    override fun finishInFlight() {
        val start = world.scheduler.currentTime
        var handled = 0
        while (inFlight.any { !it.second.isCompleted }) {
            handled += inFlight.size
            inFlight.toList().forEach { (what, deferred) ->
                val from = world.scheduler.currentTime
                world.drive(deferred, "sync delivery $what")
                val waited = world.scheduler.currentTime - from
                if (waited > LONG_WAIT_MILLIS) trace += "   sync: waited ${waited}ms of virtual time for $what"
            }
        }
        val waitedAll = world.scheduler.currentTime - start
        if (waitedAll >
            LONG_WAIT_MILLIS
        ) {
            trace += "   sync: waited ${waitedAll}ms of virtual time for $handled deliveries to land"
        }
        inFlight.forEach { (what, deferred) ->
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            deferred.getCompletionExceptionOrNull()?.let {
                undeliverable +=
                    "$what: ${it::class.simpleName}: ${it.message}"
            }
        }
        inFlight.clear()
    }

    /**
     * Sync everything: let what is under way land, then scan every device and deliver at once, until
     * a round finds nothing changed. Returns how many paths changed over all rounds.
     */
    override fun flush(): Int {
        var total = 0
        repeat(FLUSH_ROUNDS) {
            finishInFlight()
            val changed = sharing.sumOf { peer -> scan(peer, immediate = true) } + pullAll()
            total += changed
            if (changed == 0) return total
        }
        return total
    }

    /**
     * Bring every device every version another holds that its own does not descend from, as the
     * tool does when it compares a device's index with the others': what a lost or overtaken
     * delivery left out comes now. Returns how many it brought.
     *
     * Two devices recording different bytes under one version vector would be a slip of this model
     * (every version a device makes bumps its own counter, so no two contents share a vector), never
     * the tool's: it fails the run, rather than being quietly settled and hiding the next slip.
     */
    private fun pullAll(): Int {
        var pulled = 0
        val paths = sharing.flatMap { known[it].keys }.toSortedSet()
        for (path in paths) {
            for (to in sharing) {
                for (from in sharing.filter { it != to }) {
                    val theirs = known[from][path] ?: continue
                    val mine = known[to][path]
                    check(mine == null || mine.vector != theirs.vector || same(mine.bytes, theirs.bytes)) {
                        "sync model: ${peers[to].name} and ${peers[from].name} record different contents of " +
                            "'$path' under one version vector ${theirs.vector} " +
                            "(${mine?.describe()} and ${theirs.describe()})"
                    }
                    if (mine?.dominates(theirs.vector) != true) {
                        apply(to, path, theirs, immediate = true)
                        pulled++
                    }
                }
            }
        }
        return pulled
    }

    /**
     * The paths whose files differ between the devices sharing the folder (missing on some, or other
     * bytes); empty when all agree.
     */
    override fun differences(): List<String> {
        val devices = sharing
        val maps = devices.map { peers[it].fs.allFiles().filterKeys(::synced) }
        val paths = maps.flatMap { it.keys }.toSortedSet()
        return paths.mapNotNull { path ->
            val hashes = maps.map { files -> files[path]?.let { sha256Bytes(it).take(ID) } ?: "-" }
            path.takeIf { hashes.distinct().size > 1 }?.let {
                "'$it': " +
                    devices.withIndex().joinToString { (slot, i) ->
                        val recorded = known[i][it]
                        val held =
                            recorded?.bytes?.let { bytes -> sha256Bytes(bytes).take(ID) } ?: recorded?.let { "deleted" }
                                ?: "none"
                        "${peers[i].name}=${hashes[slot]} (tool has $held ${recorded?.vector})"
                    } +
                    // A journal object some device keeps writing says what it keeps deciding.
                    if (SimNames.isJournalObject(it)) {
                        "\n    holding: " +
                            maps.firstNotNullOf { files -> files[it] }.decodeToString().trim()
                    } else {
                        ""
                    }
            }
        }
    }

    // ---- names ----------------------------------------------------------------------------------

    private fun synced(path: String): Boolean = !NpnPaths.isSyncTemp(NpnPaths.fileName(path))

    private fun tempName(path: String): String {
        val nonce = if (options.tool == SyncTool.NEXTCLOUD) random.nextInt(NONCE_FROM, NONCE_UNTIL) else 0
        return SimNames.syncTemp(path, options.tool, nonce)
    }

    private fun conflictName(
        path: String,
        device: String,
    ): String {
        val name = NpnPaths.fileName(path)
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        val at = Instant.ofEpochMilli(world.clock.nowMillis())
        val copy =
            when (options.tool) {
                SyncTool.NEXTCLOUD -> "$base (conflicted copy ${NEXTCLOUD_TIME.format(at)})$ext"
                SyncTool.SYNCTHING, SyncTool.RESILIO -> "$base.sync-conflict-${SYNCTHING_TIME.format(at)}-$device$ext"
            }
        val dir = NpnPaths.parentDir(path)
        return if (dir.isEmpty()) copy else "$dir/$copy"
    }

    private fun bump(
        vector: Map<Int, Long>?,
        peer: Int,
    ): Map<Int, Long> = vector.orEmpty() + (peer to ++counters[peer])

    private fun join(
        a: Map<Int, Long>,
        b: Map<Int, Long>,
    ): Map<Int, Long> = (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0, b[it] ?: 0) }

    private fun same(
        a: ByteArray?,
        b: ByteArray?,
    ): Boolean = if (a == null || b == null) a === b else a.contentEquals(b)

    private fun SyncVersion.describe(): String = bytes?.let { "${sha256Bytes(it).take(ID)}" } ?: "deletion of"

    companion object {
        private const val ID = 8
        private const val TRANSFER_MILLIS = 2_500L
        private const val FLUSH_ROUNDS = 12
        private const val BUSY_POLL_MILLIS = 500L
        private const val LONG_WAIT_MILLIS = 15L * 60_000
        private const val NONCE_FROM = 0x10000
        private const val NONCE_UNTIL = 0xfffff
        private val SYNCTHING_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val NEXTCLOUD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneOffset.UTC)

        /** Whether every folder above [path] is a folder (or not there yet) on [fs], so a file can be put there. */
        fun placeable(
            fs: InMemoryLibraryFileSystem,
            path: String,
        ): Boolean {
            var parent = NpnPaths.parentDir(path)
            while (parent.isNotEmpty()) {
                if (fs.bytesAt(parent) != null) return false
                parent = NpnPaths.parentDir(parent)
            }
            return true
        }
    }
}
