package dev.npn.core.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Storage Access Framework backed [LibraryFileSystem] for Android. Given a tree uri from
 * `ACTION_OPEN_DOCUMENT_TREE`, it exposes the same library-relative, '/'-separated view as the
 * direct-filesystem implementation.
 *
 * Performance: [listAllIndexable] and [listAllUnder] walk the tree with direct
 * [android.content.ContentResolver] queries against [DocumentsContract] child-document uris
 * (COLUMN_DOCUMENT_ID/DISPLAY_NAME/MIME_TYPE/SIZE/LAST_MODIFIED), recursing into directories.
 * This is dramatically faster than [DocumentFile.listFiles], which does one binder round-trip
 * per child. Path-to-docId resolution
 * is memoized per directory (see [SafDirectoryCache]), because SAF has no path lookup and every
 * operation would otherwise re-list all of its ancestors.
 *
 * Write-once discipline: nothing here ever appends or overwrites. [writeBytesOnce] pre-checks
 * existence and guards against SAF's silent rename dedup (e.g. "file (1).pdf"), for the
 * directories it creates as for the file.
 */
internal class SafLibraryFileSystem(
    private val context: Context,
    private val treeUri: Uri,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LibraryFileSystem {
    // The provider's own rename publishes, which no remount takes away: an open need not try one,
    // and a cloud provider is spared syncing a file made and deleted at every open.
    override val publicationMayBeLost: Boolean = false

    private val resolver get() = context.contentResolver

    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Path-to-docId memoization for directories; see [SafDirectoryCache]. SAF has no path lookup,
     * so without this every operation re-lists all of its ancestors. Our own destructive
     * operations ([delete], [move]) invalidate the affected subtree eagerly; a directory deleted
     * outside the app is detected and recovered inside the cache. One moved or renamed away is
     * checked for where the tree's current path matters: before a listing under a path (how a
     * reconcile reads the journal) and before a publish, acknowledged only at its canonical path.
     */
    private val dirCache = SafDirectoryCache(treeDocId, ::queryChildren)

    /** The parent at this path now, including at providers whose ids survive moves. */
    private fun refreshParent(relPath: String): String? = dirCache.refresh(splitSegments(relPath).dropLast(1))

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private val projection =
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

    private fun queryChildren(parentDocId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val cursor =
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId),
                projection,
                null,
                null,
                null,
            )
                ?: throw IOException("Cannot query children of $parentDocId")
        cursor.use { c ->
            while (c.moveToNext()) {
                out.add(
                    Entry(
                        docId = c.getString(0),
                        name = c.getString(1) ?: "",
                        mime = c.getString(2) ?: "",
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                        mtime = if (c.isNull(4)) 0L else c.getLong(4),
                    ),
                )
            }
        }
        return out
    }

    /** Resolve a relative path to its document entry, or null if any segment is missing. */
    private fun resolveEntry(
        relPath: String,
        fresh: Boolean = false,
    ): Entry? {
        val segments = splitSegments(relPath)
        if (segments.isEmpty()) {
            // The root itself; synthesize a directory entry.
            return Entry(treeDocId, "", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L)
        }
        val parents = segments.subList(0, segments.size - 1)
        val children =
            if (fresh) {
                dirCache.refresh(parents)?.let(::queryChildren)
            } else {
                dirCache.childrenOfDir(parents)
            } ?: return null
        val match = children.firstOrNull { it.name == segments.last() } ?: return null
        if (match.isDir) dirCache.remember(segments.joinToString("/"), match.docId)
        return match
    }

    /** [resolveEntry], for a caller that needs the entry to be there. */
    private fun requireEntry(
        relPath: String,
        fresh: Boolean = false,
    ): Entry = resolveEntry(relPath, fresh) ?: throw IOException("Not found: $relPath")

    /** Resolve the parent directory docId of [relPath], creating dirs if [create]. */
    private fun resolveParentDir(
        relPath: String,
        create: Boolean,
    ): String {
        val segments = splitSegments(relPath)
        if (segments.size <= 1) return treeDocId
        val dirSegments = segments.subList(0, segments.size - 1)
        if (create) return createDirPath(dirSegments, relPath)
        return dirCache.resolveDir(dirSegments).docId
            ?: throw IOException("Parent directory missing for: $relPath")
    }

    /**
     * Resolve [dirSegments] as a directory chain, creating any level that does not exist yet, and
     * return the deepest docId: the creating half of [resolveParentDir].
     *
     * Refresh the actual parent chain first, so a cached id that survived a move or rename cannot
     * send a write to a displaced directory. Missing levels are created with exactly their name
     * ([createExact]); the copy also checks that its parent still has this path before succeeding.
     */
    private fun createDirPath(
        dirSegments: List<String>,
        relForError: String,
    ): String {
        dirCache.refresh(dirSegments)?.let { return it }
        var parentId = treeDocId
        for ((index, seg) in dirSegments.withIndex()) {
            val key = dirSegments.subList(0, index + 1).joinToString("/")
            val cached = dirCache.cached(key)
            if (cached != null) {
                parentId = cached
                continue
            }
            val existing = queryChildren(parentId).firstOrNull { it.name == seg }
            parentId =
                when {
                    existing != null && existing.isDir -> {
                        existing.docId
                    }

                    existing != null -> {
                        throw IOException("Path segment '$seg' is a file, not a directory: $relForError")
                    }

                    else -> {
                        // A clash (a sync tool made it meanwhile) leaves the directory of that name to use.
                        createExact(parentId, seg, DocumentsContract.Document.MIME_TYPE_DIR, relForError)
                            ?.let(DocumentsContract::getDocumentId)
                            ?: queryChildren(parentId).firstOrNull { it.name == seg && it.isDir }?.docId
                            ?: throw IOException("Could not create directory '$seg' under its own name: $relForError")
                    }
                }
            dirCache.remember(key, parentId)
        }
        return parentId
    }

    override suspend fun listAllIndexable(): List<FileMeta> =
        withContext(io) {
            listAllFrom(treeDocId, "", skipNpn = true)
        }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> =
        withContext(io) {
            val norm = NpnPaths.normalize(relPath)
            val rootId = dirCache.refresh(splitSegments(norm))
            if (rootId == null) emptyList() else listAllFrom(rootId, norm)
        }

    private fun listAllFrom(
        rootId: String,
        rootRel: String,
        skipNpn: Boolean = false,
    ): List<FileMeta> {
        val out = ArrayList<FileMeta>()
        // Iterative DFS over (docId, relPath) directory frontier.
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rootId to rootRel)
        while (stack.isNotEmpty()) {
            val (dirId, dirRel) = stack.removeLast()
            for (entry in queryChildren(dirId)) {
                // The top-level `.npn/` metadata tree is never indexable.
                if (skipNpn && dirRel.isEmpty() && entry.name == NpnPaths.NPN_DIR) continue
                val rel = if (dirRel.isEmpty()) entry.name else "$dirRel/${entry.name}"
                out.add(
                    FileMeta(
                        relPath = rel,
                        name = entry.name,
                        size = if (entry.isDir) 0L else entry.size,
                        mtimeMillis = entry.mtime,
                        isDirectory = entry.isDir,
                    ),
                )
                if (entry.isDir) {
                    // Free cache fill: the walk already holds every directory's docId, so a later
                    // per-file operation under one of them needs no ancestor lookups at all.
                    dirCache.remember(rel, entry.docId)
                    stack.addLast(entry.docId to rel)
                }
            }
        }
        return out
    }

    override suspend fun exists(relPath: String): Boolean =
        withContext(io) {
            resolveEntry(relPath) != null
        }

    override suspend fun stat(relPath: String): FileMeta? =
        withContext(io) {
            val entry = resolveEntry(relPath) ?: return@withContext null
            val norm = NpnPaths.normalize(relPath)
            FileMeta(
                relPath = norm,
                name = entry.name.ifEmpty { NpnPaths.fileName(norm) },
                size = if (entry.isDir) 0L else entry.size,
                mtimeMillis = entry.mtime,
                isDirectory = entry.isDir,
            )
        }

    override suspend fun readBytes(relPath: String): ByteArray =
        withContext(io) {
            val entry = requireEntry(relPath)
            resolver.openInputStream(docUri(entry.docId))?.use { it.readBytes() }
                ?: throw IOException("Cannot open for read: $relPath")
        }

    override suspend fun readChunked(
        relPath: String,
        sink: (ByteArray, Int) -> Unit,
    ) = withContext(io) {
        val entry = requireEntry(relPath)
        resolver.openInputStream(docUri(entry.docId))?.use { input -> input.pipeTo(sink) }
            ?: throw IOException("Cannot open for read: $relPath")
    }

    override suspend fun readTail(
        relPath: String,
        maxBytes: Int,
    ): ByteArray =
        withContext(io) {
            val entry = requireEntry(relPath)
            resolver.openInputStream(docUri(entry.docId))?.use { input ->
                // Providers stream; skipping still avoids holding the whole file in memory.
                var toSkip = maxOf(0L, entry.size - maxBytes)
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) break
                    toSkip -= skipped
                }
                val tail = input.readBytes()
                tail.copyOfRange(maxOf(0, tail.size - maxBytes), tail.size)
            } ?: throw IOException("Cannot open for read: $relPath")
        }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) = createNew(relPath) { bytes.inputStream() }

    /**
     * Create [relPath], which must not exist yet, with the bytes [openInput] streams; a partial
     * file is deleted again ([copyIntoNewFile]).
     */
    private suspend fun createNew(
        relPath: String,
        openInput: () -> InputStream,
    ) = withContext(io) {
        if (resolveEntry(relPath, fresh = true) != null) {
            error("Target already exists: $relPath")
        }
        val name = splitSegments(relPath).last()
        val parentId = resolveParentDir(relPath, create = true)
        copyIntoNewFile(parentId, name, relPath, openInput = openInput)
        Unit
    }

    /**
     * Publish a fully-written, hash-verified staging object under its canonical, content-addressed
     * name via [DocumentsContract.renameDocument].
     *
     * SAF rename is not guaranteed atomic like POSIX `rename(2)`, but this is safe because:
     * - The staging bytes are verified to hash to [expectedHash] *before* the rename, so the
     *   renamed canonical file always holds a complete object (rename moves the existing directory
     *   entry; it never re-copies or truncates bytes).
     * - v2 readers recompute the content hash and compare it against the filename; a torn or
     *   mismatched object is treated as retryable/skippable rather than a hard failure.
     *
     * Failure handling: on any rename failure the staging file is left in place (it is classified
     * as [dev.npn.core.journal.JournalPath.Staging] and ignored by readers, then GC'd by the
     * accumulator) and a retryable [VerifiedPublicationFailedException] is thrown, so a
     * wrongly-named file is never installed in the published objects directory. If the canonical
     * object already exists
     * (same hash ⇒ same content), the staging file is deleted and the call succeeds.
     */
    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ) = withContext(io) {
        val stagingSegments = splitSegments(stagingPath)
        val canonicalSegments = splitSegments(canonicalPath)
        require(stagingSegments.dropLast(1) == canonicalSegments.dropLast(1)) {
            "Object staging must be in the canonical bucket"
        }
        val canonicalName = canonicalSegments.last()
        // Resolve names AND parentage: a cloud provider can keep an id and name through a move.
        // A canonical object already present means the content was published earlier (content
        // addressed: same hash ⇒ same bytes). Verify its bytes and drop our staging copy.
        resolveEntry(canonicalPath, fresh = true)?.let { existing ->
            check(!existing.isDir) { "Canonical object path is a directory: $canonicalPath" }
            check(sha256OfEntry(existing) == expectedHash) {
                "Canonical object exists with invalid bytes: $canonicalPath"
            }
            requirePublishedAtPath(existing, canonicalPath)
            // Best effort: an orphaned staging file is never read as an object.
            runCatchingCancellable { delete(stagingPath) }
            return@withContext
        }

        val staging =
            resolveEntry(stagingPath, fresh = true)
                ?: throw VerifiedPublicationFailedException("Staging object is missing: $stagingPath")
        check(!staging.isDir) { "Staging object path is a directory: $stagingPath" }
        check(sha256OfEntry(staging) == expectedHash) { "Staging object hash mismatch: $stagingPath" }

        // Rename the complete staging file to its canonical name within the same bucket. A rename
        // the provider refuses fails retryably and leaves the staging file for a later attempt / GC.
        // One that lands under another name (e.g. "<hash> (1).jsonl") fails retryably too, and the
        // misnamed file is removed so it never sits among the published objects; the staging file
        // is gone then (renamed), so the next attempt stages afresh.
        try {
            renameExact(docUri(staging.docId), canonicalName, canonicalPath, discardMisnamed = true)
        } catch (e: Exception) {
            throw retryablePublish(canonicalPath, e)
        }

        // Confirm the published object is intact and correctly named before reporting success.
        val published =
            resolveEntry(canonicalPath, fresh = true)
                ?: throw retryablePublish(canonicalPath, IOException("Published object not found after rename"))
        check(sha256OfEntry(published) == expectedHash) {
            "Published object hash mismatch: $canonicalPath"
        }
        requirePublishedAtPath(published, canonicalPath)
    }

    /** A move while the provider renamed or read the object must not make a publication succeed. */
    private fun requirePublishedAtPath(
        published: Entry,
        canonicalPath: String,
    ) {
        if (resolveEntry(canonicalPath, fresh = true)?.docId != published.docId) {
            throw retryablePublish(canonicalPath, IOException("Published object left its canonical path"))
        }
    }

    private fun retryablePublish(
        canonicalPath: String,
        cause: Throwable?,
    ): VerifiedPublicationFailedException =
        VerifiedPublicationFailedException("Verified publication failed: $canonicalPath", cause)

    private fun sha256OfEntry(entry: Entry): String = digestSha256(docUri(entry.docId), entry.docId)

    /** SHA-256 of the content at [uri], streamed; [relForError] only names it in the not-found message. */
    private fun digestSha256(
        uri: Uri,
        relForError: String,
    ): String =
        resolver.openInputStream(uri)?.use { it.sha256Hex() }
            ?: throw IOException("Cannot open for read: $relForError")

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) = withContext(io) {
        if (resolveEntry(toRelPath, fresh = true) != null) {
            error("Target already exists: $toRelPath")
        }
        val source = requireEntry(fromRelPath, fresh = true)
        // Moving a directory relocates every descendant path, so the cached docIds keyed under the
        // OLD path are about to be wrong. Nothing above it moves, so only that subtree goes.
        // Document moves (the hot path: import, rename, storage-path rules) move files, which are
        // never cached, and so leave the cache untouched.
        if (source.isDir) dirCache.invalidateSubtree(splitSegments(fromRelPath).joinToString("/"))
        val fromSegments = splitSegments(fromRelPath)
        val toSegments = splitSegments(toRelPath)
        val sameDir = fromSegments.dropLast(1) == toSegments.dropLast(1)
        val fromName = fromSegments.last()
        val toName = toSegments.last()

        if (sameDir) {
            // Pure rename within the same directory.
            if (fromName != toName) renameExact(docUri(source.docId), toName, toRelPath, discardMisnamed = false)
            return@withContext
        }

        val fromParentId = resolveParentDir(fromRelPath, create = false)
        val toParentId = resolveParentDir(toRelPath, create = true)

        // A same-tree native move first (fast, atomic on the provider side), which keeps the name.
        val moved =
            if (fromName != toName) {
                null
            } else {
                try {
                    DocumentsContract.moveDocument(
                        resolver,
                        docUri(source.docId),
                        docUri(fromParentId),
                        docUri(toParentId),
                    )
                } catch (_: Exception) {
                    null
                }
            }
        if (moved == null) {
            streamMoveFallback(source, fromRelPath, toRelPath, toParentId, toName)
        } else {
            // The document has moved; a provider that dodged a clash by renaming it leaves it there.
            misnamed(moved, toName)?.let { throw IOException("Move collided (got '$it'): $toRelPath") }
        }
        Unit
    }

    /** Stream a cross-directory move without making a partial PDF visible at the final path. */
    private fun streamMoveFallback(
        source: Entry,
        fromRelPath: String,
        toRelPath: String,
        toParentId: String,
        toName: String,
    ) {
        val stagingName = NpnPaths.Temp.moveName(Uuid.random().toString())
        // The source is deleted below, so the staging copy is read back and checked first.
        val stagingUri =
            copyIntoNewFile(toParentId, stagingName, toRelPath, mimeForName(toName), verify = true) {
                resolver.openInputStream(docUri(source.docId))
                    ?: throw IOException("Cannot read source for move: $fromRelPath")
            }
        val renamed = publishMovedCopy(stagingUri, toName, fromRelPath, toRelPath)
        if (resolveEntry(toRelPath, fresh = true)?.docId != DocumentsContract.getDocumentId(renamed)) {
            deleteQuietly(renamed)
            throw IOException("Move target collided: $toRelPath")
        }
        DocumentsContract.deleteDocument(resolver, docUri(source.docId))
    }

    /** Rename a move's verified [staging] copy to [toName]; on failure the copy goes, the source stays. */
    private fun publishMovedCopy(
        staging: Uri,
        toName: String,
        fromRelPath: String,
        toRelPath: String,
    ): Uri =
        try {
            renameExact(staging, toName, toRelPath, discardMisnamed = true)
        } catch (e: Exception) {
            deleteQuietly(staging)
            throw IOException("Move publish failed: $fromRelPath -> $toRelPath", e)
        }

    /**
     * Create [name] in [parentId] ([createExact]) and stream [openInput] into it, removing the file
     * again on any error, so no partial one is left behind. With [verify], the file must also read
     * back as exactly the bytes that were read.
     */
    private fun copyIntoNewFile(
        parentId: String,
        name: String,
        relForError: String,
        mime: String = mimeForName(name),
        verify: Boolean = false,
        openInput: () -> InputStream,
    ): Uri {
        val createdUri =
            createExact(parentId, name, mime, relForError)
                ?: error("Target already exists (SAF renamed the new file): $relForError")
        try {
            val read = if (verify) MessageDigest.getInstance("SHA-256") else null
            streamInto(createdUri, relForError) {
                if (read != null) DigestInputStream(openInput(), read) else openInput()
            }
            verifyCopy(createdUri, parentId, relForError, read?.digest()?.toHexLower())
        } catch (e: Throwable) {
            deleteQuietly(createdUri)
            throw e
        }
        return createdUri
    }

    /** [expectedSha256] is the source's digest when the copy must read back as it; the parent must still hold it. */
    private fun verifyCopy(
        uri: Uri,
        parentId: String,
        relForError: String,
        expectedSha256: String?,
    ) {
        if (expectedSha256 != null && digestSha256(uri, relForError) != expectedSha256) {
            throw IOException("Copy does not read back as its source: $relForError")
        }
        if (refreshParent(relForError) != parentId) {
            throw IOException("Copy's parent left its path: $relForError")
        }
    }

    /** Write everything [openInput] yields into the existing file at [target], from its start. */
    private fun streamInto(
        target: Uri,
        relForError: String,
        openInput: () -> InputStream,
    ) {
        openInput().use { input ->
            resolver.openOutputStream(target, "w")?.use { output ->
                input.copyTo(output, STREAM_BUFFER_SIZE)
            } ?: throw IOException("Cannot open for write: $relForError")
        }
    }

    /**
     * Create an empty document (a file, or a directory by [mime]) named exactly [name] in [parentId],
     * or return null when [name] was taken. A provider may settle a clash by naming the new one
     * "name (1).ext" instead of failing, which would break write-once and leave the path naming
     * something else: that one is deleted again, as it was just made and is empty.
     */
    private fun createExact(
        parentId: String,
        name: String,
        mime: String,
        relForError: String,
    ): Uri? {
        val created =
            DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
                ?: throw IOException("Failed to create: $relForError")
        if (misnamed(created, name) == null) return created
        deleteQuietly(created)
        return null
    }

    /**
     * Rename [uri] to exactly [name] and return the renamed document. A provider that settles a
     * clash with another name ("name (1).ext") fails the rename just like one that refuses it. With
     * [discardMisnamed] the misnamed document is deleted first: right for a copy whose source is
     * still there, never for the user's own file.
     */
    private fun renameExact(
        uri: Uri,
        name: String,
        relForError: String,
        discardMisnamed: Boolean,
    ): Uri {
        val renamed =
            DocumentsContract.renameDocument(resolver, uri, name)
                ?: throw IOException("Provider did not rename to '$name': $relForError")
        misnamed(renamed, name)?.let { actual ->
            if (discardMisnamed) deleteQuietly(renamed)
            throw IOException("Provider renamed to '$actual' instead of '$name': $relForError")
        }
        return renamed
    }

    /** The name the provider gave [uri] if it is not [expected]; null if it is, or the provider cannot say. */
    private fun misnamed(
        uri: Uri,
        expected: String,
    ): String? = DocumentFile.fromSingleUri(context, uri)?.name?.takeIf { it != expected }

    /** Best-effort delete that never throws (used to clean up stray/partial files). */
    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
        }
    }

    override suspend fun delete(relPath: String) =
        withContext(io) {
            val entry = resolveEntry(relPath, fresh = true) ?: return@withContext
            // DocumentsContract.deleteDocument removes directories recursively.
            DocumentsContract.deleteDocument(resolver, docUri(entry.docId))
            // Deleting a directory invalidates its own cached docId and every descendant's, but
            // nothing above it. Files are never cached, so deleting one needs no invalidation.
            if (entry.isDir) dirCache.invalidateSubtree(splitSegments(relPath).joinToString("/"))
            Unit
        }

    override suspend fun sha256(relPath: String): String =
        withContext(io) {
            digestSha256(docUri(requireEntry(relPath).docId), relPath)
        }

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) = createNew(toRelPath) {
        resolver.openInputStream(source.uri) ?: throw IOException("Cannot open external source: ${source.uri}")
    }

    /** SAF provides no reliable change notifications; indexing is rescan-driven. */
    override fun watch(): Flow<FsEvent> = emptyFlow()
}

private fun splitSegments(relPath: String): List<String> =
    NpnPaths.normalize(relPath).split('/').filter { it.isNotEmpty() }

private fun mimeForName(name: String): String =
    if (NpnPaths.isPdf(name)) "application/pdf" else "application/octet-stream"
