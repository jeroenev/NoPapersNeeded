package dev.npn.core.sim

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import java.util.TreeMap
import kotlin.random.Random

/** How a seed's rclone bisync behaves. Everything here is drawn from the seed ([SyncConfig.forSeed]). */
data class BisyncOptions(
    /**
     * Percent of runs in the background that stop part way: the process killed with the app (the
     * listings stay as they were), or a transfer failing (a critical error: the listings are dropped,
     * and the next run recovers from the backup listing, or resyncs without one).
     */
    val interruptPercent: Int,
)

/**
 * rclone `bisync` between each device's folder and one cloud remote, run as the app would run it on
 * every device: `--compare size,modtime,checksum --conflict-resolve newer --conflict-loser num
 * --resilient --recover --resync-mode newer`, and a `--resync` whenever a run reports it is locked
 * out. Modelled on rclone v1.75.1's `cmd/bisync`:
 *
 * - A run ([scan]) lists both sides and compares each with the listing the device saved after its
 *   last successful run: a path is new, changed (other bytes or mtime) or deleted on each side.
 * - Changed on one side only: copied to the other, over whatever is there now (nothing is checked
 *   again before a transfer: the listing at the start decides). A deletion on one side deletes the
 *   other's file, unless that one changed, which is copied back. A copy whose source went since the
 *   listing deletes the destination, as rclone's `sync` over a file list does.
 * - Changed on both: identical bytes are no conflict (the newer mtime is copied over); otherwise the
 *   newer mtime keeps the name and the other is renamed `<name>.conflict<n>` on its side, and both
 *   are copied across. Equal mtimes rename both. A conflict on a name the library writes once is a
 *   [violations] entry, as for [SimSync].
 * - Renames go first, then the transfers to the device (copies, then deletions), then the ones to
 *   the remote. A file reaches the device under rclone's temporary name (`<name>.<hex>.partial`) and
 *   is renamed into place; the remote takes a file whole.
 * - More than half of a side's listed files deleted aborts the run, changing nothing, as
 *   `--max-delete 50` does; the next run meets the same deletions.
 * - The saved listing after a run is the one at its start with the run's own transfers applied, so
 *   what changed during the run is a change for the next one. The listing before is kept as the
 *   backup (`.lst-old`).
 * - A run stopped by an error drops the listings (`.lst-err`): the next one goes back to the backup
 *   (`--recover`), or, without one (a first run after a resync), resyncs. A resync copies every file
 *   one side lacks to it and, where both hold other bytes, the newer over the older (the remote's
 *   first, on a tie): files deleted on one side since come back, and bytes it replaces are gone.
 *
 * Bytes the tool replaces or deletes that its listing did not show (a change made during the run, or
 * anything a resync overwrites) are its own doing, not a change it carries: [clobbered].
 */
