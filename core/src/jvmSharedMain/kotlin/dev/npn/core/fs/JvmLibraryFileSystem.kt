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
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.io.path.name

/**
 * How [JvmLibraryFileSystem.publishVerifiedObjectOnce] installs a hash-verified staging object
 * under its canonical name when the filesystem has no usable `link(2)`.
 */
enum class ObjectPublication {
    /**
     * Hard link or nothing: a filesystem without `link(2)` gets a
     * [VerifiedPublicationUnavailableException], and the journal stays on the v1 writer for the
     * session. The desktop default, where every supported filesystem has hard links.
     */
    HARD_LINK_ONLY,

    /**
     * Hard link, else a plain no-replace rename of the verified staging file within its bucket.
     * Android's shared storage is a FUSE mount that refuses `link(2)` with EPERM, so this is the
     * best primitive there. It is safe for the same reason the SAF implementation's rename is:
     * objects are content-addressed and verified in full before the rename, so the published file
     * is never torn, and a target that appears in the window between the existence check and the
     * rename holds the same bytes by construction.
     */
    HARD_LINK_OR_RENAME,
}

/** The one no-replace primitive, abstracted so tests can simulate a filesystem without `link(2)`. */
internal fun interface NoReplaceLinker {
    /**
     * Install [to] as a second name for [from]. Returns false when the filesystem has no usable
     * hard links; throws [java.nio.file.FileAlreadyExistsException] when [to] already exists.
     */
    fun link(
        from: Path,
        to: Path,
    ): Boolean
}

internal object HardLinkLinker : NoReplaceLinker {
    override fun link(
        from: Path,
        to: Path,
    ): Boolean =
        try {
            Files.createLink(to, from)
            true
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            throw e
        } catch (_: UnsupportedOperationException) {
            false
        } catch (_: IOException) {
            // EXDEV, EPERM on a hard-link-less filesystem, and anything else a plain move can
            // report for itself with a better diagnostic.
            false
        }
}

/**
 * java.nio-backed [LibraryFileSystem] over a plain directory: the desktop library, and Android's
 * default (non-SAF) library. All suspend functions run on [Dispatchers.IO]. Writes stage a temp
 * file in the same directory and then install it under its final name with a primitive that
 * cannot replace an existing entry ([atomicMoveNoReplace]). Never appends or overwrites.
 *
 * [externalIo] opens the platform's external files for import/export (see [ExternalIo]);
 * [publication] picks the fallback when the filesystem has no hard links (see [ObjectPublication]).
 */
