package dev.npn.core.sync

import dev.npn.core.app.AppSessionRig
import dev.npn.core.app.LibrarySessionImpl
import dev.npn.core.fs.sha256Bytes
import kotlinx.coroutines.CoroutineScope
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
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
 */
class DirectoryRemoteStore(
    val root: Path,
    var reportsHashes: Boolean = false,
) : RemoteStore {
    val failDownloads = HashSet<String>()
    val tamperDownloads = HashSet<String>()
    var failList = false
    var downloads = 0
    var uploads = 0
    private val statHooks = HashMap<String, () -> Unit>()

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
        downloads++
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
        uploads++
        val target = root.resolve(path)
        Files.createDirectories(target.parent)
        Files.copy(
            Path.of(fromLocalPath),
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.COPY_ATTRIBUTES,
        )
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
        return RemoteEntry(rel, attrs.size(), attrs.lastModifiedTime().toMillis(), hash)
    }

    private fun sha256(file: Path): String = sha256Bytes(Files.readAllBytes(file))
}

/** A [SyncTransport] that is always available and opens [store] for any remote name. */
class DirectoryTransport(
    val store: DirectoryRemoteStore,
) : SyncTransport {
    override suspend fun availability(): TransportAvailability = TransportAvailability.Available("test")

    override suspend fun listRemotes(): List<String> = listOf("test")

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
            val config: suspend () -> CloudSyncConfig = { CloudSyncConfig(enabled = false, remote = "test:library") }
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
