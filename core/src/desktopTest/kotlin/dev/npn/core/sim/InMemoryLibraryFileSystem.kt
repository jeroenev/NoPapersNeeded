package dev.npn.core.sim

import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.yield
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.util.TreeMap
import java.util.TreeSet
import kotlin.random.Random

/**
 * What the library folder sits on, as far as publication and the watcher go.
 *
 * - [DIRECT_LINKS]: a desktop filesystem (ext4): hard links, `renameat2(RENAME_NOREPLACE)`, inotify.
 * - [DIRECT_RENAME]: Android's shared storage, exFAT: no hard links, an exclusive rename only.
 * - [SAF]: a document provider: a rename it checks afterwards, no document relocation, no watcher,
 *   and listings that may report no mtime ([SimFsOptions.mtimes]).
 * - [NONE]: neither primitive; the library must refuse to open.
 */
enum class StorageProfile(
    val hardLinks: Boolean,
    val exclusiveRename: Boolean,
    val saf: Boolean,
) {
    DIRECT_LINKS(hardLinks = true, exclusiveRename = true, saf = false),
    DIRECT_RENAME(hardLinks = false, exclusiveRename = true, saf = false),
    SAF(hardLinks = false, exclusiveRename = false, saf = true),
    NONE(hardLinks = false, exclusiveRename = false, saf = false),
}

/** The knobs of an [InMemoryLibraryFileSystem]; all may change while a simulation runs. */
class SimFsOptions(
    var profile: StorageProfile = StorageProfile.DIRECT_LINKS,
    /** False for a provider that reports no mtime (SAF can give 0): listings and stats carry 0. */
    var mtimes: Boolean = true,
    /** Whether [InMemoryLibraryFileSystem.watch] reports changes (never on [StorageProfile.SAF]). */
    var watch: Boolean = true,
    /** The share of change events the watcher silently drops, as a real watch service may. */
    var watchDropRate: Double = 0.0,
    /** The share of change events that arrive as an overflow (`relPath == null`) instead. */
    var watchOverflowRate: Double = 0.0,
)

/**
 * Seeded latency at the storage boundary: every call suspends at least once, and some wait a while
 * of virtual time, so the order in which concurrent work interleaves at the filesystem changes from
 * seed to seed while staying reproducible for one.
 */
class SimLatency(
    private val random: Random,
    /** Zero for a filesystem that should run as fast as virtual time allows (the rebuild oracle's). */
    private val enabled: Boolean = true,
) {
    suspend fun pause() {
        if (!enabled) return
        val roll = random.nextInt(100)
        when {
            roll < 55 -> yield()
            roll < 90 -> delay(random.nextLong(1, 20))
            else -> delay(random.nextLong(20, 400))
        }
    }
}

/**
 * A [LibraryFileSystem] held in memory, with the semantics of [dev.npn.core.fs.JvmLibraryFileSystem]
 * (and, by [StorageProfile.SAF], of `SafLibraryFileSystem`), plus the hooks a simulation needs:
 * injected [faults], seeded [latency], a watcher it drives, and an "external world" (the `external*`
 * members) through which the test does what other processes do to a library folder.
 *
 * Every call runs on the caller's dispatcher (the simulation's test dispatcher) and takes effect at
 * one instant between two suspensions, as a syscall does. A file is a [Node]: two paths share one
 * after a hard link, and a write in place changes the node every name of it sees.
 *
 * It records every file the app creates under `.npn/` that must stay write-once (journal objects
 * and snapshots, OCR sidecars, device markers), and reports in [writeOnceViolations] a path that
 * comes to hold other bytes than it was first published with, or an object whose name is not the
 * hash of its bytes.
 */
