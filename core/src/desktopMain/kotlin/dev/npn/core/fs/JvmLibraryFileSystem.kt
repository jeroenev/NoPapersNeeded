package dev.npn.core.fs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.name

/**
 * java.nio-backed [LibraryFileSystem] for desktop. All suspend functions run on
 * [Dispatchers.IO]. Writes are atomic where possible (temp file in the same directory +
 * atomic move that fails if the target exists). Never appends or overwrites.
 */
class JvmLibraryFileSystem(private val root: Path) : LibraryFileSystem {

    override val verifiedPublicationCapability: VerifiedPublicationCapability =
        VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

    private fun resolve(relPath: String): Path {
        val norm = NpnPaths.normalize(relPath)
        return if (norm.isEmpty()) root else root.resolve(norm)
    }

    private fun relOf(path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    override suspend fun listAll(): List<FileMeta> = listAllFrom(root)

    override suspend fun listAllUnder(relPath: String): List<FileMeta> = listAllFrom(resolve(relPath))

    private suspend fun listAllFrom(start: Path): List<FileMeta> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileMeta>()
        if (!Files.exists(start)) return@withContext out
        // followLinks = false: do not traverse symlink cycles.
        Files.walkFileTree(start, emptySet<FileVisitOption>(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != start) out.add(metaOf(dir, attrs))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                // Skip symlinks entirely (attrs are of the link target when followLinks; here
                // followLinks=false so attrs describe the link itself).
                if (!attrs.isSymbolicLink) out.add(metaOf(file, attrs))
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE
        })
        out
    }

    private fun metaOf(path: Path, attrs: BasicFileAttributes): FileMeta = FileMeta(
        relPath = relOf(path),
        name = path.name,
        size = if (attrs.isDirectory) 0L else attrs.size(),
        mtimeMillis = attrs.lastModifiedTime().toMillis(),
        isDirectory = attrs.isDirectory,
    )

    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(resolve(relPath))
    }

    override suspend fun stat(relPath: String): FileMeta? = withContext(Dispatchers.IO) {
        val p = resolve(relPath)
        if (!Files.exists(p)) return@withContext null
        val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
        metaOf(p, attrs)
    }

    override suspend fun readBytes(relPath: String): ByteArray = withContext(Dispatchers.IO) {
        Files.readAllBytes(resolve(relPath))
    }

    override suspend fun writeBytesOnce(relPath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolve(relPath)
        if (Files.exists(target)) {
            error("Target already exists: $relPath")
        }
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        // Write to a temp file in the same directory, then atomically move failing if the
        // target appeared meanwhile (no REPLACE_EXISTING).
        val tmp = Files.createTempFile(parent ?: root, ".npn-tmp-", ".part")
        try {
            Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                error("Target already exists: $relPath")
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                // Fall back to a non-atomic move that still refuses to overwrite.
                Files.move(tmp, target)
            }
            Unit
        } finally {
            // Clean up the temp file if it survived (move failed).
            try {
                Files.deleteIfExists(tmp)
            } catch (_: IOException) {
            }
        }
        Unit
    }

    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ): VerifiedPublicationResult = withContext(Dispatchers.IO) {
        val staging = resolve(stagingPath)
        val canonical = resolve(canonicalPath)
        require(staging.parent == canonical.parent) { "Object staging must be in the canonical bucket" }
        check(Files.isRegularFile(staging)) { "Staging object is missing: $stagingPath" }
        check(hashPath(staging) == expectedHash) { "Staging object hash mismatch: $stagingPath" }
        val result = try {
            // Creating a hard link installs one complete directory entry and cannot replace an
            // existing target. Providers without this primitive must use the v1 writer.
            Files.createLink(canonical, staging)
            VerifiedPublicationResult.PUBLISHED
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            check(Files.isRegularFile(canonical) && hashPath(canonical) == expectedHash) {
                "Canonical object exists with invalid bytes: $canonicalPath"
            }
            VerifiedPublicationResult.ALREADY_PRESENT
        } catch (e: UnsupportedOperationException) {
            throw VerifiedPublicationUnavailableException("Atomic no-replace publication is unsupported", e)
        } catch (e: IOException) {
            if (Files.isRegularFile(canonical)) {
                check(hashPath(canonical) == expectedHash) {
                    "Canonical object appeared with invalid bytes: $canonicalPath"
                }
                VerifiedPublicationResult.ALREADY_PRESENT
            } else {
                throw VerifiedPublicationUnavailableException("Atomic no-replace publication failed", e)
            }
        }
        check(hashPath(canonical) == expectedHash) { "Published object hash mismatch: $canonicalPath" }
        Files.deleteIfExists(staging)
        result
    }

    override suspend fun move(fromRelPath: String, toRelPath: String) = withContext(Dispatchers.IO) {
        val from = resolve(fromRelPath)
        val to = resolve(toRelPath)
        if (Files.exists(to)) {
            error("Target already exists: $toRelPath")
        }
        val parent = to.parent
        if (parent != null) Files.createDirectories(parent)
        // No REPLACE_EXISTING: move throws FileAlreadyExistsException if target appears.
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(from, to)
        }
        Unit
    }

    override suspend fun delete(relPath: String) = withContext(Dispatchers.IO) {
        val p = resolve(relPath)
        if (Files.isDirectory(p)) {
            // Recursive delete, deepest first.
            Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        } else {
            Files.deleteIfExists(p)
        }
        Unit
    }

    override suspend fun mkdirs(relPath: String) = withContext(Dispatchers.IO) {
        Files.createDirectories(resolve(relPath))
        Unit
    }

    override suspend fun sha256(relPath: String): String = withContext(Dispatchers.IO) {
        hashPath(resolve(relPath))
    }

    private fun hashPath(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(64 * 1024)
                while (dis.read(buffer) != -1) {
                    // read feeds the digest
                }
            }
        }
        return digest.digest().toHexLower()
    }

    override suspend fun importExternal(source: ExternalSource, toRelPath: String) = withContext(Dispatchers.IO) {
        val target = resolve(toRelPath)
        if (Files.exists(target)) {
            error("Target already exists: $toRelPath")
        }
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        // COPY without REPLACE_EXISTING: fails if target appears concurrently.
        Files.copy(source.path, target, StandardCopyOption.COPY_ATTRIBUTES)
        Unit
    }

    override suspend fun copyOut(relPath: String, target: ExternalSource) = withContext(Dispatchers.IO) {
        val parent = target.path.parent
        if (parent != null) Files.createDirectories(parent)
        Files.copy(resolve(relPath), target.path, StandardCopyOption.REPLACE_EXISTING)
        Unit
    }

    override fun watch(): Flow<FsEvent> = callbackFlow {
        val watcher: WatchService = root.fileSystem.newWatchService()
        val keys = HashMap<WatchKey, Path>()

        fun register(dir: Path) {
            try {
                val key = dir.register(
                    watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
                keys[key] = dir
            } catch (_: IOException) {
            }
        }

        fun registerRecursive(start: Path) {
            if (!Files.exists(start)) return
            Files.walkFileTree(start, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    register(dir)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            })
        }

        registerRecursive(root)

        val scope = CoroutineScope(coroutineContext)
        val job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val key = try {
                    watcher.take()
                } catch (_: InterruptedException) {
                    break
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                    break
                }
                val dir = keys[key]
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        trySend(FsEvent.Changed(null))
                        continue
                    }
                    @Suppress("UNCHECKED_CAST")
                    val ctx = event.context() as? Path
                    val child = if (dir != null && ctx != null) dir.resolve(ctx) else null
                    if (child != null && kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                        // Newly created directory: start watching it (and anything already inside).
                        registerRecursive(child)
                    }
                    val rel = child?.let { relOf(it) }
                    trySend(FsEvent.Changed(rel))
                }
                val valid = key.reset()
                if (!valid) {
                    keys.remove(key)
                    if (keys.isEmpty()) break
                }
            }
        }

        awaitClose {
            job.cancel()
            try {
                watcher.close()
            } catch (_: IOException) {
            }
        }
    }
}
