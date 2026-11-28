package dev.npn.core.sim

import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * - [NONE]: neither primitive; the library must refuse to open.
 */
enum class StorageProfile(
    val hardLinks: Boolean,
    val exclusiveRename: Boolean,
) {
    DIRECT_LINKS(hardLinks = true, exclusiveRename = true),
    DIRECT_RENAME(hardLinks = false, exclusiveRename = true),
    NONE(hardLinks = false, exclusiveRename = false),
    ;

    companion object {
        /** A device's storage as a seed draws it: mostly a desktop filesystem, else Android's shared storage. */
        fun draw(random: Random): StorageProfile =
            if (random.nextInt(100) <
                LINKS_PERCENT
            ) {
                DIRECT_LINKS
            } else {
                DIRECT_RENAME
            }

        private const val LINKS_PERCENT = 69
    }
}

/** The knobs of an [InMemoryLibraryFileSystem], fixed for its life. */
class SimFsOptions(
    val profile: StorageProfile = StorageProfile.DIRECT_LINKS,
    /** Whether [InMemoryLibraryFileSystem.watch] reports changes. */
    val watch: Boolean = true,
    /** The share of change events the watcher silently drops, as a real watch service may. */
    val watchDropRate: Double = 0.0,
    /** The share of change events that arrive as an overflow (`relPath == null`) instead. */
    val watchOverflowRate: Double = 0.0,
    /** The mtimes its files are given: one for every folder of a world, which the sync tool joins. */
    val stamps: SimMtimes = SimMtimes(),
)

/**
 * The mtimes the files of one world's folders are given: the virtual clock's, but never one a file
 * of the same size was given before. Two files with other bytes, one size and one mtime have one
 * stat, which the app's proof by stat takes for the same file: an mtime-preserving overwrite of the
 * same size, out of scope (docs/SIMULATION.md). Several steps run in one virtual millisecond, so
 * without this two files of one size written in that millisecond, then carried by the sync tool,
 * mtime and all, and the one renamed over the other, left the index holding the first document at a
 * path whose file holds the second (seeds 4100509 and 4100575 at 9f76eae2). One for every folder of
 * a world, as the sync tool carries files between them.
 */
class SimMtimes {
    private val last = HashMap<Int, Long>()