class JvmLibraryFileSystem internal constructor(
    private val root: Path,
    private val externalIo: ExternalIo,
    private val publication: ObjectPublication,
    private val linker: NoReplaceLinker,
) : LibraryFileSystem {
    constructor(
        root: Path,
        externalIo: ExternalIo = defaultExternalIo(),
        publication: ObjectPublication = ObjectPublication.HARD_LINK_ONLY,
    ) : this(root, externalIo, publication, HardLinkLinker)

    override val verifiedPublicationCapability: VerifiedPublicationCapability =
        VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

    /**
     * Set once a same-bucket link in [publishObject] has come back false, meaning the filesystem
     * itself has no usable `link(2)` (checked there because those links never cross a mount point,
     * so a refusal is a property of the filesystem, not of the pair). [move] and the other
     * call sites can cross into a bind-mounted or otherwise separate-device subtree, where a link
     * failure (EXDEV) says nothing about the filesystem in general; they consult this latch to skip
     * a doomed attempt once it is known, but never set it themselves ([linkNoReplace]'s
     * `mayLatch = false`).
     */
    @Volatile
    private var linksUnavailable = false

    private fun resolve(relPath: String): Path {
        val norm = NpnPaths.normalize(relPath)
        return if (norm.isEmpty()) root else root.resolve(norm)
    }

    private fun relOf(path: Path): String = root.relativize(path).toString().replace('\\', '/')

    override suspend fun listAll(): List<FileMeta> = listAllFrom(root)

    /** [listAll] without the `.npn/` metadata subtree, for indexing/rescan walks. */
    override suspend fun listAllIndexable(): List<FileMeta> = listAllFrom(root, skipNpn = true)

    override suspend fun listAllUnder(relPath: String): List<FileMeta> = listAllFrom(resolve(relPath))

    private suspend fun listAllFrom(
        start: Path,
        skipNpn: Boolean = false,
    ): List<FileMeta> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<FileMeta>()
            if (!Files.exists(start)) return@withContext out
            // followLinks = false: do not traverse symlink cycles.
            val visitor =
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        // The app's `.npn/` metadata tree (journal, OCR sidecars, trash) is never
                        // indexable; skip it in the full-library walk so a rescan does not traverse
                        // it twice (journal reconcile also walks `.npn/journal` separately).
                        if (skipNpn && isRootLevelNpn(dir)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (dir != start) out.add(metaOf(dir, attrs))
                        return FileVisitResult.CONTINUE
                    }

                    private fun isRootLevelNpn(dir: Path): Boolean =
                        start == root && dir != start && dir.name == NpnPaths.NPN_DIR

                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        // Skip symlinks entirely (attrs are of the link target when followLinks; here
                        // followLinks=false so attrs describe the link itself).
                        if (!attrs.isSymbolicLink) out.add(metaOf(file, attrs))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException,
                    ): FileVisitResult = FileVisitResult.CONTINUE
                }
            Files.walkFileTree(start, emptySet<FileVisitOption>(), Int.MAX_VALUE, visitor)
            out
        }

    private fun metaOf(
        path: Path,
        attrs: BasicFileAttributes,
    ): FileMeta =
        FileMeta(
            relPath = relOf(path),
            name = path.name,
            size = if (attrs.isDirectory) 0L else attrs.size(),
            mtimeMillis = attrs.lastModifiedTime().toMillis(),
            isDirectory = attrs.isDirectory,
        )

    override suspend fun exists(relPath: String): Boolean =
        withContext(Dispatchers.IO) {
            Files.exists(resolve(relPath))
        }

    override suspend fun stat(relPath: String): FileMeta? =
        withContext(Dispatchers.IO) {
            val p = resolve(relPath)
            if (!Files.exists(p)) return@withContext null
            val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
            metaOf(p, attrs)
        }

    override suspend fun readBytes(relPath: String): ByteArray =
        withContext(Dispatchers.IO) {
            Files.readAllBytes(resolve(relPath))
        }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val target = resolve(relPath)
        if (Files.exists(target)) {
            error("Target already exists: $relPath")
        }
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        // Write to a temp file in the same directory, then install it under the final name,
        // which fails if the target appeared meanwhile.
        val tmp = Files.createTempFile(parent ?: root, ".npn-tmp-", ".part")
        try {
            Files.write(tmp, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            try {
                atomicMoveNoReplace(tmp, target)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                error("Target already exists: $relPath")
            }
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
    ): VerifiedPublicationResult =
        withContext(Dispatchers.IO) {
            val staging = resolve(stagingPath)
            val canonical = resolve(canonicalPath)
            require(staging.parent == canonical.parent) { "Object staging must be in the canonical bucket" }
            check(Files.isRegularFile(staging)) { "Staging object is missing: $stagingPath" }
            check(hashPath(staging) == expectedHash) { "Staging object hash mismatch: $stagingPath" }
            val result =
                try {
                    publishObject(staging, canonical)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    check(Files.isRegularFile(canonical) && hashPath(canonical) == expectedHash) {
                        "Canonical object exists with invalid bytes: $canonicalPath"
                    }
                    VerifiedPublicationResult.ALREADY_PRESENT
                }
            check(hashPath(canonical) == expectedHash) { "Published object hash mismatch: $canonicalPath" }
            Files.deleteIfExists(staging)
            result
        }

    /**
     * Install the verified [staging] object as [canonical] without ever replacing an existing
     * entry. A hard link installs one complete directory entry and cannot replace; without hard
     * links the behaviour follows [publication]. Throws [java.nio.file.FileAlreadyExistsException]
     * when the canonical object is already there.
     */
    private fun publishObject(
        staging: Path,
        canonical: Path,
    ): VerifiedPublicationResult {
        // mayLatch = true (the default): staging and canonical always share a bucket directory, so
        // a refusal here is the filesystem's, safe to remember for the rest of the session.
        if (linkNoReplace(staging, canonical)) return VerifiedPublicationResult.PUBLISHED
        // A generic failure with the target in place is a lost race, not a missing primitive.
        if (Files.isRegularFile(canonical)) throw java.nio.file.FileAlreadyExistsException(canonical.toString())
        return when (publication) {
            ObjectPublication.HARD_LINK_ONLY -> {
                throw VerifiedPublicationUnavailableException(
                    "Atomic no-replace publication is unsupported on this filesystem: $canonical",
                )
            }

            ObjectPublication.HARD_LINK_OR_RENAME -> {
                // Plain move, never ATOMIC_MOVE: rename(2) would replace silently, while Files.move
                // refuses an existing target (see atomicMoveNoReplace for the residual window).
                Files.move(staging, canonical)
                VerifiedPublicationResult.PUBLISHED
            }
        }
    }

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        val from = resolve(fromRelPath)
        val to = resolve(toRelPath)
        if (Files.exists(to)) {
            error("Target already exists: $toRelPath")
        }
        val parent = to.parent
        if (parent != null) Files.createDirectories(parent)
        try {
            atomicMoveNoReplace(from, to)
        } catch (e: java.nio.file.FileAlreadyExistsException) {
            error("Target already exists: $toRelPath")
        }
        Unit
    }

    /**
     * Move [from] onto [to] so that an existing [to] can never be replaced, throwing
     * [java.nio.file.FileAlreadyExistsException] instead.
     *
     * [StandardCopyOption.ATOMIC_MOVE] is `rename(2)`, which silently replaces the target, and a
     * plain [Files.move] only stats the target before renaming — so neither refuses a target that
     * appears while the caller is staging its bytes. `link(2)` does: it installs a second name for
     * the same inode and fails with EEXIST as one indivisible step. It is the primitive
     * [publishVerifiedObjectOnce] already relies on. Unlinking [from] afterwards is outside that
     * step, so a crash in between leaves a spare copy at [from] rather than a lost object — the
     * trade the write-once invariant asks for.
     *
     * Filesystems with no hard links, and cross-device moves, fall back to a plain no-replace
     * [Files.move]; ATOMIC_MOVE is never used, because it cannot refuse.
     */
    private fun atomicMoveNoReplace(
        from: Path,
        to: Path,
    ) {
        // mayLatch = false: from/to can straddle a bind-mounted or otherwise separate-device
        // subtree (unlike publishObject's bucket-local links), so a refusal here must not be
        // remembered as "this filesystem has no hard links" — see linksUnavailable.
        if (linkNoReplace(from, to, mayLatch = false)) {
            Files.delete(from)
        } else {
            Files.move(from, to)
        }
    }

    /** Hard-link [from] as [to], or return false when this filesystem has no usable `link(2)`. */
    private fun linkNoReplace(
        from: Path,
        to: Path,
        mayLatch: Boolean = true,
    ): Boolean {
        if (linksUnavailable) return false
        val linked = linker.link(from, to)
        if (!linked && mayLatch) linksUnavailable = true
        return linked
    }

    override suspend fun delete(relPath: String) =
        withContext(Dispatchers.IO) {
            val p = resolve(relPath)
            if (Files.isDirectory(p)) {
                // Recursive delete, deepest first.
                Files.walkFileTree(
                    p,
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(
                            file: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            Files.deleteIfExists(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            dir: Path,
                            exc: IOException?,
                        ): FileVisitResult {
                            Files.deleteIfExists(dir)
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            } else {
                Files.deleteIfExists(p)
            }
            Unit
        }

    override suspend fun mkdirs(relPath: String) =
        withContext(Dispatchers.IO) {
            Files.createDirectories(resolve(relPath))
            Unit
        }

    override suspend fun sha256(relPath: String): String =
        withContext(Dispatchers.IO) {
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

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        val target = resolve(toRelPath)
        if (Files.exists(target)) {
            error("Target already exists: $toRelPath")
        }
        val parent = target.parent
        if (parent != null) Files.createDirectories(parent)
        // Stream into a same-directory temp file, then install it no-replace, so a partial PDF is
        // never visible at the final path and a target that appears meanwhile is not replaced.
        val tmp = Files.createTempFile(parent ?: root, ".npn-tmp-", ".part")
        try {
            externalIo.openInput(source).use { input ->
                Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use {
                    input.copyTo(it, STREAM_BUFFER_SIZE)
                }
            }
            try {
                atomicMoveNoReplace(tmp, target)
            } catch (e: java.nio.file.FileAlreadyExistsException) {
                error("Target already exists: $toRelPath")
            }
        } finally {
            try {
                Files.deleteIfExists(tmp)
            } catch (_: IOException) {
            }
        }
        Unit
    }

    override suspend fun copyOut(
        relPath: String,
        target: ExternalSource,
    ) = withContext(Dispatchers.IO) {
        Files.newInputStream(resolve(relPath)).use { input ->
            // The external target belongs to the caller and may have existed before this copy;
            // close it on failure but never delete it.
            externalIo.openOutput(target).use { output -> input.copyTo(output, STREAM_BUFFER_SIZE) }
        }
        Unit
    }

    override fun watch(): Flow<FsEvent> =
        callbackFlow {
            val watcher: WatchService = root.fileSystem.newWatchService()
            val keys = HashMap<WatchKey, Path>()

            fun register(dir: Path) {
                try {
                    val key =
                        dir.register(
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
                Files.walkFileTree(
                    start,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            dir: Path,
                            attrs: BasicFileAttributes,
                        ): FileVisitResult {
                            register(dir)
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            file: Path,
                            exc: IOException,
                        ): FileVisitResult = FileVisitResult.CONTINUE
                    },
                )
            }

            registerRecursive(root)

            val scope = CoroutineScope(coroutineContext)
            val job =
                scope.launch(Dispatchers.IO) {
                    while (isActive) {
                        val key =
                            try {
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
                            val createdDirectory =
                                child != null &&
                                    kind == StandardWatchEventKinds.ENTRY_CREATE &&
                                    Files.isDirectory(child)
                            if (createdDirectory) {
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

private const val STREAM_BUFFER_SIZE = 64 * 1024
