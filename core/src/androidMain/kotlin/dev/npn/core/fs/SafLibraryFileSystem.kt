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
 * [DocumentFile.listFiles], which does one binder round-trip per child.
 *
 * Write-once discipline: nothing here ever appends or overwrites. [writeBytesOnce] pre-checks
 * existence and guards against SAF's silent rename dedup (e.g. "file (1).pdf").
 */
class SafLibraryFileSystem(
    private val context: Context,
    private val treeUri: Uri,
) : LibraryFileSystem {

    private val resolver get() = context.contentResolver

    private val treeDocId: String = DocumentsContract.getTreeDocumentId(treeUri)

    private fun childrenUri(parentDocId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

    private fun docUri(docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun splitSegments(relPath: String): List<String> =
        NpnPaths.normalize(relPath).split('/').filter { it.isNotEmpty() }

    /** Internal representation of a resolved document. */
    private data class Entry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
        val mtime: Long,
    ) {
        val isDir: Boolean get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    private fun queryChildren(parentDocId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val cursor = resolver.query(childrenUri(parentDocId), projection, null, null, null)
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
        var parentId = treeDocId
        var found: Entry? = null
        for ((i, seg) in segments.withIndex()) {
            val children = queryChildren(parentId)
            val match = children.firstOrNull { it.name == seg } ?: return null
            if (i == segments.lastIndex) {
                found = match
            } else {
                if (!match.isDir) return null
                parentId = match.docId
            }
        }
        return found
    }

    /** Resolve the parent directory docId of [relPath], creating dirs if [create]. */
    private fun resolveParentDir(relPath: String, create: Boolean): String {
        val segments = splitSegments(relPath)
        if (segments.size <= 1) return treeDocId
        var parentId = treeDocId
        for (i in 0 until segments.size - 1) {
            val seg = segments[i]
            val children = queryChildren(parentId)
            val existing = children.firstOrNull { it.name == seg }
            parentId = when {
                existing != null && existing.isDir -> existing.docId
                existing != null -> throw IOException("Path segment '$seg' is a file, not a directory: $relPath")
                create -> {
                    val created = DocumentsContract.createDocument(
                        resolver,
                        docUri(parentId),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        seg,
                    ) ?: throw IOException("Failed to create directory '$seg' in: $relPath")
                    DocumentsContract.getDocumentId(created)
                }
                else -> throw IOException("Parent directory missing for: $relPath")
            }
        }
        return parentId
    }

    private fun mimeForName(name: String): String =
        if (NpnPaths.isPdf(name)) "application/pdf" else "application/octet-stream"

    override suspend fun listAll(): List<FileMeta> = withContext(Dispatchers.IO) {
        val out = ArrayList<FileMeta>()
        // Iterative DFS over (docId, relPath) directory frontier.
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(treeDocId to "")
        while (stack.isNotEmpty()) {
            val (dirId, dirRel) = stack.removeLast()
            for (entry in queryChildren(dirId)) {
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
                if (entry.isDir) stack.addLast(entry.docId to rel)
            }
        }
        out
    }

    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        resolveEntry(relPath) != null
    }

    override suspend fun stat(relPath: String): FileMeta? = withContext(Dispatchers.IO) {
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

    override suspend fun readBytes(relPath: String): ByteArray = withContext(Dispatchers.IO) {
        val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
        resolver.openInputStream(docUri(entry.docId))?.use { it.readBytes() }
            ?: throw IOException("Cannot open for read: $relPath")
    }

    override suspend fun writeBytesOnce(relPath: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        if (resolveEntry(relPath) != null) {
            error("Target already exists: $relPath")
        }
        val name = splitSegments(relPath).last()
        val parentId = resolveParentDir(relPath, create = true)
        val createdUri = DocumentsContract.createDocument(
            resolver,
            docUri(parentId),
            mimeForName(name),
            name,
        ) ?: throw IOException("Failed to create file: $relPath")
        // SAF may silently dedup and rename to "name (1).ext". That violates write-once, so
        // detect it, delete the misnamed file, and fail.
        val created = DocumentFile.fromSingleUri(context, createdUri)
        val actualName = created?.name
        if (actualName != null && actualName != name) {
            try {
                DocumentsContract.deleteDocument(resolver, createdUri)
            } catch (_: Exception) {
            }
            error("Target already exists (SAF renamed to '$actualName'): $relPath")
        }
        try {
            resolver.openOutputStream(createdUri, "w")?.use { it.write(bytes) }
                ?: throw IOException("Cannot open for write: $relPath")
        } catch (e: Throwable) {
            try {
                DocumentsContract.deleteDocument(resolver, createdUri)
            } catch (_: Exception) {
            }
            throw e
        }
        Unit
    }

    override suspend fun move(fromRelPath: String, toRelPath: String) = withContext(Dispatchers.IO) {
        if (resolveEntry(toRelPath) != null) {
            error("Target already exists: $toRelPath")
        }
        val source = resolveEntry(fromRelPath) ?: throw IOException("Not found: $fromRelPath")
        val fromSegments = splitSegments(fromRelPath)
        val toSegments = splitSegments(toRelPath)
        val sameDir = fromSegments.dropLast(1) == toSegments.dropLast(1)
        val fromName = fromSegments.last()
        val toName = toSegments.last()

        if (sameDir) {
            // Pure rename within the same directory.
            if (fromName != toName) {
                val renamed = DocumentsContract.renameDocument(resolver, docUri(source.docId), toName)
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
                val result = DocumentsContract.moveDocument(
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
            val data = resolver.openInputStream(docUri(source.docId))?.use { it.readBytes() }
                ?: throw IOException("Cannot read source for move: $fromRelPath")
            writeBytesInDir(toParentId, toName, data, toRelPath)
            DocumentsContract.deleteDocument(resolver, docUri(source.docId))
            return@withContext
        }

        // Native move landed the file under its original name in the target dir; rename if needed.
        if (fromName != toName) {
            val movedEntry = resolveEntry(
                (toSegments.dropLast(1) + fromName).joinToString("/"),
            ) ?: throw IOException("Post-move lookup failed: $toRelPath")
            val renamed = DocumentsContract.renameDocument(resolver, docUri(movedEntry.docId), toName)
                ?: throw IOException("Post-move rename failed: $toRelPath")
            val actual = DocumentFile.fromSingleUri(context, renamed)?.name
            if (actual != null && actual != toName) {
                throw IOException("Post-move rename collided (got '$actual'): $toRelPath")
            }
        }
        Unit
    }

    /** Create a file with an exact name inside [parentId], failing on SAF rename dedup. */
    private fun writeBytesInDir(parentId: String, name: String, bytes: ByteArray, relForError: String) {
        val createdUri = DocumentsContract.createDocument(
            resolver,
            docUri(parentId),
            mimeForName(name),
            name,
        ) ?: throw IOException("Failed to create file: $relForError")
        val actualName = DocumentFile.fromSingleUri(context, createdUri)?.name
        if (actualName != null && actualName != name) {
            try {
                DocumentsContract.deleteDocument(resolver, createdUri)
            } catch (_: Exception) {
            }
            error("Target already exists (SAF renamed to '$actualName'): $relForError")
        }
        resolver.openOutputStream(createdUri, "w")?.use { it.write(bytes) }
            ?: throw IOException("Cannot open for write: $relForError")
    }

    override suspend fun delete(relPath: String) = withContext(Dispatchers.IO) {
        val entry = resolveEntry(relPath) ?: return@withContext
        // DocumentsContract.deleteDocument removes directories recursively.
        DocumentsContract.deleteDocument(resolver, docUri(entry.docId))
        Unit
    }

    override suspend fun mkdirs(relPath: String) = withContext(Dispatchers.IO) {
        val segments = splitSegments(relPath)
        if (segments.isEmpty()) return@withContext
        var parentId = treeDocId
        for (seg in segments) {
            val children = queryChildren(parentId)
            val existing = children.firstOrNull { it.name == seg }
            parentId = when {
                existing != null && existing.isDir -> existing.docId
                existing != null -> throw IOException("Path segment '$seg' is a file: $relPath")
                else -> {
                    val created = DocumentsContract.createDocument(
                        resolver,
                        docUri(parentId),
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        seg,
                    ) ?: throw IOException("Failed to create directory '$seg': $relPath")
                    DocumentsContract.getDocumentId(created)
                }
            }
        }
        Unit
    }

    override suspend fun sha256(relPath: String): String = withContext(Dispatchers.IO) {
        val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(docUri(entry.docId))?.use { input ->
            DigestInputStream(input, digest).use { dis ->
                val buffer = ByteArray(64 * 1024)
                while (dis.read(buffer) != -1) {
                    // read feeds the digest
                }
            }
        } ?: throw IOException("Cannot open for read: $relPath")
        digest.digest().toHexLower()
    }

    override suspend fun importExternal(source: ExternalSource, toRelPath: String) = withContext(Dispatchers.IO) {
        if (resolveEntry(toRelPath) != null) {
            error("Target already exists: $toRelPath")
        }
        val name = splitSegments(toRelPath).last()
        val parentId = resolveParentDir(toRelPath, create = true)
        val data = resolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open external source: ${source.uri}")
        writeBytesInDir(parentId, name, data, toRelPath)
        Unit
    }

    override suspend fun copyOut(relPath: String, target: ExternalSource) = withContext(Dispatchers.IO) {
        val entry = resolveEntry(relPath) ?: throw IOException("Not found: $relPath")
        val data = resolver.openInputStream(docUri(entry.docId))?.use { it.readBytes() }
            ?: throw IOException("Cannot read: $relPath")
        resolver.openOutputStream(target.uri, "w")?.use { it.write(data) }
            ?: throw IOException("Cannot open external target: ${target.uri}")
        Unit
    }

    /** SAF provides no reliable change notifications; indexing is rescan-driven. */
    override fun watch(): Flow<FsEvent> = emptyFlow()
}