internal class SimBisync(
    private val world: SimWorld,
    val peers: List<SyncPeer>,
    private val random: Random,
    val options: BisyncOptions,
    private val trace: MutableList<String>,
    /** Which devices share the folder from the start; the others [join] later. */
    present: List<Boolean> = peers.map { true },
) : FolderSyncTool {
    private class Stored(
        val bytes: ByteArray,
        val mtime: Long,
    )

    /** One file in a listing: what `--compare size,modtime,checksum` tells apart. */
    private data class Listed(
        val hash: String,
        val size: Int,
        val mtime: Long,
    )

    private class Listings(
        val local: Map<String, Listed>,
        val remote: Map<String, Listed>,
    )

    private class DeviceState {
        /** `.lst`: the listings saved by the last successful run, null before the first or after a critical error. */
        var current: Listings? = null

        /** `.lst-old`: the listings before the last successful run, which `--recover` goes back to. */
        var backup: Listings? = null

        /** A run holds the device's lock file: another run of it refuses to start. */
        var running = false
    }

    private sealed interface Op {
        val path: String

        /** Rename [path] to [to] on one side: a conflict's loser. */
        data class Rename(
            val onLocal: Boolean,
            override val path: String,
            val to: String,
        ) : Op

        /** Bring [path] from the other side to this one ([toLocal]), or delete it here when the other has none. */
        data class Transfer(
            val toLocal: Boolean,
            override val path: String,
        ) : Op

        /** Delete [path] on one side: a deletion the other side made. */
        data class Delete(
            val onLocal: Boolean,
            override val path: String,
        ) : Op
    }

    private val remote = TreeMap<String, Stored>()
    private val states = peers.map { DeviceState() }
    private val present = present.toBooleanArray()
    private val sharing: List<Int> get() = peers.indices.filter { present[it] }
    private val inFlight = ArrayList<Pair<String, Deferred<Unit>>>()

    override val conflicts = ArrayList<String>()
    override val violations = ArrayList<String>()
    override val undeliverable = ArrayList<String>()

    /** Bytes the tool replaced or deleted that its listing did not show (see the class comment), by hash: why. */
    val clobbered = HashMap<String, String>()

    override val pendingDeliveries: Int get() = inFlight.count { !it.second.isCompleted }

    override fun holds(hash: String): Boolean = remote.values.any { sha256Bytes(it.bytes) == hash }

    // ---- a run ----------------------------------------------------------------------------------

    /**
     * One bisync run of [peer]: listed and planned now, carried out over virtual time (or at once,
     * with no interruption, when [immediate]). Returns how many operations it planned.
     */
    override fun scan(
        peer: Int,
        immediate: Boolean,
    ): Int {
        if (!present[peer]) return 0
        val state = states[peer]
        if (state.running) {
            trace += "   bisync: ${peers[peer].name} is running already (lock file)"
            return 0
        }
        val local = listLocal(peer)
        val remoteNow = listRemote()
        val prior = state.current ?: state.backup?.takeIf { consistent(it) }
        val resync = prior == null
        val ops = if (resync) planResync(peer, local, remoteNow) else plan(peer, prior, local, remoteNow) ?: return 0
        val name = peers[peer].name
        val mode =
            when {
                resync -> "resync"
                state.current == null -> "recovered from the backup listing"
                else -> "run"
            }
        trace += "   bisync: $name's $mode plans ${ops.size} operation(s)" +
            ops.take(TRACE_OPS).joinToString("") { "\n      ${describe(it)}" }
        val interruptAt =
            if (!immediate && random.chance(options.interruptPercent)) random.nextInt(ops.size + 1) else null
        val killed = interruptAt != null && random.nextBoolean()
        if (immediate) {
            val after = Execution(peer, local, remoteNow, resync)
            ops.forEach { after.apply(it, immediate = true) }
            finish(peer, prior, after)
            return ops.size
        }
        state.running = true
        inFlight +=
            "bisync $mode of $name" to
            world.launch {
                try {
                    val after = Execution(peer, local, remoteNow, resync)
                    for ((index, op) in ops.withIndex()) {
                        if (index == interruptAt) {
                            interrupted(peer, killed, index, ops.size)
                            return@launch
                        }
                        delay(random.nextLong(1, CALL_MILLIS))
                        after.apply(op, immediate = false)
                    }
                    if (interruptAt == ops.size) {
                        interrupted(peer, killed, ops.size, ops.size)
                        return@launch
                    }
                    finish(peer, prior, after)
                } finally {
                    state.running = false
                }
            }
        return ops.size
    }

    private fun interrupted(
        peer: Int,
        killed: Boolean,
        done: Int,
        planned: Int,
    ) {
        val state = states[peer]
        if (killed) {
            trace += "   bisync: ${peers[peer].name}'s run was killed after $done of $planned operation(s); " +
                "its listings stay"
        } else {
            state.current = null
            trace += "   bisync: ${peers[peer].name}'s run failed after $done of $planned operation(s); " +
                "its listings are dropped (${if (state.backup != null) "it recovers next" else "it resyncs next"})"
        }
    }

    private fun finish(
        peer: Int,
        prior: Listings?,
        after: Execution,
    ) {
        val state = states[peer]
        // A run saves the listing it started from as the backup; a resync leaves none.
        state.backup = prior
        state.current = Listings(after.local.toMap(), after.remote.toMap())
    }

    /** `--check-sync` on the backup listings before `--recover` trusts them: both sides list the same files. */
    private fun consistent(listings: Listings): Boolean = listings.local.keys == listings.remote.keys

    // ---- planning -------------------------------------------------------------------------------

    private enum class Delta { NEW, CHANGED, DELETED }

    private fun deltas(
        prior: Map<String, Listed>,
        now: Map<String, Listed>,
    ): Map<String, Delta> {
        val deltas = HashMap<String, Delta>()
        now.forEach { (path, listed) ->
            val before = prior[path]
            when {
                before == null -> deltas[path] = Delta.NEW
                before != listed -> deltas[path] = Delta.CHANGED
            }
        }
        prior.keys.filter { it !in now }.forEach { deltas[it] = Delta.DELETED }
        return deltas
    }

    /** The operations of a run from [prior], or null when it aborts (too many deletions). */
    private fun plan(
        peer: Int,
        prior: Listings,
        local: Map<String, Listed>,
        remoteNow: Map<String, Listed>,
    ): List<Op>? {
        val d1 = deltas(prior.local, local)
        val d2 = deltas(prior.remote, remoteNow)
        listOf("device" to (d1 to prior.local), "remote" to (d2 to prior.remote)).forEach { (side, pair) ->
            val (deltas, old) = pair
            val deleted = deltas.count { it.value == Delta.DELETED }
            if (deleted > 0 && old.isNotEmpty() && deleted * PERCENT > old.size * MAX_DELETE_PERCENT) {
                trace += "   bisync: ${peers[peer].name}'s run aborts: too many deletes on the $side " +
                    "($deleted of ${old.size}); its listings stay"
                return null
            }
        }
        val renames = ArrayList<Op>()
        val toLocal = ArrayList<Op>()
        val toRemote = ArrayList<Op>()
        val handled = HashSet<String>()
        for (path in d1.keys.sorted()) {
            val a = d1.getValue(path)
            val b = d2[path]
            if (a != Delta.DELETED) {
                when {
                    b == null || b == Delta.DELETED -> {
                        toRemote += Op.Transfer(toLocal = false, path)
                    }

                    else -> {
                        conflict(peer, path, local.getValue(path), remoteNow.getValue(path), renames, toLocal, toRemote)
                    }
                }
                handled += path
            } else {
                when (b) {
                    null -> toRemote += Op.Delete(onLocal = false, path)
                    Delta.DELETED -> Unit
                    Delta.NEW, Delta.CHANGED -> toLocal += Op.Transfer(toLocal = true, path)
                }
                handled += path
            }
        }
        for (path in d2.keys.sorted()) {
            if (path in handled) continue
            when (d2.getValue(path)) {
                Delta.DELETED -> toLocal += Op.Delete(onLocal = true, path)
                Delta.NEW, Delta.CHANGED -> toLocal += Op.Transfer(toLocal = true, path)
            }
        }
        // Each direction is one `sync` over a file list: transfers first, deletions after.
        return renames + toLocal.sortedBy { it is Op.Delete } + toRemote.sortedBy { it is Op.Delete }
    }

    /** Both sides have [path] new or changed: equal bytes merge, else the newer keeps the name. */
    private fun conflict(
        peer: Int,
        path: String,
        mine: Listed,
        theirs: Listed,
        renames: MutableList<Op>,
        toLocal: MutableList<Op>,
        toRemote: MutableList<Op>,
    ) {
        if (mine.hash == theirs.hash) {
            when {
                mine.mtime > theirs.mtime -> toRemote += Op.Transfer(toLocal = false, path)
                mine.mtime < theirs.mtime -> toLocal += Op.Transfer(toLocal = true, path)
            }
            return
        }
        if (SimNames.isWriteOnce(path)) {
            violations += "step ${world.step}: conflicting versions of the write-once '$path' " +
                "(${mine.hash.take(ID)} on ${peers[peer].name}, ${theirs.hash.take(ID)} on the remote)"
        }
        val taken = { n: Int -> "$path$CONFLICT_SUFFIX$n" }
        var n = 1
        while (peers[peer].fs.bytesAt(taken(n)) != null || remote.containsKey(taken(n))) n++
        when {
            mine.mtime > theirs.mtime -> {
                renames += Op.Rename(onLocal = false, path, taken(n))
                toRemote += Op.Transfer(toLocal = false, path)
                toLocal += Op.Transfer(toLocal = true, taken(n))
                conflicts += "step ${world.step}: '$path', the remote's copy kept as '${taken(n)}'"
            }

            mine.mtime < theirs.mtime -> {
                renames += Op.Rename(onLocal = true, path, taken(n))
                toLocal += Op.Transfer(toLocal = true, path)
                toRemote += Op.Transfer(toLocal = false, taken(n))
                conflicts += "step ${world.step}: '$path', ${peers[peer].name}'s copy kept as '${taken(n)}'"
            }

            else -> {
                var m = n + 1
                while (peers[peer].fs.bytesAt(taken(m)) != null || remote.containsKey(taken(m))) m++
                renames += Op.Rename(onLocal = true, path, taken(n))
                renames += Op.Rename(onLocal = false, path, taken(m))
                toRemote += Op.Transfer(toLocal = false, taken(n))
                toLocal += Op.Transfer(toLocal = true, taken(m))
                conflicts += "step ${world.step}: '$path', no winner: both kept as '${taken(n)}' and '${taken(m)}'"
            }
        }
        trace += "   bisync: conflict on '$path' at ${peers[peer].name}"
    }

    /** A resync: every file one side lacks goes to it, and where both differ the newer goes over the older. */
    private fun planResync(
        peer: Int,
        local: Map<String, Listed>,
        remoteNow: Map<String, Listed>,
    ): List<Op> {
        val toLocal = ArrayList<Op>()
        val toRemote = ArrayList<Op>()
        (local.keys + remoteNow.keys).sorted().forEach { path ->
            val mine = local[path]
            val theirs = remoteNow[path]
            when {
                mine == null -> {
                    toLocal += Op.Transfer(toLocal = true, path)
                }

                theirs == null -> {
                    toRemote += Op.Transfer(toLocal = false, path)
                }

                mine.hash != theirs.hash -> {
                    if (SimNames.isWriteOnce(path)) {
                        violations += "step ${world.step}: a resync of ${peers[peer].name} met two versions of " +
                            "the write-once '$path'"
                    }
                    if (mine.mtime > theirs.mtime) {
                        toRemote += Op.Transfer(toLocal = false, path)
                    } else {
                        toLocal += Op.Transfer(toLocal = true, path)
                    }
                }
            }
        }
        return toLocal + toRemote
    }

    // ---- carrying a run out ---------------------------------------------------------------------

    /** What a run's listings become as it goes: the ones it started from, with its own operations applied. */
    private inner class Execution(
        val peer: Int,
        local: Map<String, Listed>,
        remote: Map<String, Listed>,
        private val resync: Boolean,
    ) {
        val local = HashMap(local)
        val remote = HashMap(remote)
        private val listedLocal = local
        private val listedRemote = remote
        private val fs = peers[peer].fs

        fun apply(
            op: Op,
            immediate: Boolean,
        ) {
            when (op) {
                is Op.Rename -> rename(op)
                is Op.Delete -> if (op.onLocal) deleteLocal(op.path) else deleteRemote(op.path)
                is Op.Transfer -> if (op.toLocal) toLocal(op.path, immediate) else toRemote(op.path)
            }
        }

        private fun rename(op: Op.Rename) {
            if (op.onLocal) {
                if (fs.bytesAt(op.path) == null) return
                fs.bySyncTool { fs.externalMove(op.path, op.to) }
                local.remove(op.path)?.let { local[op.to] = it }
            } else {
                val stored = this@SimBisync.remote.remove(op.path) ?: return
                this@SimBisync.remote[op.to] = stored
                remote.remove(op.path)?.let { remote[op.to] = it }
            }
        }

        private fun toLocal(
            path: String,
            immediate: Boolean,
        ) {
            val source = this@SimBisync.remote[path]
            if (source == null) return deleteLocal(path)
            if (fs.isDirectory(path) || !SimSync.placeable(fs, path)) {
                undeliverable += "${peers[peer].name}: '$path'"
                return
            }
            val current = fs.bytesAt(path)
            if (current != null && current.contentEquals(source.bytes) && fs.mtimeAt(path) == source.mtime) {
                local[path] = listed(source.bytes, source.mtime)
                return
            }
            unseen(current, listedLocal[path], source.bytes, "the device's '$path', replaced")
            if (immediate) {
                fs.bySyncTool { fs.externalWriteAtomic(path, source.bytes, source.mtime) }
            } else {
                val temp = partialName(path)
                check(
                    NpnPaths.isSyncTemp(NpnPaths.fileName(temp)),
                ) { "'$temp' is no sync temporary name the app knows" }
                fs.bySyncTool {
                    fs.externalWriteAtomic(temp, source.bytes, source.mtime)
                    fs.externalMove(temp, path)
                }
            }
            local[path] = listed(source.bytes, source.mtime)
        }

        private fun toRemote(path: String) {
            val bytes = fs.bytesAt(path)
            if (bytes == null) return deleteRemote(path)
            val mtime = fs.mtimeAt(path) ?: world.clock.nowMillis()
            unseen(this@SimBisync.remote[path]?.bytes, listedRemote[path], bytes, "the remote's '$path', replaced")
            this@SimBisync.remote[path] = Stored(bytes.copyOf(), mtime)
            remote[path] = listed(bytes, mtime)
        }

        private fun deleteLocal(path: String) {
            val current = fs.bytesAt(path)
            if (current != null) {
                unseen(current, listedLocal[path], null, "the device's '$path', deleted")
                fs.bySyncTool { fs.externalDelete(path) }
            }
            local.remove(path)
        }

        private fun deleteRemote(path: String) {
            this@SimBisync
                .remote
                .remove(
                    path,
                )?.let { unseen(it.bytes, listedRemote[path], null, "the remote's '$path', deleted") }
            remote.remove(path)
        }

        /**
         * The tool is about to take [current] from a path, for [replacement] (null: a deletion). Bytes
         * its listing did not show there, or any a resync replaces, are its own loss.
         */
        private fun unseen(
            current: ByteArray?,
            listed: Listed?,
            replacement: ByteArray?,
            what: String,
        ) {
            if (current == null || (replacement != null && current.contentEquals(replacement))) return
            val hash = sha256Bytes(current)
            val why =
                when {
                    resync -> "by ${peers[peer].name}'s resync"
                    listed?.hash != hash -> "by ${peers[peer].name}'s run, changed since its listing"
                    else -> return
                }
            clobbered[hash] = "$what at step ${world.step} $why"
        }
    }

    // ---- listing --------------------------------------------------------------------------------

    private fun listLocal(peer: Int): Map<String, Listed> {
        val fs = peers[peer].fs
        return fs
            .allFiles()
            .filterKeys(::synced)
            .mapValues { (path, bytes) -> listed(bytes, fs.mtimeAt(path) ?: 0L) }
    }

    private fun listRemote(): Map<String, Listed> =
        remote.mapValues { (_, stored) -> listed(stored.bytes, stored.mtime) }

    private fun listed(
        bytes: ByteArray,
        mtime: Long,
    ) = Listed(sha256Bytes(bytes), bytes.size, mtime)

    private fun synced(path: String): Boolean = !NpnPaths.isSyncTemp(NpnPaths.fileName(path))

    private fun partialName(path: String): String =
        "$path.${random.nextInt().toUInt().toString(HEX).padStart(HEX_DIGITS, '0')}.partial"

    private fun describe(op: Op): String =
        when (op) {
            is Op.Rename -> "rename '${op.path}' to '${op.to}' on ${if (op.onLocal) "the device" else "the remote"}"
            is Op.Transfer -> "${if (op.toLocal) "down" else "up"} '${op.path}'"
            is Op.Delete -> "delete '${op.path}' on ${if (op.onLocal) "the device" else "the remote"}"
        }

    // ---- joining and quiescence -----------------------------------------------------------------

    /** Share the folder with [peer] from now on: it has no listing yet, so its first run is a resync. */
    override fun join(peer: Int) {
        check(!present[peer]) { "${peers[peer].name} shares the folder already" }
        present[peer] = true
    }

    override fun finishInFlight() {
        while (inFlight.any { !it.second.isCompleted }) {
            inFlight.toList().forEach { (what, deferred) -> world.drive(deferred, what) }
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

    /** Runs on every device in turn, at once and never interrupted, until a round plans nothing. */
    override fun flush(): Int {
        var total = 0
        finishInFlight()
        repeat(FLUSH_ROUNDS) {
            val changed = sharing.sumOf { peer -> scan(peer, immediate = true) }
            total += changed
            if (changed == 0) return total
        }
        return total
    }

    override fun differences(): List<String> {
        val devices = sharing
        val maps = devices.map { peers[it].fs.allFiles().filterKeys(::synced) }
        val paths = maps.flatMap { it.keys }.toSortedSet()
        return paths.mapNotNull { path ->
            val hashes = maps.map { files -> files[path]?.let { sha256Bytes(it).take(ID) } ?: "-" }
            path.takeIf { hashes.distinct().size > 1 }?.let {
                "'$it': " + devices.withIndex().joinToString { (slot, i) -> "${peers[i].name}=${hashes[slot]}" } +
                    ", remote=${remote[it]?.let { stored -> sha256Bytes(stored.bytes).take(ID) } ?: "-"}"
            }
        }
    }

    private companion object {
        const val ID = 8
        const val CALL_MILLIS = 400L
        const val FLUSH_ROUNDS = 12
        const val TRACE_OPS = 8
        const val PERCENT = 100
        const val MAX_DELETE_PERCENT = 50
        const val HEX = 16
        const val HEX_DIGITS = 8
        const val CONFLICT_SUFFIX = ".conflict"
    }
}
