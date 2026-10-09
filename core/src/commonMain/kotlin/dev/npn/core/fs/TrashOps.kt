package dev.npn.core.fs

/**
 * Soft-delete operations backed by [LibraryFileSystem] and the `.npn/trash/` convention.
 * Moving to trash is a plain file move: the file stays in the library so it
 * syncs, but is excluded from the index. Filenames get a hash suffix to avoid collisions;
 * further collisions get a numeric `-1`, `-2`, ... suffix so the move stays write-once.
 */
class TrashOps(
    private val fs: LibraryFileSystem,
) {
    /**
     * Move [relPath] into `.npn/trash/`, returning the trash-relative path used. The base
     * trash name is [NpnPaths.trashPath]; if that already exists, `-1`, `-2`, ... is inserted
     * before the extension until a free name is found.
     */
    suspend fun moveToTrash(
        relPath: String,
        sha256: String,
    ): String {
        val target = trashTarget(relPath, sha256)
        fs.move(relPath, target)
        return target
    }

    /** Where [moveToTrash] puts [relPath], the file of the document [sha256]. */
    suspend fun trashTarget(
        relPath: String,
        sha256: String,
    ): String {
        val name = NpnPaths.sanitizeFileName(NpnPaths.fileName(relPath))
        return uniquify(NpnPaths.trashPath(name, sha256))
    }

    /**
     * Restore a file from trash to [toRelPath]. If the target is taken, a
     * `name (restored).pdf`-style suffix is inserted (then `(restored 1)`, ...) until free.
     */
    suspend fun restore(
        trashRelPath: String,
        toRelPath: String,
    ): String {
        val target = restoreTarget(toRelPath, fs::exists)
        fs.move(trashRelPath, target)
        return target
    }

    /**
     * Where a file restored to [toRelPath] goes: there, or beside it when it is [taken] ([restore]
     * takes a path a file is at).
     */
    suspend fun restoreTarget(
        toRelPath: String,
        taken: suspend (String) -> Boolean,
    ): String = uniquifyRestored(toRelPath, taken)

    /**
     * All entries currently in `.npn/trash/` (files only, never a temporary file a move into the trash
     * left behind, nor a name the library cannot address). Walks that directory alone, not the library.
     */
    suspend fun listTrash(): List<FileMeta> =
        fs.listAllUnder(NpnPaths.TRASH_DIR).filter {
            !it.isDirectory && NpnPaths.isSafe(it.relPath) && NpnPaths.Temp.kindOf(it.relPath) == null
        }

    /**
     * The trash entries that hold [docId]'s bytes. The hash prefix in an entry's name only narrows
     * the search: another document's hash can share it, and an entry may hold other bytes than its
     * name says, so each candidate is hashed, unless [hashed] (from [hashCandidates]) knows it with
     * the stat it has now.
     */
    suspend fun copiesOf(
        docId: String,
        hashed: Map<FileMeta, Boolean> = emptyMap(),
    ): List<String> = candidates(docId).filter { hashed[it] ?: fs.holds(it.relPath, docId) }.map { it.relPath }

    /**
     * Whether each entry [copiesOf] would look at for [docId] holds its bytes, keyed by the stat it
     * had before it was hashed. Taken before the writer lock, it leaves [copiesOf] under the lock to
     * hash only an entry that changed since, or appeared.
     */
    suspend fun hashCandidates(docId: String): Map<FileMeta, Boolean> =
        candidates(docId).associateWith { fs.holds(it.relPath, docId) }

    /** The trash entries whose name carries [docId]'s hash prefix. */
    private suspend fun candidates(docId: String): List<FileMeta> {
        val prefix = docId.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
        return listTrash().filter { NpnPaths.trashHashPrefix(it.name) == prefix }
    }

    /**
     * Permanently delete trash entries trashed strictly before [cutoffMillis].
     *
     * [documents] maps each live document id to the time it was trashed, or to null while it is
     * not in trash. An entry belongs to the documents whose id starts with the hash prefix in its
     * name ([NpnPaths.trashHashPrefix]), all of them, so a prefix two documents share only keeps it
     * longer and its bytes need no hashing:
     * - a trashed document: its trash time decides. The entry's mtime cannot, because a move into
     *   trash keeps the PDF's own mtime, so a document imported long ago would go the moment it
     *   was trashed.
     * - a document not in trash yet: kept. Trashing moves the PDF before it commits the event.
     * - no document: the mtime decides, as nothing better is known.
     */
    suspend fun purgeOlderThan(
        cutoffMillis: Long,
        documents: Map<String, Long?> = emptyMap(),
    ) {
        val byPrefix =
            documents.entries.groupBy(
                keySelector = { it.key.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) },
                valueTransform = { it.value },
            )
        for (meta in listTrash()) {
            val owners = NpnPaths.trashHashPrefix(meta.name)?.let(byPrefix::get)
            val trashedAt =
                when {
                    owners == null -> meta.mtimeMillis
                    null in owners -> continue
                    else -> owners.maxOf { checkNotNull(it) }
                }
            if (trashedAt < cutoffMillis) fs.delete(meta.relPath)
        }
    }

    private suspend fun uniquify(path: String): String =
        uniquifyPath(path, startIndex = 1, suffix = { "-$it" }, exists = fs::exists)

    private suspend fun uniquifyRestored(
        path: String,
        taken: suspend (String) -> Boolean,
    ): String =
        uniquifyPath(
            path,
            startIndex = 1,
            firstSuffix = " (restored)",
            suffix = { " (restored $it)" },
            exists = taken,
        )
}
