package dev.npn.core.sim

import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
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
    /** The share of PDF deliveries written in place over time, partial at their final name meanwhile. */
    val inPlacePdfPercent: Int,
    /** The share of deliveries made a second time, as a tool that re-pulls a file it already has does. */
    val duplicatePercent: Int,
)

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
 * or (for a PDF, sometimes) written in place over a while, as `cp`, a scanner app or `adb push`
 * write one; what is not a PDF, a journal object above all, always lands whole by a rename, as every
 * tool of this kind does it. Journal objects, OCR sidecars, device markers, staging files and PDFs
 * travel alike; a deletion travels as a version too. A delivery a device's own unscanned change, or
 * a version it has that does not descend from it, contends with is a conflict: identical bytes
 * merge, a modification beats a deletion, and otherwise the newer mtime (then the higher device id)
 * keeps the name while the other is kept as a conflict copy, which then syncs like any new file.
 * Modification times travel with the bytes.
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
) {
    /** What the tool last recorded at each path of each device: its local index. */
    private val known = peers.map { HashMap<String, SyncVersion>() }

    /** Version counters, one per device. */
    private val counters = LongArray(peers.size)

    /** Paths the tool is writing in place on each device, which its scans of that device leave alone. */
    private val writing = peers.map { HashSet<String>() }

    /** Paths a delivery is transferring to each device now. */
    private val transferring = peers.map { HashSet<String>() }

    private val inFlight = ArrayList<Pair<String, Deferred<Unit>>>()

    /** Conflict copies made, for the trace and the summary. */
    val conflicts = ArrayList<String>()

    /** Conflicts on names that must be written once: never expected. */
    val violations = ArrayList<String>()

    /** Deliveries the tool could not make (a file where a folder must be), for the report. */
    val undeliverable = ArrayList<String>()

    val pendingDeliveries: Int get() = inFlight.count { !it.second.isCompleted }

    // ---- scanning -------------------------------------------------------------------------------

    /**
     * Record what changed on [peer] since its last scan and send it to the other devices: at once
     * when [immediate] (each delivery atomic, no delay), else as seeded deliveries over virtual time.
     * Returns how many paths changed.
     */
    fun scan(
        peer: Int,
        immediate: Boolean = false,
    ): Int {
        val fs = peers[peer].fs
        val index = known[peer]
        val files = fs.allFiles().filterKeys { synced(it) && it !in writing[peer] }
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
            if (recorded.bytes != null && path !in files && path !in writing[peer]) {
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
        peers.indices.filter { it != from }.forEach { to ->
            if (immediate) {
                apply(to, path, version, Delivery.Atomic, immediate = true)
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
        val inPlace = version.bytes != null && isPdf(path) && random.chance(options.inPlacePdfPercent)
        val delivery =
            when {
                inPlace -> Delivery.InPlace(random.nextInt(2, CHUNKS), random.nextLong(200, CHUNK_INTERVAL_MILLIS))
                else -> Delivery.ViaTemp(random.nextLong(0, TRANSFER_MILLIS))
            }
        val what = "${if (duplicate) "again " else ""}${version.describe()} '$path' to ${peers[to].name}"
        inFlight +=
            what to
            world.launch {
                delay(wait)
                deliver(to, path, version, delivery, duplicate)
            }
    }

    private sealed interface Delivery {
        data object Atomic : Delivery

        data class ViaTemp(
            val transferMillis: Long,
        ) : Delivery

        data class InPlace(
            val chunks: Int,
            val intervalMillis: Long,
        ) : Delivery
    }

    // ---- delivering -----------------------------------------------------------------------------

    private suspend fun deliver(
        to: Int,
        path: String,
        version: SyncVersion,
        delivery: Delivery,
        duplicate: Boolean,
    ) {
        val fs = peers[to].fs
        // One transfer of a path to a device at a time: they share its temporary name.
        while (path in transferring[to] || path in writing[to]) delay(BUSY_POLL_MILLIS)
        transferring[to] += path
        try {
            transfer(to, path, version, delivery, duplicate, fs)
        } finally {
            transferring[to] -= path
        }
    }

    private suspend fun transfer(
        to: Int,
        path: String,
        version: SyncVersion,
        delivery: Delivery,
        duplicate: Boolean,
        fs: InMemoryLibraryFileSystem,
    ) {
        when (delivery) {
            is Delivery.ViaTemp -> {
                val bytes = version.bytes
                if (bytes == null) {
                    apply(to, path, version, Delivery.Atomic, immediate = false, duplicate = duplicate)
                    return
                }
                val temp = tempName(path)
                if (!placeable(fs, temp)) {
                    undeliverable += "${peers[to].name}: '$path'"
                    return
                }
                fs.bySyncTool { fs.externalWriteAtomic(temp, bytes, version.mtime) }
                if (delivery.transferMillis > 0) delay(delivery.transferMillis)
                // The temporary file is the tool's; if it went meanwhile, the next pull starts over.
                if (fs.bytesAt(temp) == null) return
                apply(to, path, version, delivery, immediate = false, duplicate = duplicate, temp = temp)
                fs.bySyncTool { fs.externalDelete(temp) }
            }

            Delivery.Atomic, is Delivery.InPlace -> {
                apply(to, path, version, delivery, immediate = false, duplicate = duplicate)
            }
        }
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
        delivery: Delivery,
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
                put(fs, path, version, Delivery.Atomic, temp)
            }
            return
        }
        val concurrent = localChange || (recorded != null && !version.dominates(recorded.vector))
        if (!concurrent) {
            put(fs, path, version, delivery, temp, to)
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
                put(fs, path, version, delivery, temp, to)
                // A version of its own, so no other content ever has its vector.
                known[to][path] = SyncVersion(version.bytes.copyOf(), version.mtime, bump(joined, to), to)
            }

            else -> {
                conflict(to, path, version, current, joined, delivery, immediate, temp)
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
        delivery: Delivery,
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
        if (writeOnceName(path)) {
            violations += "step ${world.step}: conflicting versions of the write-once '$path' " +
                "(${sha256Bytes(current).take(ID)} on ${peers[to].name}, " +
                "${sha256Bytes(version.bytes!!).take(ID)} from ${peers[remote].name})"
        }
        if (remoteWins) {
            fs.bySyncTool { fs.externalMove(path, copy) }
            put(fs, path, version, delivery, temp, to)
            known[to][path] = SyncVersion(version.bytes!!.copyOf(), version.mtime, bump(joined, to), to)
        } else {
            if (temp !=
                null
            ) {
                fs.bySyncTool { fs.externalMove(temp, copy) }
            } else {
                fs.bySyncTool { fs.externalWriteAtomic(copy, version.bytes!!, version.mtime) }
            }
            known[to][path] = SyncVersion(current.copyOf(), localMtime, bump(joined, to), to)
        }
        conflicts += "step ${world.step}: '$path' on ${peers[to].name}, ${loser.name}'s copy kept as '$copy'"
        trace += "   sync: conflict on '$path' at ${peers[to].name}; ${loser.name}'s version kept as '$copy'"
        send(to, path, known[to].getValue(path), immediate)
    }

    /** Put [version]'s bytes (or its deletion) at [path], from [temp] when they were transferred there. */
    private fun put(
        fs: InMemoryLibraryFileSystem,
        path: String,
        version: SyncVersion,
        delivery: Delivery,
        temp: String?,
        to: Int = -1,
    ) {
        val bytes = version.bytes
        when {
            bytes == null -> {
                fs.bySyncTool { fs.externalDelete(path) }
            }

            delivery is Delivery.InPlace -> {
                writing[to] += path
                inFlight +=
                    "in place '$path' at ${peers[to].name}" to
                    world.launch {
                        try {
                            fs.externalWriteInChunks(
                                path,
                                bytes,
                                delivery.chunks,
                                delivery.intervalMillis,
                                bySync = true,
                            )
                        } finally {
                            writing[to] -= path
                        }
                    }
            }

            temp != null && fs.bytesAt(temp) != null -> {
                fs.bySyncTool { fs.externalMove(temp, path) }
            }

            else -> {
                fs.bySyncTool { fs.externalWriteAtomic(path, bytes, version.mtime) }
            }
        }
    }

    // ---- quiescence -----------------------------------------------------------------------------

    /** Let every delivery under way land, however long it waits. */
    fun finishInFlight() {
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
    fun flush(): Int {
        var total = 0
        repeat(FLUSH_ROUNDS) {
            finishInFlight()
            val changed = peers.indices.sumOf { peer -> scan(peer, immediate = true) } + pullAll()
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
        val paths = known.flatMap { it.keys }.toSortedSet()
        for (path in paths) {
            for (to in peers.indices) {
                for (from in peers.indices.filter { it != to }) {
                    val theirs = known[from][path] ?: continue
                    val mine = known[to][path]
                    check(mine == null || mine.vector != theirs.vector || same(mine.bytes, theirs.bytes)) {
                        "sync model: ${peers[to].name} and ${peers[from].name} record different contents of " +
                            "'$path' under one version vector ${theirs.vector} " +
                            "(${mine?.describe()} and ${theirs.describe()})"
                    }
                    if (mine?.dominates(theirs.vector) != true) {
                        apply(to, path, theirs, Delivery.Atomic, immediate = true)
                        pulled++
                    }
                }
            }
        }
        return pulled
    }

    /** The paths whose files differ between the devices (missing on some, or other bytes); empty when all agree. */
    fun differences(): List<String> {
        val maps = peers.map { peer -> peer.fs.allFiles().filterKeys(::synced) }
        val paths = maps.flatMap { it.keys }.toSortedSet()
        return paths.mapNotNull { path ->
            val hashes = maps.map { files -> files[path]?.let { sha256Bytes(it).take(ID) } ?: "-" }
            path.takeIf { hashes.distinct().size > 1 }?.let {
                "'$it': " +
                    peers.indices.joinToString { i ->
                        val recorded = known[i][it]
                        val held =
                            recorded?.bytes?.let { bytes -> sha256Bytes(bytes).take(ID) } ?: recorded?.let { "deleted" }
                                ?: "none"
                        val busy = if (it in writing[i]) ", writing" else ""
                        "${peers[i].name}=${hashes[i]} (tool has $held ${recorded?.vector}$busy)"
                    } +
                    // A journal object some device keeps writing says what it keeps deciding.
                    if (isJournalObject(it)) {
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
        val name = NpnPaths.fileName(path)
        val temp =
            when (options.tool) {
                SyncTool.SYNCTHING -> ".syncthing.$name.tmp"
                SyncTool.NEXTCLOUD -> ".$name.~${random.nextInt(0x10000, 0xfffff).toString(16)}"
                SyncTool.RESILIO -> "$name.!sync"
            }
        val dir = NpnPaths.parentDir(path)
        return if (dir.isEmpty()) temp else "$dir/$temp"
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
        private const val CHUNKS = 5
        private const val CHUNK_INTERVAL_MILLIS = 3_000L
        private const val TRANSFER_MILLIS = 2_500L
        private const val FLUSH_ROUNDS = 12
        private const val BUSY_POLL_MILLIS = 500L
        private const val LONG_WAIT_MILLIS = 15L * 60_000
        private val SYNCTHING_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        private val NEXTCLOUD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneOffset.UTC)

        fun isPdf(path: String): Boolean = NpnPaths.indexable(FileMeta(path, NpnPaths.fileName(path), 1, 0, false))

        fun isJournalObject(path: String): Boolean =
            JournalPaths.classify(path).let {
                it is JournalPath.ObjectV2 ||
                    it is JournalPath.SnapshotV2
            }

        /** Names the library writes once and never again: two different versions of one is a broken invariant. */
        fun writeOnceName(path: String): Boolean =
            isJournalObject(path) ||
                (
                    path.endsWith(".json") &&
                        (path.startsWith("${NpnPaths.OCR_DIR}/") || path.startsWith("${NpnPaths.DEVICES_DIR}/"))
                )

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
