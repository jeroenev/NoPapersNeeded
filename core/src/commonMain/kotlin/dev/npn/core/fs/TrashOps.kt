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

    /** Permanently delete trash entries whose mtime is strictly older than [cutoffMillis]. */
    suspend fun purgeOlderThan(cutoffMillis: Long) {
        for (meta in listTrash()) {
            if (meta.mtimeMillis < cutoffMillis) {
                fs.delete(meta.relPath)
            }
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
