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
     * hash only an entry that changed since, or appeared, and [confirmedCopiesOf] to list nothing
     * while a copy found here is still there.
     * [listing], from [listTrash], lets a run over many documents list the trash once; without one,
     * the trash is listed here.
     */
    suspend fun hashCandidates(
        docId: String,
        listing: List<FileMeta>? = null,
    ): Map<FileMeta, Boolean> = candidates(docId, listing).associateWith { fs.holds(it.relPath, docId) }

    /**
     * The entries [hashed] (from [hashCandidates]) knows of that hold [docId]'s bytes now, for a caller
     * under the writer lock, without listing the trash there while one of them still does: each is
     * checked by a stat, and one whose stat changed since is hashed again. When none does (moved,
     * written over, or none was found), the trash is listed again ([copiesOf]), for a copy that went
     * elsewhere in it or reached it since the listing (a sync delivering a trash move).
     */
    suspend fun confirmedCopiesOf(
        docId: String,
        hashed: Map<FileMeta, Boolean>,
    ): List<String> =
        hashed.keys
            .filter { holdsNow(it, docId, hashed.getValue(it)) }
            .map { it.relPath }
            .ifEmpty { copiesOf(docId, hashed) }

    /** Whether [listed] holds [docId]'s bytes now: [held], as when hashed, while its stat is unchanged. */
    private suspend fun holdsNow(
        listed: FileMeta,
        docId: String,
        held: Boolean,
    ): Boolean {
        val now = fs.stat(listed.relPath)?.takeIf { !it.isDirectory } ?: return false
        val unchanged = now.size == listed.size && now.mtimeMillis == listed.mtimeMillis
        return if (unchanged) held else fs.holds(listed.relPath, docId)
    }

    /** The trash entries whose name carries [docId]'s hash prefix, in [listing] or else in the trash now. */
    private suspend fun candidates(
        docId: String,
        listing: List<FileMeta>? = null,
    ): List<FileMeta> {
        val prefix = docId.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
        return (listing ?: listTrash()).filter { NpnPaths.trashHashPrefix(it.name) == prefix }
    }

    /**
     * Permanently delete the trash entries whose documents were trashed, or deleted for good,
     * strictly before [cutoffMillis].
     *
     * [documents] maps each document known to be trashed or deleted to when that happened, and may
     * map others to null. An entry belongs to the documents whose id starts with the hash prefix in
     * its name ([NpnPaths.trashHashPrefix]), all of them, so a prefix two documents share only keeps
     * it longer and its bytes need no hashing:
     * - a trashed or deleted document: when that happened decides. The entry's mtime cannot, because
     *   a move into trash keeps the PDF's own mtime, so a document imported long ago would go the
     *   moment it was trashed.
     * - a document in neither: kept. Trashing moves the PDF before it commits the event, and a crash
     *   in between leaves the PDF here with no event.
     * - no document: kept, as nothing says when it was trashed. An import journals nothing, so
     *   another device's trash of a document never edited is unknown until its event arrives, which
     *   may come after the file; and a crash may have left a document's only copy here.
     */
    suspend fun purgeOlderThan(
        cutoffMillis: Long,
        documents: Map<String, Long?>,
    ) {
        val byPrefix =
            documents.entries.groupBy(
                keySelector = { it.key.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH) },
                valueTransform = { it.value },
            )
        for (meta in listTrash()) {
            val owners = NpnPaths.trashHashPrefix(meta.name)?.let(byPrefix::get).orEmpty()
            if (owners.isNotEmpty() && owners.all { it != null && it < cutoffMillis }) fs.delete(meta.relPath)
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