    /** The mtime of a file of [size] bytes written [now]: [now], or just past the last one given that size. */
    fun stamp(
        size: Int,
        now: Long,
    ): Long {
        val mtime = last[size]?.let { maxOf(now, it + 1) } ?: now
        last[size] = mtime
        return mtime
    }
}

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
 * A [LibraryFileSystem] held in memory, with the semantics of [dev.npn.core.fs.JvmLibraryFileSystem],
 * plus the hooks a simulation needs:
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

    // ---- the app's view -------------------------------------------------------------------------

    private suspend fun <T> io(
        method: FsMethod,
        path: String,
        effect: () -> T,
    ): T {
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
            ).filterNot { it.relPath == NpnPaths.NPN_DIR || it.relPath.startsWith(NPN_PREFIX) }
        }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> {
        val norm = NpnPaths.normalize(relPath)
        return io(FsMethod.LIST_UNDER, norm) {
            when {
                files.containsKey(norm) -> listOf(metaOf(norm))
                norm.isEmpty() || dirs.containsKey(norm) -> entriesUnder(norm)
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
        io(FsMethod.WRITE_ONCE, norm) { create(norm, bytes.copyOf()).also { publications.wrote(norm) } }
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
            if (!profile.hardLinks && !profile.exclusiveRename) {
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
                publications.published(staging, canonical, node.bytes)
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
        val from = NpnPaths.normalize(fromRelPath)
        val to = NpnPaths.normalize(toRelPath)
        io(FsMethod.CLAIM, to) {
            // An exclusive rename replaces nothing and makes no folder: a claim stays within one.
            val refused =
                when {
                    existsNow(to) -> FileAlreadyExistsException(to)
                    !existsNow(NpnPaths.parentDir(to)) -> NoSuchFileException(NpnPaths.parentDir(to))
                    else -> null
                }
            if (refused != null) throw refused
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
                else -> fileAt(a) === fileAt(b)
            }
        }
    }

    override suspend fun delete(relPath: String) {
        val norm = NpnPaths.normalize(relPath)
        io(FsMethod.DELETE, norm) { remove(norm, byApp = true) }
    }

    override suspend fun setModifiedTime(
        relPath: String,
        mtimeMillis: Long,
    ) {
        val norm = NpnPaths.normalize(relPath)
        io(FsMethod.STAT, norm) { fileAt(norm).mtime = mtimeMillis }
        touch(norm)
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
        io(FsMethod.IMPORT_EXTERNAL, norm) {
            create(norm, bytes)
            if (isDelivery(source.path)) delivered += norm
        }
    }

    /**
     * Whether an import from this source carries another device's file into the folder (the cloud
     * sync's transfers): what it installs is that device's, not one the app here published.
     */
    var isDelivery: (java.nio.file.Path) -> Boolean = { false }

    private val delivered = HashSet<String>()

    override fun watch(): Flow<FsEvent> = events.asSharedFlow()

    // ---- the external world ---------------------------------------------------------------------

    /**
     * Where [importExternal] reads a source the simulation holds in memory (a watched folder's
     * file); null for a real path.
     */
    var externalSources: ((java.nio.file.Path) -> ByteArray?)? = null

    /** Every file the app removed, by delete or by moving it away: the sync invariants check whose they were. */
    val appRemovals = ArrayList<String>()

    /** Put [bytes] at [relPath] in one step, replacing whatever is there: a rename into place. */
    fun externalWriteAtomic(
        relPath: String,
        bytes: ByteArray,
        mtime: Long = options.stamps.stamp(bytes.size, now()),
    ) {
        val norm = NpnPaths.normalize(relPath)
        if (dirs.containsKey(norm)) remove(norm)
        // The same bytes again (a backup put back over the file it copied) take nothing away.
        files[norm]?.takeIf { sha256Bytes(it.bytes) != sha256Bytes(bytes) }?.let { lost(norm, it, byApp = false) }
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
            val node = files[norm]
            node?.let { lost(norm, it, byApp = false, inPlace = true) }
            if (node == null) {
                if (dirs.containsKey(norm)) remove(norm)
                ensureParents(norm)
                files[norm] = Node(bytes.copyOf(written), options.stamps.stamp(written, now()))
            } else {
                node.bytes = bytes.copyOf(written)
                node.mtime = options.stamps.stamp(written, now())
            }
            touch(norm)
            if (written < bytes.size) delay(intervalMillis)
        }
    }

    /** Change the bytes of the file at [relPath] in place (every hard link sees them), with a new mtime. */
    fun externalOverwriteInPlace(
        relPath: String,
        bytes: ByteArray,
    ) {
        val norm = NpnPaths.normalize(relPath)
        val node = files[norm] ?: return externalWriteAtomic(norm, bytes)
        if (sha256Bytes(node.bytes) != sha256Bytes(bytes)) lost(norm, node, byApp = false, inPlace = true)
        node.bytes = bytes.copyOf()
        node.mtime = options.stamps.stamp(bytes.size, now())
        touch(norm)
    }

    /**
     * Give the file at [relPath] a new mtime with its bytes unchanged, as `touch`, a backup tool
     * that does not keep mtimes, or a sync tool rewriting a file it holds already does.
     */
    fun externalTouch(relPath: String) {
        val norm = NpnPaths.normalize(relPath)
        val node = files[norm] ?: return
        node.mtime = options.stamps.stamp(node.bytes.size, now())
        touch(norm)
    }

    /** Delete [relPath], a file or a whole directory, as another process does. */
    fun externalDelete(relPath: String) {
        val norm = NpnPaths.normalize(relPath)
        if (!existsNow(norm)) return
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
        mtime: Long = options.stamps.stamp(bytes.size, now()),
    ) {
        val norm = NpnPaths.normalize(relPath)
        val temp = SimNames.syncTemp(norm, SyncTool.SYNCTHING)
        externalWriteAtomic(temp, bytes, mtime)
        if (transferMillis > 0) delay(transferMillis)
        if (!files.containsKey(temp)) return
        externalMove(temp, norm)
    }

    // ---- what the simulation inspects ------------------------------------------------------------

    /** Every file and its bytes, for invariants and the rebuild oracle. */
    fun allFiles(): Map<String, ByteArray> = files.mapValues { it.value.bytes }

    fun bytesAt(relPath: String): ByteArray? = files[relPath]?.bytes

    fun mtimeAt(relPath: String): Long? = files[relPath]?.mtime

    /**
     * Every write-once file the app published here (not one that arrived from outside, by the sync
     * tool or the app's cloud sync delivering it).
     */
    val appPublished: Set<String> get() = writeOnce.keys - delivered

    /** The stat of the file at [relPath] as a listing reports it now, with no latency or fault; null without one. */
    fun statAt(relPath: String): FileMeta? = if (files.containsKey(relPath)) metaOf(relPath) else null

    fun isDirectory(relPath: String): Boolean = dirs.containsKey(relPath)

    /**
     * A copy of this filesystem as it stands, with no faults, latency or watcher history, over
     * [clock]: what a rebuilt index sees.
     */
    fun snapshot(clock: () -> Long = now): InMemoryLibraryFileSystem {
        val copy =
            InMemoryLibraryFileSystem(
                now = clock,
                options = SimFsOptions(profile = options.profile, watch = false),
            )
        dirs.forEach { (path, mtime) -> copy.dirs[path] = mtime }
        // Hard links stay links in the copy, so sameFile answers alike.
        val nodes = HashMap<Node, Node>()
        files.forEach { (path, node) ->
            copy.files[path] = nodes.getOrPut(node) { Node(node.bytes.copyOf(), node.mtime) }
        }
        return copy
    }

    /** Whether the app is writing [path] now, so its bytes are not yet what they will be. */
    fun isBeingWritten(path: String): Boolean = path in writing

    // ---- internals ------------------------------------------------------------------------------

    private fun existsNow(norm: String): Boolean = norm.isEmpty() || files.containsKey(norm) || dirs.containsKey(norm)

    private fun fileAt(norm: String): Node {
        files[norm]?.let { return it }
        if (dirs.containsKey(norm)) throw java.io.IOException("Is a directory: $norm")
        throw NoSuchFileException(norm)
    }

    private fun metaOf(norm: String): FileMeta {
        val node = files[norm]
        return FileMeta(
            relPath = norm,
            name = NpnPaths.fileName(norm),
            size = node?.bytes?.size?.toLong() ?: 0L,
            mtimeMillis = node?.mtime ?: dirs[norm] ?: 0L,
            isDirectory = node == null,
        )
    }

    private fun entriesUnder(norm: String): List<FileMeta> {
        val prefix = if (norm.isEmpty()) "" else "$norm/"
        val out = TreeSet<String>()
        out += dirs.keysWithPrefix(prefix)
        out += files.keysWithPrefix(prefix)
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
        files[norm] = Node(bytes, options.stamps.stamp(bytes.size, now()))
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
                publications.left(from)
                if (byApp) {
                    appRemovals += from
                    recordWrite(to, node.bytes)
                    publications.moved(from, to, node.bytes, node.mtime)
                }
            }

            dirs.containsKey(from) -> {
                ensureParents(to)
                val prefix = "$from/"
                (listOf(from) + dirs.keysWithPrefix(prefix)).forEach { dir ->
                    dirs[to + dir.removePrefix(from)] = dirs.remove(dir) ?: now()
                }
                files.keysWithPrefix(prefix).forEach { file ->
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
        publications.left(norm)
        files.remove(norm)?.let { node ->
            if (byApp) appRemovals += norm
            lost(norm, node, byApp)
            touch(norm)
            return
        }
        if (!dirs.containsKey(norm)) return
        val prefix = "$norm/"
        files.keysWithPrefix(prefix).forEach {
            files.remove(it)?.let { node -> lost(it, node, byApp) }
            if (byApp) appRemovals += it
        }
        (listOf(norm) + dirs.keysWithPrefix(prefix)).forEach { dirs.remove(it) }
        touch(norm)
    }

    /** Report a change at [norm] to the watcher, as inotify would (possibly dropped or as an overflow). */
    private fun touch(norm: String) {
        val entries = history.getOrPut(norm) { ArrayDeque() }
        val node = files[norm]
        val hash = node?.let { sha256Bytes(it.bytes) }
        if (node != null && hash != null) publications.saw(norm, hash, node.bytes.size.toLong(), node.mtime)
        val content = hash?.take(HISTORY_ID) ?: if (dirs.containsKey(norm)) "dir" else "gone"
        entries.addLast("t=${now()} ${actor?.name?.lowercase() ?: "external"} -> $content")
        if (entries.size > HISTORY_LENGTH) entries.removeFirst()
        if (!options.watch) return
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
        if (!SimNames.isWriteOnce(norm)) return
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

    /** Whether each `doc-trash` the app publishes records the stat of the file its trash moved. */
    val publications = SimPublications { path -> files[path]?.bytes }

    /** The app's call taking effect right now, or null for the outside world. */
    private var actor: FsMethod? = null

    /**
     * Each time bytes were taken from a path here; never from a file that is none of the library's
     * copies (an import's staging file, a bake's temporary file: [isNoCopy]).
     */
    val losses = Losses()

    /**
     * Whether the file at [path], holding [bytes], is none of the library's copies ([Losses.isNoCopy]).
     * A bake's temporary file is one once its bytes are a document a journal object here names as a
     * relink's target: a bake undone after its relink landed parks the document's bytes there, maybe
     * its only copy, and deleting them then loses the document.
     */
    private fun isNoCopy(
        path: String,
        bytes: ByteArray,
    ): Boolean {
        if (!Losses.isNoCopy(path)) return false
        if (NpnPaths.Temp.kindOf(path) != NpnPaths.Temp.Kind.BAKE) return true
        return sha256Bytes(bytes) !in relinkTargets()
    }

    /** Every document a journal object in this folder relinks another onto. */
    private fun relinkTargets(): Set<String> =
        journalObjects().values.flatMapTo(HashSet()) { bytes ->
            journalEventsIn(bytes).mapNotNull { (it.op as? JournalOp.Relink)?.newDoc }
        }

    /** This folder's journal objects, by path, but for one a sync tool is still writing. */
    private fun journalObjects(): Map<String, ByteArray> =
        files
            .keysWithPrefix("${NpnPaths.JOURNAL_DIR}/")
            .filter { JournalPaths.classify(it) is JournalPath.ObjectV2 && it !in writing }
            .associateWith { files.getValue(it).bytes }

    /** Whether the app holds a fact for each last copy here it deletes ([SimDeleteAuthority]). */
    val deleteAuthority = SimDeleteAuthority(::journalObjects)

    private var syncCalls = 0

    /** Run [block], the sync tool's change to this folder, so what it takes away counts as [Loss.SYNC]. */
    fun <T> bySyncTool(block: () -> T): T {
        syncCalls++
        try {
            return block()
        } finally {
            syncCalls--
        }
    }

    /**
     * Record that [node]'s bytes are leaving [norm]: removed, or replaced there ([inPlace]: the same
     * file, every hard link to it, given other bytes).
     */
    private fun lost(
        norm: String,
        node: Node,
        byApp: Boolean,
        inPlace: Boolean = false,
    ) {
        val cause =
            when {
                byApp && Losses.isKept(norm) -> {
                    Loss.APP_TRASH
                }

                byApp -> {
                    Loss.APP
                }

                syncCalls > 0 -> {
                    Loss.SYNC
                }

                else -> {
                    Loss.OUTSIDE
                }
            }
        if (isNoCopy(norm, node.bytes)) return
        val hash = sha256Bytes(node.bytes)
        val kept =
            files.any { (path, other) ->
                path != norm && !(inPlace && other === node) && !isNoCopy(path, other.bytes) &&
                    sha256Bytes(other.bytes) == hash
            }
        val lastAnywhere = !kept && !heldElsewhere(hash, norm)
        losses.record(hash, LossAt(now(), cause, norm, lastHere = !kept, lastAnywhere = lastAnywhere))
        if (byApp && !kept) deleteAuthority.tookLastCopy(norm, hash, now()) { historyOf(norm) }
    }

    /**
     * Whether a file in another replica's folder, at another path than the one losing them, holds the
     * bytes hashing to the given hash, so a loss here leaves the library a copy; the [SyncSimulation]
     * holding this folder sets it. A copy at the same path does not count: the sync tool carries this
     * folder's change there, so a loss here takes that one too.
     */
    var heldElsewhere: (hash: String, path: String) -> Boolean = { _, _ -> false }

    /** Whether one of the library's copies here ([isNoCopy]), not at [except], holds [hash]'s bytes. */
    fun holdsCopy(
        hash: String,
        except: String,
    ): Boolean =
        files.any { (path, node) ->
            path != except && !isNoCopy(path, node.bytes) && sha256Bytes(node.bytes) == hash
        }

    /** Each path the bytes hashing to [hash] were taken from, with that path's history: what a failure shows. */
    fun lossesAt(hash: String): List<String> =
        losses.pathsOf(hash).map { path ->
            "'$path': " + historyOf(path).joinToString("; ")
        }

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

/**
 * The keys starting with [prefix], in order. They are one range of the sorted map, so a listing
 * costs its own size, not a scan of every path in the library.
 */
private fun <V> TreeMap<String, V>.keysWithPrefix(prefix: String): List<String> =
    tailMap(prefix, true).keys.takeWhile { it.startsWith(prefix) }
