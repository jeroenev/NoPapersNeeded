package dev.npn.core.fs

/**
 * Soft-delete operations backed by [LibraryFileSystem] and the `.npn/trash/` convention
 * (PLAN "Trash"). Moving to trash is a plain file move: the file stays in the library so it
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
        val name = NpnPaths.sanitizeFileName(relPath.substringAfterLast('/'))
        val base = NpnPaths.trashPath(name, sha256)
        val target = uniquify(base)
        fs.move(relPath, target)
        return target
    }

    /**
     * Restore a file from trash to [toRelPath]. If the target is taken, a
     * `name (restored).pdf`-style suffix is inserted (then `(restored 1)`, ...) until free.
     */
    suspend fun restore(
        trashRelPath: String,
        toRelPath: String,
    ): String {
        val target = uniquifyRestored(toRelPath)
        fs.move(trashRelPath, target)
        return target
    }

    /** All entries currently in `.npn/trash/` (files only). */
    suspend fun listTrash(): List<FileMeta> =
        fs.listAll().filter {
            !it.isDirectory &&
                (it.relPath == NpnPaths.TRASH_DIR || it.relPath.startsWith("${NpnPaths.TRASH_DIR}/")) &&
                it.relPath != NpnPaths.TRASH_DIR
        }

    /**
     * Permanently delete trash entries trashed strictly before [cutoffMillis].
     *
     * [documents] maps each live document id to the time it was trashed, or to null while it is
     * not in trash. An entry belongs to the documents whose id starts with the hash prefix in its
     * name ([NpnPaths.trashHashPrefix]):
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

    private suspend fun uniquifyRestored(path: String): String =
        uniquifyPath(
            path,
            startIndex = 1,
            firstSuffix = " (restored)",
            suffix = { " (restored $it)" },
            exists = fs::exists,
        )
}
