package dev.npn.core.fs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.name

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
 * [externalIo] opens the platform's external files for import (see [ExternalIo]).
 */
class JvmLibraryFileSystem internal constructor(
    private val root: Path,
    private val externalIo: ExternalIo,
    private val linker: NoReplaceLinker,
) : LibraryFileSystem {
    constructor(
        root: Path,
        externalIo: ExternalIo,
    ) : this(root, externalIo, HardLinkLinker)

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

    /**
     * [path] relative to the root, its names joined with `/`. Where `\` is no separator (Linux,
     * Android) a name holding one stays one segment, which [NpnPaths.isSafe] refuses, rather than
     * turning into a path that does not exist.
     */
    private fun relOf(path: Path): String = root.relativize(path).joinToString("/")

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
            try {
                val attrs = Files.readAttributes(p, BasicFileAttributes::class.java)
                metaOf(p, attrs)
            } catch (ignored: NoSuchFileException) {
                // Gone before or during the read (a sync tool moving it away): as in the SAF impl,
                // no metadata rather than a failure for what does not exist.
                null
            }
        }

    override suspend fun readBytes(relPath: String): ByteArray =
        withContext(Dispatchers.IO) {
            Files.readAllBytes(resolve(relPath))
        }

    override suspend fun readTail(
        relPath: String,
        maxBytes: Int,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            Files.newByteChannel(resolve(relPath)).use { channel ->
                val start = maxOf(0L, channel.size() - maxBytes)
                val buffer = ByteBuffer.allocate((channel.size() - start).toInt())
                channel.position(start)
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // read fills the buffer
                }
                buffer.array().copyOf(buffer.position())
            }
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
        val tmp = Files.createTempFile(parent ?: root, NpnPaths.Temp.WRITE_PREFIX, NpnPaths.Temp.PART)
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
    ) = withContext(Dispatchers.IO) {
        val staging = resolve(stagingPath)
        val canonical = resolve(canonicalPath)
        require(staging.parent == canonical.parent) { "Object staging must be in the canonical bucket" }
        check(Files.isRegularFile(staging)) { "Staging object is missing: $stagingPath" }
        check(hashPath(staging) == expectedHash) { "Staging object hash mismatch: $stagingPath" }
        try {
            publishObject(staging, canonical)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            check(Files.isRegularFile(canonical) && hashPath(canonical) == expectedHash) {
                "Canonical object exists with invalid bytes: $canonicalPath"
            }
        }
        check(hashPath(canonical) == expectedHash) { "Published object hash mismatch: $canonicalPath" }
        Files.deleteIfExists(staging)
        Unit
    }

    /**
     * Install the verified [staging] object as [canonical] without ever replacing an existing
     * entry. A hard link installs one complete directory entry and cannot replace; a filesystem
     * without hard links (Android's shared-storage FUSE mount, exFAT, FAT) gets [NoReplaceMove]'s
     * exclusive rename of the staging file within its bucket, which refuses an existing target just
     * as atomically, including one a sync client installed meanwhile. A filesystem with neither
     * fails with [VerifiedPublicationUnavailableException]. Throws
     * [java.nio.file.FileAlreadyExistsException] when the canonical object is already there.
     */
    private fun publishObject(
        staging: Path,
        canonical: Path,
    ) {
        // mayLatch = true (the default): staging and canonical always share a bucket directory, so
        // a refusal here is the filesystem's, safe to remember for the rest of the session.
        if (linkNoReplace(staging, canonical)) return
        // A generic failure with the target in place is a lost race, not a missing primitive.
        if (Files.isRegularFile(canonical)) throw java.nio.file.FileAlreadyExistsException(canonical.toString())
        NoReplaceMove.move(staging, canonical)
    }

    override val supportsDocumentRelocation: Boolean get() = true

    override suspend fun claimDocument(
        fromRelPath: String,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        NoReplaceMove.move(resolve(fromRelPath), resolve(toRelPath))
    }

    override suspend fun sameFile(
        first: String,
        second: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            Files.isSameFile(resolve(first), resolve(second))
        }

    override suspend fun copyOnce(
        fromRelPath: String,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        val to = resolve(toRelPath)
        Files.createDirectories(to.parent)
        Files.copy(resolve(fromRelPath), to)
        Unit
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
     * Filesystems with no hard links use [NoReplaceMove]'s exclusive rename. A filesystem without
     * either primitive, or a move across mount points, fails without touching either file.
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
            NoReplaceMove.move(from, to)
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

    private fun hashPath(path: Path): String = Files.newInputStream(path).use { it.sha256Hex() }

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
        val tmp = Files.createTempFile(parent ?: root, NpnPaths.Temp.WRITE_PREFIX, NpnPaths.Temp.PART)
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

    override fun watch(): Flow<FsEvent> =
        watchDirs(root, recursive = true).map { changed -> FsEvent(changed?.let(::relOf)) }
}