class InMemoryLibraryFileSystem(
    /** The virtual wall clock in epoch millis, for mtimes. */
    private val now: () -> Long,
    val options: SimFsOptions = SimFsOptions(),
    val faults: SimFaults = SimFaults(),
    private val latency: SimLatency = SimLatency(Random(0), enabled = false),
    /** Decides which watch events are dropped or turned into overflows. */
    private val watchRandom: Random = Random(0),
) : LibraryFileSystem {
    /** A file's content. Shared by every name a hard link gave it. */
    class Node(
        var bytes: ByteArray,
        var mtime: Long,
    )

    private val files = TreeMap<String, Node>()
    private val dirs = TreeMap<String, Long>()

    private val events = MutableSharedFlow<FsEvent>(extraBufferCapacity = WATCH_BUFFER)

    /** Path to the hash it was first published with, for every write-once file the app created. */
    private val writeOnce = HashMap<String, String>()

    val writeOnceViolations = ArrayList<String>()

    /** Bumped by every change the external world makes; [externalChanges] maps a path to its last. */
    var externalSeq = 0L
        private set

    val externalChanges = HashMap<String, Long>()

    /** How many calls each method took, for a summary of a run. */
    val calls = HashMap<FsMethod, Int>()

    override val publicationMayBeLost: Boolean get() = !options.profile.saf

    override val supportsDocumentRelocation: Boolean get() = !options.profile.saf

    // ---- the app's view -------------------------------------------------------------------------

    private suspend fun <T> io(
        method: FsMethod,
        path: String,
        effect: () -> T,
    ): T {
        calls.merge(method, 1, Int::plus)
        latency.pause()
        faults.check(method, path, SimFaults.Point.BEFORE)
        actor = method
        val result =
            try {
                effect()
            } finally {
                actor = null
            }
        faults.check(method, path, SimFaults.Point.AFTER)
        latency.pause()
        return result
    }

    override suspend fun listAllIndexable(): List<FileMeta> =
        io(FsMethod.LIST_INDEXABLE, "") {
            entriesUnder(
                "",
            ).filterNot { it.relPath == NpnPaths.NPN_DIR || it.relPath.startsWith(NPN_PREFIX) }.partial("")
        }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.LIST_UNDER, norm) {
            when {
                files.containsKey(norm) -> listOf(metaOf(norm))
                norm.isEmpty() || dirs.containsKey(norm) -> entriesUnder(norm).partial(norm)
                else -> emptyList()
            }
        }
    }

    override suspend fun exists(relPath: String): Boolean {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.EXISTS, norm) { existsNow(norm) }
    }

    override suspend fun stat(relPath: String): FileMeta? {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.STAT, norm) { if (existsNow(norm) && norm.isNotEmpty()) metaOf(norm) else null }
    }

    override suspend fun readBytes(relPath: String): ByteArray {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.READ, norm) { fileAt(norm).bytes.copyOf() }
    }

    override suspend fun readTail(
        relPath: String,
        maxBytes: Int,
    ): ByteArray {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.READ_TAIL, norm) {
            val bytes = fileAt(norm).bytes
            bytes.copyOfRange(maxOf(0, bytes.size - maxBytes), bytes.size)
        }
    }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) {
        val norm = NpnPaths.normalize(relPath)
        io(FsMethod.WRITE_ONCE, norm) { create(norm, bytes.copyOf()) }
    }

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ) {
        val staging = NpnPaths.normalize(stagingPath)
        val canonical = NpnPaths.normalize(canonicalPath)
        io(FsMethod.PUBLISH, canonical) {
            val profile = options.profile
            if (!profile.hardLinks && !profile.exclusiveRename && !profile.saf) {
                throw VerifiedPublicationUnavailableException("No hard links and no exclusive rename")
            }
            require(NpnPaths.parentDir(staging) == NpnPaths.parentDir(canonical)) {
                "Object staging must be in the canonical bucket"
            }
            val node = files[staging]
            checkNotNull(node) { "Staging object is missing: $staging" }
            check(sha256Bytes(node.bytes) == expectedHash) { "Staging object hash mismatch: $staging" }
            val existing = files[canonical]
            if (existing != null || dirs.containsKey(canonical)) {
                check(existing != null && sha256Bytes(existing.bytes) == expectedHash) {
                    "Canonical object exists with invalid bytes: $canonical"
                }
            } else {
                files[canonical] = node
                touch(canonical)
                recordWrite(canonical, node.bytes)
            }
            check(sha256Bytes(files.getValue(canonical).bytes) == expectedHash) {
                "Published object hash mismatch: $canonical"
            }
            if (files.remove(staging) != null) touch(staging)
        }
    }

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) {
        val from = NpnPaths.normalize(fromRelPath)
        val to = NpnPaths.normalize(toRelPath)
        io(FsMethod.MOVE, to) {
            check(!existsNow(to)) { "Target already exists: $to" }
            relocate(from, to)
        }
    }

    override suspend fun claimDocument(
        fromRelPath: String,
        toRelPath: String,
    ) {
        if (!supportsDocumentRelocation) {
            throw VerifiedPublicationUnavailableException("Atomic document claiming is unsupported")
        }
        val from = NpnPaths.normalize(fromRelPath)
        val to = NpnPaths.normalize(toRelPath)
        io(FsMethod.CLAIM, to) {
            if (existsNow(to)) throw FileAlreadyExistsException(to)
            relocate(from, to)
        }
    }

    override suspend fun copyOnce(
        fromRelPath: String,
        toRelPath: String,
    ) {
        val from = NpnPaths.normalize(fromRelPath)
        val to = NpnPaths.normalize(toRelPath)
        io(FsMethod.COPY_ONCE, to) {
            val bytes = fileAt(from).bytes.copyOf()
            if (existsNow(to)) throw FileAlreadyExistsException(to)
            create(to, bytes)
        }
    }

    override suspend fun sameFile(
        first: String,
        second: String,
    ): Boolean {
        val a = NpnPaths.normalize(first)
        val b = NpnPaths.normalize(second)
        return io(FsMethod.SAME_FILE, a) {
            when {
                a == b -> true
                options.profile.saf -> false
                else -> fileAt(a) === fileAt(b)
            }
        }
    }

    override suspend fun delete(relPath: String) {
        val norm = NpnPaths.normalize(relPath)
        io(FsMethod.DELETE, norm) { remove(norm, byApp = true) }
    }

    override suspend fun sha256(relPath: String): String {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.SHA256, norm) { sha256Bytes(fileAt(norm).bytes) }
    }

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) {
        val norm = NpnPaths.normalize(toRelPath)
        val bytes = externalSources?.invoke(source.path) ?: Files.readAllBytes(source.path)
        io(FsMethod.IMPORT_EXTERNAL, norm) { create(norm, bytes) }
    }

    override fun watch(): Flow<FsEvent> = if (options.profile.saf) emptyFlow() else events.asSharedFlow()

    // ---- the external world ---------------------------------------------------------------------

    /**
     * Where [importExternal] reads a source the simulation holds in memory (a watched folder's
     * file); null for a real path.
     */
    var externalSources: ((java.nio.file.Path) -> ByteArray?)? = null

    /** Every file the app removed, by delete or by moving it away: the sync invariants check whose they were. */
    val appRemovals = ArrayList<String>()

    /**
     * How many of the next listings come back partial, as a document provider's cursor does while it
     * is still loading (`DocumentsContract.EXTRA_LOADING`): one directory's later children, and
     * everything under them, are left out. [partialListingsServed] counts the ones that were.
     */
    var partialListings = 0

    var partialListingsServed = 0
        private set

    /** Decides which directory a partial listing cuts short, and where. */
    var partialRandom: Random = Random(0)

    private fun List<FileMeta>.partial(root: String): List<FileMeta> {
        if (partialListings <= 0 || isEmpty()) return this
        partialListings--
        val parents = (map { NpnPaths.parentDir(it.relPath) }.toSortedSet() + root).toList()
        val dir = parents[partialRandom.nextInt(parents.size)]
        val children = filter { NpnPaths.parentDir(it.relPath) == dir }.map { it.relPath }.sorted()
        if (children.isEmpty()) return this
        val dropped = children.drop(partialRandom.nextInt(children.size)).toSet()
        partialListingsServed++
        return filterNot { meta -> dropped.any { meta.relPath == it || meta.relPath.startsWith("$it/") } }
    }

    /** Put [bytes] at [relPath] in one step, replacing whatever is there: a rename into place. */
    fun externalWriteAtomic(
        relPath: String,
        bytes: ByteArray,
        mtime: Long = now(),
    ) {
        val norm = NpnPaths.normalize(relPath)
        external(norm)
        if (dirs.containsKey(norm)) remove(norm)
        ensureParents(norm)
        files[norm] = Node(bytes.copyOf(), mtime)
        touch(norm)
    }

    /**
     * Write [bytes] at [relPath] in place, in [chunks] pieces [intervalMillis] apart, as `cp`, a
     * scanner app or `adb push` does: the file is there from the first chunk on, growing, its mtime
     * moving with each write. Over a file already there it truncates that file (the same node).
     */
    suspend fun externalWriteInChunks(
        relPath: String,
        bytes: ByteArray,
        chunks: Int,
        intervalMillis: Long,
    ) {
        val norm = NpnPaths.normalize(relPath)
        val step = (bytes.size + chunks - 1) / chunks
        writing += norm
        try {
            writeChunks(norm, bytes, step, intervalMillis)
        } finally {
            writing -= norm
        }
    }

    /** Paths another process is writing in place right now: partial, not damaged. */
    private val writing = HashSet<String>()

    private suspend fun writeChunks(
        norm: String,
        bytes: ByteArray,
        step: Int,
        intervalMillis: Long,
    ) {
        var written = 0
        while (written < bytes.size) {
            written = minOf(bytes.size, written + step)
            external(norm)
            val node = files[norm]
            if (node == null) {
                if (dirs.containsKey(norm)) remove(norm)
                ensureParents(norm)
                files[norm] = Node(bytes.copyOf(written), now())
            } else {
                node.bytes = bytes.copyOf(written)
                node.mtime = now()
            }
            touch(norm)
            if (written < bytes.size) delay(intervalMillis)
        }
    }

    /**
     * Change the bytes of the file at [relPath] in place (every hard link sees them). With
     * [keepStat] the size is unchanged only if the new bytes have the old length, and the mtime is
     * the old one: a tool that preserves mtimes.
     */
    fun externalOverwriteInPlace(
        relPath: String,
        bytes: ByteArray,
        keepStat: Boolean = false,
    ) {
        val norm = NpnPaths.normalize(relPath)
        val node = files[norm] ?: return externalWriteAtomic(norm, bytes)
        external(norm)
        node.bytes = bytes.copyOf()
        if (!keepStat) node.mtime = now()
        touch(norm)
    }

    /** Delete [relPath], a file or a whole directory, as another process does. */
    fun externalDelete(relPath: String) {
        val norm = NpnPaths.normalize(relPath)
        if (!existsNow(norm)) return
        external(norm)
        remove(norm)
    }

    /** Rename [from] to [to] (replacing [to]), as a user in a file manager or a sync tool does. */
    fun externalMove(
        from: String,
        to: String,
    ) {
        val src = NpnPaths.normalize(from)
        val dst = NpnPaths.normalize(to)
        if (!existsNow(src) || src == dst) return
        external(src)
        external(dst)
        if (existsNow(dst)) remove(dst)
        relocate(src, dst, byApp = false)
    }

    /** A copy of everything at or under [relPath], with mtimes, to [externalRestore] later. */
    fun externalBackup(relPath: String): Backup {
        val norm = NpnPaths.normalize(relPath)
        // A file another process is still writing is skipped, as a backup tool skips a file in use:
        // put back later, its partial bytes would be damage no library could tell from a lost file.
        val taken =
            files.filterKeys { (it == norm || it.startsWith("$norm/")) && it !in writing }.mapValues { (_, node) ->
                node.bytes.copyOf() to node.mtime
            }
        return Backup(norm, taken)
    }

    /**
     * Put [backup] back as a backup tool does: every file it holds is written again with the bytes
     * and mtime it had, over whatever is at its path now; files added since stay.
     */
    fun externalRestore(backup: Backup) {
        backup.files.forEach { (path, content) ->
            val (bytes, mtime) = content
            externalWriteAtomic(path, bytes, mtime)
        }
    }

    /** A copy of the files under one path, as [externalBackup] took it. */
    class Backup(
        val root: String,
        val files: Map<String, Pair<ByteArray, Long>>,
    )

    /**
     * Deliver [bytes] to [relPath] the way Syncthing does: written under its temporary name beside
     * the target first, then renamed into place [transferMillis] later. Returns once it is in place.
     */
    suspend fun externalSyncDelivery(
        relPath: String,
        bytes: ByteArray,
        transferMillis: Long,
        mtime: Long = now(),
    ) {
        val norm = NpnPaths.normalize(relPath)
        val temp = syncthingTemp(norm)
        externalWriteAtomic(temp, bytes, mtime)
        if (transferMillis > 0) delay(transferMillis)
        if (!files.containsKey(temp)) return
        externalMove(temp, norm)
    }

    /** Syncthing's temporary name for [relPath]: `.syncthing.<name>.tmp` beside it. */
    fun syncthingTemp(relPath: String): String {
        val dir = NpnPaths.parentDir(relPath)
        val name = ".syncthing.${NpnPaths.fileName(relPath)}.tmp"
        return if (dir.isEmpty()) name else "$dir/$name"
    }

    // ---- what the simulation inspects ------------------------------------------------------------

    /** Every file and its bytes, for invariants and the rebuild oracle. */
    fun allFiles(): Map<String, ByteArray> = files.mapValues { it.value.bytes }

    fun bytesAt(relPath: String): ByteArray? = files[relPath]?.bytes

    fun mtimeAt(relPath: String): Long? = files[relPath]?.mtime

    /** Every write-once file the app published here (not one that arrived from outside). */
    val appPublished: Set<String> get() = writeOnce.keys

    fun isDirectory(relPath: String): Boolean = dirs.containsKey(relPath)

    /**
     * A copy of this filesystem as it stands, with no faults, latency or watcher history, over
     * [clock]: what a rebuilt index sees.
     */
    fun snapshot(clock: () -> Long = now): InMemoryLibraryFileSystem {
        val copy =
            InMemoryLibraryFileSystem(
                now = clock,
                options = SimFsOptions(profile = options.profile, mtimes = options.mtimes, watch = false),
            )
        dirs.forEach { (path, mtime) -> copy.dirs[path] = mtime }
        // Hard links stay links in the copy, so sameFile answers alike.
        val nodes = HashMap<Node, Node>()
        files.forEach { (path, node) ->
            copy.files[path] = nodes.getOrPut(node) { Node(node.bytes.copyOf(), node.mtime) }
        }
        return copy
    }

    /** Journal objects and snapshots, under their canonical names, whose name is not the hash of their bytes. */
    fun misnamedObjects(): List<String> =
        files.mapNotNull { (path, node) ->
            val expected =
                when (val kind = JournalPaths.classify(path)) {
                    is JournalPath.ObjectV2 -> kind.expectedHash
                    is JournalPath.SnapshotV2 -> kind.expectedHash
                    else -> null
                }
            path.takeIf {
                expected != null && path !in writing && NpnPaths.fileName(path) == "$expected.jsonl" &&
                    sha256Bytes(node.bytes) != expected
            }
        }

    // ---- internals ------------------------------------------------------------------------------

    private fun existsNow(norm: String): Boolean = norm.isEmpty() || files.containsKey(norm) || dirs.containsKey(norm)

    private fun fileAt(norm: String): Node {
        files[norm]?.let { return it }
        if (dirs.containsKey(norm)) throw java.io.IOException("Is a directory: $norm")
        throw NoSuchFileException(norm)
    }

    private fun metaOf(norm: String): FileMeta {
        val node = files[norm]
        val mtime = node?.mtime ?: dirs[norm] ?: 0L
        return FileMeta(
            relPath = norm,
            name = NpnPaths.fileName(norm),
            size = node?.bytes?.size?.toLong() ?: 0L,
            mtimeMillis = if (options.mtimes) mtime else 0L,
            isDirectory = node == null,
        )
    }

    private fun entriesUnder(norm: String): List<FileMeta> {
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        val out = TreeSet<String>()
        dirs.keys.filterTo(out) { it.startsWith(prefix) && it != norm }
        files.keys.filterTo(out) { it.startsWith(prefix) }
        return out.map(::metaOf)
    }

    private fun ensureParents(norm: String) {
        var parent = NpnPaths.parentDir(norm)
        val created = ArrayList<String>()
        while (parent.isNotEmpty() && !dirs.containsKey(parent)) {
            check(!files.containsKey(parent)) { "Not a directory: $parent" }
            created += parent
            parent = NpnPaths.parentDir(parent)
        }
        created.asReversed().forEach {
            dirs[it] = now()
            touch(it)
        }
    }

    /** Create [norm] with [bytes]; it must not exist. */
    private fun create(
        norm: String,
        bytes: ByteArray,
    ) {
        check(!existsNow(norm)) { "Target already exists: $norm" }
        ensureParents(norm)
        files[norm] = Node(bytes, now())
        touch(norm)
        recordWrite(norm, bytes)
    }

    private fun relocate(
        from: String,
        to: String,
        byApp: Boolean = true,
    ) {
        val node = files[from]
        when {
            node != null -> {
                ensureParents(to)
                files.remove(from)
                files[to] = node
                touch(from)
                touch(to)
                if (byApp) {
                    appRemovals += from
                    recordWrite(to, node.bytes)
                }
            }

            dirs.containsKey(from) -> {
                ensureParents(to)
                val prefix = "$from/"
                dirs.keys.filter { it == from || it.startsWith(prefix) }.forEach { dir ->
                    dirs[to + dir.removePrefix(from)] = dirs.remove(dir) ?: now()
                }
                files.keys.filter { it.startsWith(prefix) }.forEach { file ->
                    files[to + file.removePrefix(from)] = files.remove(file) ?: return@forEach
                }
                touch(from)
                touch(to)
            }

            else -> {
                throw NoSuchFileException(from)
            }
        }
    }

    private fun remove(
        norm: String,
        byApp: Boolean = false,
    ) {
        if (files.remove(norm) != null) {
            if (byApp) appRemovals += norm
            touch(norm)
            return
        }
        if (!dirs.containsKey(norm)) return
        val prefix = "$norm/"
        files.keys.filter { it.startsWith(prefix) }.forEach {
            files.remove(it)
            if (byApp) appRemovals += it
        }
        dirs.keys.filter { it == norm || it.startsWith(prefix) }.forEach { dirs.remove(it) }
        touch(norm)
    }

    private fun external(norm: String) {
        externalSeq++
        externalChanges[norm] = externalSeq
    }

    /** Report a change at [norm] to the watcher, as inotify would (possibly dropped or as an overflow). */
    private fun touch(norm: String) {
        val entries = history.getOrPut(norm) { ArrayDeque() }
        val content =
            files[norm]?.let { sha256Bytes(it.bytes).take(HISTORY_ID) } ?: if (dirs.containsKey(norm)) "dir" else "gone"
        entries.addLast("t=${now()} ${actor?.name?.lowercase() ?: "external"} -> $content")
        if (entries.size > HISTORY_LENGTH) entries.removeFirst()
        if (!options.watch || options.profile.saf) return
        val roll = watchRandom.nextDouble()
        val event =
            when {
                roll < options.watchDropRate -> return
                roll < options.watchDropRate + options.watchOverflowRate -> FsEvent(null)
                else -> FsEvent(norm)
            }
        events.tryEmit(event)
    }

    /** Record the app's write of [bytes] at [norm], when it is a file that must stay write-once. */
    private fun recordWrite(
        norm: String,
        bytes: ByteArray,
    ) {
        if (!isWriteOnce(norm)) return
        val hash = sha256Bytes(bytes)
        val first = writeOnce.putIfAbsent(norm, hash)
        if (first != null && first != hash) {
            writeOnceViolations += "$norm was published as $first and written again as $hash"
        }
        val expected =
            when (val kind = JournalPaths.classify(norm)) {
                is JournalPath.ObjectV2 -> kind.expectedHash
                is JournalPath.SnapshotV2 -> kind.expectedHash
                else -> null
            }
        if (expected != null && expected != hash) writeOnceViolations += "$norm holds bytes hashing to $hash"
    }

    private fun isWriteOnce(norm: String): Boolean {
        val kind = JournalPaths.classify(norm)
        if (kind is JournalPath.ObjectV2 || kind is JournalPath.SnapshotV2) return true
        return norm.endsWith(".json") &&
            (norm.startsWith("${NpnPaths.OCR_DIR}/") || norm.startsWith("${NpnPaths.DEVICES_DIR}/"))
    }

    /** The app's call taking effect right now, or null for the outside world. */
    private var actor: FsMethod? = null

    /** The last changes at each path, and who made them: what a failure at a path shows. */
    private val history = HashMap<String, ArrayDeque<String>>()

    fun historyOf(relPath: String): List<String> = history[relPath].orEmpty().toList()

    private companion object {
        const val HISTORY_LENGTH = 12
        const val HISTORY_ID = 8
        const val WATCH_BUFFER = 100_000
        const val NPN_PREFIX = "${NpnPaths.NPN_DIR}/"
    }
}
