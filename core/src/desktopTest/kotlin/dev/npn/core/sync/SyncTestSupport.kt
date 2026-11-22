package dev.npn.core.sync

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

/**
 * A [RemoteStore] over a plain directory, standing in for an rclone remote. Behaves like a dumb
 * cloud backend: copies replace, moves create parents, listings report what is there. Reports
 * SHA-256 only when [reportsHashes], so both identification paths (native hash, download) run.
 * [failDownloads] makes downloads of the named paths throw halfway, for interruption tests.
 * [tamperDownloads] delivers the named paths with extra bytes appended (a size that no longer
 * matches the listing); [failList] makes the listing itself throw; [onceBeforeStat] runs a hook
 * right before the next `stat` of one path, to change the remote between planning and acting.
 * [failUploads] leaves half an object at the target and throws (a connection cut mid-upload);
 * [landThenFailUploads] lands the whole object and then throws, as a process killed between the
 * upload and the manifest write does.
 */
class DirectoryRemoteStore(
    val root: Path,
    var reportsHashes: Boolean = false,
) : RemoteStore {
    /** Report Dropbox's content hash in listings, as rclone's Dropbox backend does. */
    var reportsDropboxHash = false
    val failDownloads = HashSet<String>()
    val tamperDownloads = HashSet<String>()
    val failUploads = HashSet<String>()
    val landThenFailUploads = HashSet<String>()
    var failList = false

    // A pass transfers several files at once, on more than one thread.
    private val downloadCount = AtomicInteger()
    private val uploadCount = AtomicInteger()
    private val downloadedPaths = ConcurrentLinkedQueue<String>()
    val downloads: Int get() = downloadCount.get()
    val uploads: Int get() = uploadCount.get()

    /** Every path downloaded, in order. */
    val downloaded: List<String> get() = downloadedPaths.toList()
    private val statHooks = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    init {
        Files.createDirectories(root)
    }

    /** Run [action] once, right before the next `stat` of [path] (the engine's guard check). */
    fun onceBeforeStat(
        path: String,
        action: () -> Unit,
    ) {
        statHooks[path] = action
    }

    override suspend fun list(): List<RemoteEntry> {
        if (failList) throw IOException("listing failed: remote unreachable")
        val out = ArrayList<RemoteEntry>()
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }.forEach { out += entry(it) }
        }
        return out.sortedBy { it.path }
    }

    override suspend fun stat(path: String): RemoteEntry? {
        statHooks.remove(path)?.invoke()
        val file = root.resolve(path)
        return if (Files.isRegularFile(file)) entry(file) else null
    }

    override suspend fun download(
        path: String,
        toLocalPath: String,
    ) {
        downloadCount.incrementAndGet()
        downloadedPaths += path
        val source = root.resolve(path)
        if (path in failDownloads) {
            // Half a file, then the wire drops.
            val bytes = Files.readAllBytes(source)
            Files.write(Path.of(toLocalPath), bytes.copyOf(bytes.size / 2))
            throw IOException("connection reset while downloading $path")
        }
        if (path in tamperDownloads) {
            // The bytes that arrive are not the bytes the listing described.
            Files.write(Path.of(toLocalPath), Files.readAllBytes(source) + "garbage".encodeToByteArray())
            return
        }
        Files.copy(
            source,
            Path.of(toLocalPath),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    }

    override suspend fun upload(
        fromLocalPath: String,
        path: String,
    ) {
        uploadCount.incrementAndGet()
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        if (path in failUploads) {
            // Half a file, then the process dies or the wire drops.
            val bytes = Files.readAllBytes(Path.of(fromLocalPath))
            Files.write(target, bytes.copyOf(bytes.size / 2))
            throw IOException("connection reset while uploading $path")
        }
        Files.copy(
            Path.of(fromLocalPath),
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        if (path in landThenFailUploads) {
            // The object is up; the pass dies before it can record the identity in its manifest.
            throw IOException("process lost after $path landed")
        }
    }

    override suspend fun move(
        from: String,
        to: String,
    ) {
        val target = root.resolve(to)
        Files.createDirectories(target.parent)
        // rclone's move overwrites the destination, so the fake must too: a removed destination
        // guard has to surface as a clobbered file, not as a throw that masks it.
        Files.move(root.resolve(from), target, StandardCopyOption.REPLACE_EXISTING)
    }

    override suspend fun delete(path: String) {
        if (!Files.deleteIfExists(root.resolve(path))) throw IOException("object not found: $path")
    }

    /** Drop a file straight into the remote, as a foreign tool would. */
    fun put(
        path: String,
        bytes: ByteArray,
    ) {
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    fun exists(path: String): Boolean = Files.isRegularFile(root.resolve(path))

    fun bytes(path: String): ByteArray = Files.readAllBytes(root.resolve(path))

    /** Whether the remote file at [path] holds exactly [expected], for byte-identity assertions. */
    fun fileBytesEqual(
        path: String,
        expected: ByteArray,
    ): Boolean = bytes(path).contentEquals(expected)

    /** Backdate (or forward-date) a remote file, as a trash entry that has sat there a while. */
    fun setMtime(
        path: String,
        mtimeMillis: Long,
    ) {
        Files.setLastModifiedTime(root.resolve(path), FileTime.fromMillis(mtimeMillis))
    }

    fun paths(): Set<String> =
        Files.walk(root).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .map {
                    it
                        .relativeTo(
                            root,
                        ).toString()
                        .replace('\\', '/')
                }.toList()
                .toSet()
        }

    private fun entry(file: Path): RemoteEntry {
        val attrs = Files.readAttributes(file, BasicFileAttributes::class.java)
        val rel = file.relativeTo(root).toString().replace('\\', '/')
        val hash = if (reportsHashes) sha256(file) else null
        val dropbox =
            if (reportsDropboxHash) {
                DropboxContentHash()
                    .apply {
                        Files.readAllBytes(file).let { update(it, it.size) }
                    }.hex()
            } else {
                null
            }
        return RemoteEntry(rel, attrs.size(), attrs.lastModifiedTime().toMillis(), hash, dropbox)
    }

    private fun sha256(file: Path): String = sha256Bytes(Files.readAllBytes(file))
}

/** A [SyncTransport] that is always available and opens [store] for any remote name. */
class DirectoryTransport(
    val store: DirectoryRemoteStore,
) : SyncTransport {
    override suspend fun availability(): TransportAvailability = TransportAvailability.Available("test")

    override fun open(remote: String): RemoteStore = store
}

/** Two libraries in separate folders sharing one directory-backed remote. */
internal class SyncPair private constructor(
    val remote: DirectoryRemoteStore,
    val a: AppSessionRig,
    val b: AppSessionRig,
) {
    suspend fun close() {
        a.close()
        b.close()
        remote.root.toFile().deleteRecursively()
    }

    companion object {
        suspend fun create(
            scope: CoroutineScope,
            reportsHashes: Boolean = false,
        ): SyncPair {
            val remote = DirectoryRemoteStore(Files.createTempDirectory("npn-sync-remote"), reportsHashes)
            val transport = DirectoryTransport(remote)
            val config = CloudSyncConfig(enabled = false, remote = "test:library")
            val a =
                AppSessionRig.create(
                    scope,
                    syncTransport = transport,
                    cloudSync = config,
                    installationId = "install-a",
                )
            val b =
                AppSessionRig.create(
                    scope,
                    syncTransport = transport,
                    cloudSync = config,
                    installationId = "install-b",
                )
            return SyncPair(remote, a, b)
        }
    }
}

/**
 * Two or more libraries in separate folders sharing one directory-backed remote, each its own
 * device and its own manifest. [join] adds one as a fresh device does, and [retire] takes one away
 * as a dead laptop does, for the "third replica" and "restore" scenarios.
 */
internal class SyncCluster private constructor(
    private val scope: CoroutineScope,
    val remote: DirectoryRemoteStore,
    private val transport: DirectoryTransport,
    private val config: CloudSyncConfig,
) {
    private val joined = LinkedHashMap<String, AppSessionRig>()

    /** The live devices, in join order. */
    val rigs: List<AppSessionRig> get() = joined.values.toList()

    /** A new, empty library on this device, sharing the remote, as another install would be. */
    suspend fun join(installationId: String): AppSessionRig {
        check(installationId !in joined) { "device already joined: $installationId" }
        return AppSessionRig
            .create(
                scope,
                syncTransport = transport,
                cloudSync = config,
                installationId = installationId,
            ).also { joined[installationId] = it }
    }

    /** Close and forget [rig], as a device that left for good. */
    suspend fun retire(rig: AppSessionRig) {
        joined.values.removeIf { it === rig }
        rig.close()
    }

    // Named lookups, so retiring a device cannot silently turn one test device into another.
    val a: AppSessionRig get() = joined.getValue("install-a")
    val b: AppSessionRig get() = joined.getValue("install-b")

    suspend fun close() {
        for (rig in rigs) {
            // Cleanup must not mask the test's own failure; a close that throws still leaves the
            // next test with fresh temp directories.
            runCatchingCancellable { rig.close() }
        }
        joined.clear()
        remote.root.toFile().deleteRecursively()
    }

    companion object {
        suspend fun open(scope: CoroutineScope): SyncCluster {
            val remote = DirectoryRemoteStore(Files.createTempDirectory("npn-sync-remote"))
            val cluster =
                SyncCluster(
                    scope,
                    remote,
                    DirectoryTransport(remote),
                    CloudSyncConfig(enabled = false, remote = "test:library"),
                )
            cluster.join("install-a")
            cluster.join("install-b")
            return cluster
        }
    }
}

/**
 * Run a pass on each of [members] in order, again until a whole round changes nothing. Returns the
 * number of rounds taken. A remote-backed pass reaches quiescence once every replica has seen the
 * others' metadata. A pass with an error, or (unless [allowConflicts]) a conflict, is not quiescent:
 * a scenario that promises clean convergence must not pass over failed or stuck work.
 */
internal suspend fun SyncCluster.converge(
    members: List<AppSessionRig> = rigs,
    maxRounds: Int = 6,
    allowConflicts: Boolean = false,
): Int {
    repeat(maxRounds) { round ->
        var changed = false
        for (rig in members.toList()) {
            val report = rig.session.syncCompleted()
            if (report.changedAnything) changed = true
            check(report.errors.isEmpty()) { "pass failed: ${report.errors}" }
            check(allowConflicts || report.conflicts.isEmpty()) { "unexpected conflicts: ${report.conflicts}" }
        }
        if (!changed) return round + 1
    }
    error("replicas did not converge in $maxRounds rounds")
}

/** Run a manual pass and require it to have completed; returns its report. */
internal suspend fun LibrarySessionImpl.syncCompleted(): SyncReport {
    val outcome = sync.syncNow()
    check(outcome is SyncOutcome.Completed) { "sync did not complete: $outcome" }
    return outcome.report
}

/** The file name of the sole PDF matching [predicate] under [dir], for asserting on trash names. */
fun soleFileUnder(
    dir: Path,
    predicate: (Path) -> Boolean = { true },
): Path? =
    if (!Files.isDirectory(dir)) {
        null
    } else {
        Files.list(dir).use { stream -> stream.filter { it.isRegularFile() && predicate(it) }.toList().singleOrNull() }
    }

fun Path.fileName(): String = name
