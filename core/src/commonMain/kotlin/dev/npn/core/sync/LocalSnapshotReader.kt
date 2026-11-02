package dev.npn.core.sync

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths

/** Builds the [LocalSnapshot] a pass plans from, after metadata has been pulled and folded. */
class LocalSnapshotReader(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val hooks: SyncHooks,
) {
    /**
     * The snapshot to plan from. [remoteDocIds] are the documents the remote holds, which the journal
     * is asked about too: one this device has no row for may still be one the journal hides.
     */
    suspend fun read(
        nowMillis: Long,
        remoteDocIds: Collection<String>,
    ): LocalSnapshot {
        val rows = documentDao.getAllRows()
        val journal = hooks.journalView((rows.map { it.docId } + remoteDocIds).distinct())
        val trashFiles = trashFiles()
        return LocalSnapshot(
            documents = rows.map { row -> localDocument(row, journal[row.docId]?.intendedPath, trashFiles) },
            metadataFiles = metadataFiles(),
            trashFiles = trashFiles,
            nowMillis = nowMillis,
            trashRetentionMillis = hooks.trashRetentionDays()?.let { it * MILLIS_PER_DAY },
            journal = journal,
            trashTimes = hooks.trashTimes(),
            relocates = fs.supportsDocumentRelocation,
        )
    }

    /** Canonical journal objects, snapshots and write-once internals present locally: path to size. */
    suspend fun metadataFiles(): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        for (dir in listOf(NpnPaths.JOURNAL_DIR, NpnPaths.OCR_DIR, NpnPaths.DEVICES_DIR)) {
            for (meta in fs.listAllUnder(dir)) {
                if (!meta.isDirectory && SyncPaths.classify(meta.relPath).isMetadata) out[meta.relPath] = meta.size
            }
        }
        return out
    }

    /** The local trash entries' paths. */
    suspend fun trashPaths(): Set<String> = trashFiles().keys

    private suspend fun trashFiles(): Map<String, TrashFile> =
        fs
            .listAllUnder(NpnPaths.TRASH_DIR)
            .filter { !it.isDirectory && SyncPaths.classify(it.relPath) == SyncPathClass.Trash }
            .associate { it.relPath to TrashFile(it.mtimeMillis, it.size) }

    private suspend fun localDocument(
        row: DocumentEntity,
        intent: String?,
        trashFiles: Map<String, TrashFile>,
    ): LocalDocument {
        val relPath = row.relPath
        val fileAtRelPath = NpnPaths.isDocumentPath(relPath) && fs.exists(relPath)
        return LocalDocument(
            docId = row.docId,
            relPath = relPath,
            size = row.size,
            trashed = row.trashed,
            trashedAt = row.trashedAt,
            trashedOrigPath = row.trashedOrigPath,
            deleted = row.deleted,
            intent = intent,
            fileAtRelPath = fileAtRelPath,
            trashEntry = trashEntryFor(row, trashFiles),
        )
    }

    /**
     * The local trash entry holding [row]'s bytes: an entry whose name carries its hash prefix
     * ([NpnPaths.trashHashPrefix]), preferring a size match when the prefix is ambiguous. The bytes
     * are verified before anything acts on the entry.
     */
    private fun trashEntryFor(
        row: DocumentEntity,
        trashFiles: Map<String, TrashFile>,
    ): String? {
        val marker = row.docId.take(NpnPaths.TRASH_HASH_PREFIX_LENGTH)
        val candidates = trashFiles.filterKeys { NpnPaths.trashHashPrefix(NpnPaths.fileName(it)) == marker }
        if (candidates.isEmpty()) return null
        return candidates.entries.firstOrNull { it.value.size == row.size }?.key ?: candidates.keys.first()
    }

    private companion object {
        const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
    }
}
