package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DuplicateDao
import dev.npn.core.db.isLive
import dev.npn.core.fs.DeleteAuthority
import dev.npn.core.fs.DeleteGate
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.hasStoredStat
import dev.npn.core.fs.holds

/**
 * Resolves external duplicates: removes an extra copy of a document's bytes beside its own file.
 */
internal class DuplicateResolver(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val duplicateDao: DuplicateDao,
    /**
     * Runs a removal under the journal coordinator's lock, as imports run, so a rescan cannot list
     * the file, or move a document onto it, while it is checked and deleted.
     */
    private val serialize: suspend (suspend () -> Unit) -> Unit,
) {
    private val gate = DeleteGate(fs)

    /**
     * Resolve an external duplicate recorded at [extraRelPath] for [docId]: delete the extra copy
     * and its duplicates row, keeping the document's own file.
     *
     * The row may be stale: the copy since overwritten with another document, or the document's
     * only file now that the original went. So the file goes only while it holds [docId]'s bytes
     * beside the live document's own file, which still holds them too; the row goes either way.
     *
     * The copy is hashed before the lock, which every journal write, import and rescan waits on, and
     * under it only its stat is checked against the one taken before the hash: a copy written to
     * since is left alone rather than hashed again there. The document's own file goes by
     * [holdsDocument], a stat while it is unchanged.
     */
    suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
    ) {
        val copy = copyOf(docId, extraRelPath)
        serialize {
            val row = documentDao.getByDocId(docId)
            if (row != null && copy != null && isExtraCopy(row, extraRelPath, copy)) {
                val ownStat =
                    FileMeta(row.relPath, row.fileName, size = row.size, mtimeMillis = row.mtime, isDirectory = false)
                gate.reap(extraRelPath, docId, DeleteAuthority.ExtraCopy(row.relPath, ownStat), copy)
            }
            duplicateDao.delete(docId, extraRelPath)
        }
    }

    /**
     * The stat [path] had before it was hashed, when it holds [docId]'s bytes; null otherwise, one
     * that cannot be read included.
     */
    private suspend fun copyOf(
        docId: String,
        path: String,
    ): FileMeta? {
        val meta = fs.stat(path)?.takeIf { !it.isDirectory } ?: return null
        return meta.takeIf { fs.holds(path, docId) }
    }

    /**
     * Whether [path], which held [row]'s bytes with the stat [copy], still does by that stat, beside
     * the live document's own file, which holds them too.
     */
    private suspend fun isExtraCopy(
        row: DocumentEntity,
        path: String,
        copy: FileMeta,
    ): Boolean =
        path != row.relPath && row.isLive && fs.stat(path)?.hasStoredStat(copy.size, copy.mtimeMillis) == true &&
            fs.holdsDocument(row) && !fs.sameFile(path, row.relPath)
}
