package dev.npn.core.fs

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Storage Access Framework backed [LibraryFileSystem] for Android (PLAN section 4, "Android
 * storage"). Given a tree uri from `ACTION_OPEN_DOCUMENT_TREE`, it exposes the same
 * library-relative, '/'-separated view as the desktop implementation.
 *
 * Performance: [listAll] walks the tree with direct [android.content.ContentResolver] queries
 * against [DocumentsContract] child-document uris (COLUMN_DOCUMENT_ID/DISPLAY_NAME/MIME_TYPE/
 * SIZE/LAST_MODIFIED), recursing into directories. This is dramatically faster than
 * [DocumentFile.listFiles], which does one binder round-trip per child. Path-to-docId resolution
 * is memoized per directory (see [SafDirectoryCache]), because SAF has no path lookup and every
 * operation would otherwise re-list all of its ancestors.
 *
 * Write-once discipline: nothing here ever appends or overwrites. [writeBytesOnce] pre-checks
 * existence and guards against SAF's silent rename dedup (e.g. "file (1).pdf").
 */
class SafLibraryFileSystem(
    private val context: Context,
    private val treeUri: Uri,
) : LibraryFileSystem {
    // SAF rename is not POSIX-atomic, but v2 objects are content-addressed: the staging file is
    // written and hash-verified in full before the rename, so the published object can never be
    // torn, and readers verify the content hash against the filename anyway (see
    // StorageV2Reader / publishVerifiedObjectOnce below).
    override val verifiedPublicationCapability: VerifiedPublicationCapability =
        VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

    private val resolver get() = context.contentResolver

    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Path-to-docId memoization for directories; see [SafDirectoryCache]. SAF has no path lookup,
     * so without this every operation re-lists all of its ancestors. Our own destructive
     * operations ([delete], [move]) invalidate the affected subtree eagerly; staleness caused from
     * outside the app is detected and recovered inside the cache.
     */
    private val dirCache = SafDirectoryCache(treeDocId) { queryChildren(it) }

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun splitSegments(relPath: String): List<String> =
        NpnPaths.normalize(relPath).split('/').filter { it.isNotEmpty() }

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
            resolver.query(childrenUri(parentDocId), projection, null, null, null)
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
    private fun resolveEntry(relPath: String): Entry? {
        val segments = splitSegments(relPath)
        if (segments.isEmpty()) {
            // The root itself; synthesize a directory entry.
            return Entry(treeDocId, "", DocumentsContract.Document.MIME_TYPE_DIR, 0L, 0L)
        }
        val children = dirCache.childrenOfDir(segments.subList(0, segments.size - 1)) ?: return null
        val match = children.firstOrNull { it.name == segments.last() } ?: return null
        if (match.isDir) dirCache.remember(segments.joinToString("/"), match.docId)
        return match
    }

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
     * return the deepest docId. Shared by [mkdirs] and the creating half of [resolveParentDir].
     *
     * Unlike the read paths this does not retry past a stale cache entry: creating under a docId
     * whose directory was deleted outside the app fails loudly here, which is the right outcome for
     * a write-once filesystem — better a clear error than a file quietly landing somewhere else.
     */
    private fun createDirPath(
        dirSegments: List<String>,
        relForError: String,
    ): String {
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
                        val created =
                            DocumentsContract.createDocument(
                                resolver,
                                docUri(parentId),
                                DocumentsContract.Document.MIME_TYPE_DIR,
                                seg,
                            ) ?: throw IOException("Failed to create directory '$seg' in: $relForError")
                        DocumentsContract.getDocumentId(created)
                    }
                }
            dirCache.remember(key, parentId)
        }
        return parentId
    }

    private fun mimeForName(name: String): String =
        if (NpnPaths.isPdf(name)) "application/pdf" else "application/octet-stream"

    override suspend fun listAll(): List<FileMeta> =
        withContext(Dispatchers.IO) {
            listAllFrom(treeDocId, "")
        }

    override suspend fun listAllIndexable(): List<FileMeta> =
        withContext(Dispatchers.IO) {
            listAllFrom(treeDocId, "", skipNpn = true)
        }

    override suspend fun listAllUnder(relPath: String): List<FileMeta> =
        withContext(Dispatchers.IO) {
            val norm = NpnPaths.normalize(relPath)
            val root = resolveEntry(norm)
            if (root == null || !root.isDir) emptyList() else listAllFrom(root.docId, norm)
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
        withContext(Dispatchers.IO) {
            resolveEntry(relPath) != null
        }

    override suspend fun stat(relPath: String): FileMeta? =
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(relPath) ?: return@withContext null
            val norm = NpnPaths.normalize(relPath)
            FileMeta(
                relPath = norm,
                name = entry.name.ifEmpty { norm.substringAfterLast('/') },
                size = if (entry.isDir) 0L else entry.size,
                mtimeMillis = entry.mtime,
                isDirectory = entry.isDir,
            )
        }

    override suspend fun readBytes(relPath: String): ByteArray =
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
            resolver.openInputStream(docUri(entry.docId))?.use { it.readBytes() }
                ?: throw IOException("Cannot open for read: $relPath")
        }

    override suspend fun writeBytesOnce(
        relPath: String,
        bytes: ByteArray,
    ) = withContext(Dispatchers.IO) {
        if (resolveEntry(relPath) != null) {
            error("Target already exists: $relPath")
        }
        val name = splitSegments(relPath).last()
        val parentId = resolveParentDir(relPath, create = true)
        writeBytesInDir(parentId, name, bytes, relPath)
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
     * as [NpnPaths.JournalPath.Staging] and ignored by readers, then GC'd by the accumulator) and a
     * retryable [VerifiedPublicationUnavailableException] is thrown, so a wrongly-named file is
     * never installed in the published objects directory. If the canonical object already exists
     * (same hash ⇒ same content), the staging file is deleted and the call reports
     * [VerifiedPublicationResult.ALREADY_PRESENT].
     */
    override suspend fun publishVerifiedObjectOnce(
        stagingPath: String,
        canonicalPath: String,
        expectedHash: String,
    ): VerifiedPublicationResult =
        withContext(Dispatchers.IO) {
            val stagingSegments = splitSegments(stagingPath)
            val canonicalSegments = splitSegments(canonicalPath)
            require(stagingSegments.dropLast(1) == canonicalSegments.dropLast(1)) {
                "Object staging must be in the canonical bucket"
            }
            val canonicalName = canonicalSegments.last()

            // A canonical object already present means the content was published earlier (content
            // addressed: same hash ⇒ same bytes). Verify its bytes and drop our staging copy.
            resolveEntry(canonicalPath)?.let { existing ->
                check(!existing.isDir) { "Canonical object path is a directory: $canonicalPath" }
                check(sha256OfEntry(existing) == expectedHash) {
                    "Canonical object exists with invalid bytes: $canonicalPath"
                }
                runCatching { delete(stagingPath) }
                return@withContext VerifiedPublicationResult.ALREADY_PRESENT
            }

            val staging =
                resolveEntry(stagingPath)
                    ?: throw VerifiedPublicationUnavailableException("Staging object is missing: $stagingPath")
            check(!staging.isDir) { "Staging object path is a directory: $stagingPath" }
            check(sha256OfEntry(staging) == expectedHash) { "Staging object hash mismatch: $stagingPath" }

            // Rename the complete staging file to its canonical name within the same bucket. SAF
            // rename can throw or return null on some providers; treat that as retryable and leave the
            // staging file for a later attempt / GC.
            val renamedUri =
                try {
                    DocumentsContract.renameDocument(resolver, docUri(staging.docId), canonicalName)
                } catch (e: Exception) {
                    throw retryablePublish(canonicalPath, e)
                } ?: throw retryablePublish(canonicalPath, null)

            // Some providers ignore the requested name and dedup (e.g. "<hash> (1).jsonl"), which would
            // leave a wrongly-named file in the published directory. Detect that, remove the stray, and
            // fail retryably. The original staging file is gone (renamed), so re-staging happens above.
            val actualName = DocumentFile.fromSingleUri(context, renamedUri)?.name
            if (actualName != null && actualName != canonicalName) {
                deleteQuietly(renamedUri)
                throw retryablePublish(
                    canonicalPath,
                    IOException("Provider renamed publish target to '$actualName'"),
                )
            }

            // Confirm the published object is intact and correctly named before reporting success.
            val published =
                resolveEntry(canonicalPath)
                    ?: throw retryablePublish(canonicalPath, IOException("Published object not found after rename"))
            check(sha256OfEntry(published) == expectedHash) {
                "Published object hash mismatch: $canonicalPath"
            }
            VerifiedPublicationResult.PUBLISHED
        }

    private fun retryablePublish(
        canonicalPath: String,
        cause: Throwable?,
    ): VerifiedPublicationUnavailableException =
        VerifiedPublicationUnavailableException("Verified publication failed: $canonicalPath", cause)

    private fun sha256OfEntry(entry: Entry): String = digestSha256(docUri(entry.docId), entry.docId)

    /**
     * Stream the content at [uri] through a SHA-256 [DigestInputStream] (64 KiB buffer) and return
     * the lowercase hex digest. [relForError] is only used to build the not-found message.
     */
    private fun digestSha256(
        uri: Uri,
        relForError: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.use { input ->
            DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(64 * 1024)
                while (dis.read(buffer) != -1) {
                    // read feeds the digest
                }
            }
        } ?: throw IOException("Cannot open for read: $relForError")
        return digest.digest().toHexLower()
    }

    override suspend fun move(
        fromRelPath: String,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        if (resolveEntry(toRelPath) != null) {
            error("Target already exists: $toRelPath")
        }
        val source = resolveEntry(fromRelPath) ?: throw IOException("Not found: $fromRelPath")
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
            if (fromName != toName) {
                val renamed =
                    DocumentsContract.renameDocument(resolver, docUri(source.docId), toName)
                        ?: throw IOException("Rename failed: $fromRelPath -> $toRelPath")
                val actual = DocumentFile.fromSingleUri(context, renamed)?.name
                if (actual != null && actual != toName) {
                    throw IOException("Rename collided (got '$actual'): $toRelPath")
                }
            }
            return@withContext
        }

        val fromParentId = resolveParentDir(fromRelPath, create = false)
        val toParentId = resolveParentDir(toRelPath, create = true)

        // Try a same-tree native move first (fast, atomic on the provider side).
        var moved = false
        if (fromName == toName) {
            try {
                val result =
                    DocumentsContract.moveDocument(
                        resolver,
                        docUri(source.docId),
                        docUri(fromParentId),
                        docUri(toParentId),
                    )
                moved = result != null
            } catch (_: Exception) {
                moved = false
            }
        }

        if (!moved) {
            // Fallback: copy the bytes into the new location write-once, then delete the source.
            val data =
                resolver.openInputStream(docUri(source.docId))?.use { it.readBytes() }
                    ?: throw IOException("Cannot read source for move: $fromRelPath")
            writeBytesInDir(toParentId, toName, data, toRelPath)
            DocumentsContract.deleteDocument(resolver, docUri(source.docId))
            return@withContext
        }

        // Native move landed the file under its original name in the target dir; rename if needed.
        if (fromName != toName) {
            val movedEntry =
                resolveEntry(
                    (toSegments.dropLast(1) + fromName).joinToString("/"),
                ) ?: throw IOException("Post-move lookup failed: $toRelPath")
            val renamed =
                DocumentsContract.renameDocument(resolver, docUri(movedEntry.docId), toName)
                    ?: throw IOException("Post-move rename failed: $toRelPath")
            val actual = DocumentFile.fromSingleUri(context, renamed)?.name
            if (actual != null && actual != toName) {
                throw IOException("Post-move rename collided (got '$actual'): $toRelPath")
            }
        }
        Unit
    }

    /**
     * Create a file with an exact name inside [parentId] and write [bytes] once. SAF may silently
     * dedup a name collision by renaming to "name (1).ext"; that violates write-once, so on such a
     * mismatch the stray file is deleted and the call fails. If the write itself throws, the
     * just-created file is removed so no partial object is left behind.
     */
    private fun writeBytesInDir(
        parentId: String,
        name: String,
        bytes: ByteArray,
        relForError: String,
    ) {
        val createdUri =
            DocumentsContract.createDocument(
                resolver,
                docUri(parentId),
                mimeForName(name),
                name,
            ) ?: throw IOException("Failed to create file: $relForError")
        assertExactCreatedName(createdUri, name, relForError)
        try {
            writeCreatedFile(createdUri, bytes, relForError)
        } catch (e: Throwable) {
            deleteQuietly(createdUri)
            throw e
        }
    }

    private fun writeCreatedFile(
        uri: Uri,
        bytes: ByteArray,
        relForError: String,
    ) {
        resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
            ?: throw IOException("Cannot open for write: $relForError")
    }

    /**
     * Guard against SAF's silent rename-dedup: if the provider gave [createdUri] a name other than
     * [expected] (e.g. "name (1).ext"), delete the stray file and fail write-once. A null actual
     * name is treated as "unknown, assume ok" to match prior behaviour.
     */
    private fun assertExactCreatedName(
        createdUri: Uri,
        expected: String,
        relForError: String,
    ) {
        val actualName = DocumentFile.fromSingleUri(context, createdUri)?.name
        if (actualName != null && actualName != expected) {
            deleteQuietly(createdUri)
            error("Target already exists (SAF renamed to '$actualName'): $relForError")
        }
    }

    /** Best-effort delete that never throws (used to clean up stray/partial files). */
    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
        }
    }

    override suspend fun delete(relPath: String) =
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(relPath) ?: return@withContext
            // DocumentsContract.deleteDocument removes directories recursively.
            DocumentsContract.deleteDocument(resolver, docUri(entry.docId))
            // Deleting a directory invalidates its own cached docId and every descendant's, but
            // nothing above it. Files are never cached, so deleting one needs no invalidation.
            if (entry.isDir) dirCache.invalidateSubtree(splitSegments(relPath).joinToString("/"))
            Unit
        }

    override suspend fun mkdirs(relPath: String) =
        withContext(Dispatchers.IO) {
            val segments = splitSegments(relPath)
            if (segments.isEmpty()) return@withContext
            createDirPath(segments, relPath)
            Unit
        }

    override suspend fun sha256(relPath: String): String =
        withContext(Dispatchers.IO) {
            val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
            digestSha256(docUri(entry.docId), relPath)
        }

    override suspend fun importExternal(
        source: ExternalSource,
        toRelPath: String,
    ) = withContext(Dispatchers.IO) {
        if (resolveEntry(toRelPath) != null) {
            error("Target already exists: $toRelPath")
        }
        val name = splitSegments(toRelPath).last()
        val parentId = resolveParentDir(toRelPath, create = true)
        val data =
            resolver.openInputStream(source.uri)?.use { it.readBytes() }
                ?: throw IOException("Cannot open external source: ${source.uri}")
        writeBytesInDir(parentId, name, data, toRelPath)
        Unit
    }

    override suspend fun copyOut(
        relPath: String,
        target: ExternalSource,
    ) = withContext(Dispatchers.IO) {
        val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
        val data =
            resolver.openInputStream(docUri(entry.docId))?.use { it.readBytes() }
                ?: throw IOException("Cannot read: $relPath")
        resolver.openOutputStream(target.uri, "w")?.use { it.write(data) }
            ?: throw IOException("Cannot open external target: ${target.uri}")
        Unit
    }

    /** SAF provides no reliable change notifications; indexing is rescan-driven. */
    override fun watch(): Flow<FsEvent> = emptyFlow()
}
