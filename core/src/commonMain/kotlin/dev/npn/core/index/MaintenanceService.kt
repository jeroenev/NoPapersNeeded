package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DuplicateDao
import dev.npn.core.db.isLive
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.uniquifyPath
import dev.npn.core.util.runCatchingCancellable

sealed interface ConflictAction {
    /** Keep the file: strip the conflict suffix (uniquify if taken), then it is picked up on rescan. */
    data object Keep : ConflictAction

    data object Discard : ConflictAction

    /** Import as a new document via [ImportService] with a cleaned name. */
    data object ImportAsNew : ConflictAction
}

/**
 * Maintenance operations for external duplicates and sync-conflict files.
 */
class MaintenanceService(
    private val fs: LibraryFileSystem,
    private val documentDao: DocumentDao,
    private val duplicateDao: DuplicateDao,
    private val importService: ImportService,
    /**
     * Runs a removal under the journal coordinator's lock, as imports run, so a rescan cannot list
     * the file, or move a document onto it, while it is checked and deleted.
     */
    private val serialize: suspend (suspend () -> Unit) -> Unit = { it() },
) {
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
            if (row != null && copy != null && isExtraCopy(row, extraRelPath, copy)) fs.delete(extraRelPath)
            duplicateDao.delete(docId, extraRelPath)
        }
    }

    /** The stat [path] had before it was hashed, when it holds [docId]'s bytes; null otherwise. */
    private suspend fun copyOf(
        docId: String,
        path: String,
    ): FileMeta? {
        val meta = fs.stat(path)?.takeIf { !it.isDirectory } ?: return null
        return meta.takeIf { fs.sha256(path) == docId }
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

    /**
     * Resolve an external sync-conflict file at [relPath].
     *
     * - [ConflictAction.Keep]: rename to strip the conflict suffix; if the stripped target is free
     *   use it, otherwise uniquify with " (2)", " (3)", ... Returns the new path. The next rescan
     *   imports it as a normal document.
     * - [ConflictAction.Discard]: delete the file. Returns null.
     * - [ConflictAction.ImportAsNew]: route through [ImportService] with the cleaned name; the
     *   original conflict file is deleted after a successful import. Returns the imported path.
     */
    suspend fun resolveConflictFile(
        relPath: String,
        action: ConflictAction,
    ): String? {
        when (action) {
            is ConflictAction.Discard -> {
                if (fs.exists(relPath)) fs.delete(relPath)
                return null
            }

            is ConflictAction.Keep -> {
                val cleaned = cleanConflictName(relPath)
                val target = uniquify(cleaned)
                fs.move(relPath, target)
                return target
            }

            is ConflictAction.ImportAsNew -> {
                val cleanName = NpnPaths.fileName(cleanConflictName(relPath))
                val bytes = fs.readBytes(relPath)
                val result = importService.importBytes(bytes, cleanName, NpnPaths.DEFAULT_INBOX)
                if (fs.exists(relPath)) runCatchingCancellable { fs.delete(relPath) }
                return when (result) {
                    is ImportResult.Imported -> result.relPath
                    is ImportResult.Duplicate -> result.existingPath
                }
            }
        }
    }

    /**
     * Strip common sync-conflict markers from a file name, keeping the directory and extension.
     * Handles syncthing (`.sync-conflict-...`) and Dropbox/Nextcloud parenthetical forms.
     */
    private fun cleanConflictName(relPath: String): String {
        val dir = NpnPaths.parentDir(relPath)
        val name = NpnPaths.fileName(relPath)

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""

        var cleanedBase = base
        // Syncthing: "name.sync-conflict-YYYYMMDD-..." (the marker sits in the base since we split
        // on the LAST dot, which is the real extension). Cut at the marker.
        val stMarker = cleanedBase.indexOf(NpnPaths.MARKER_SYNCTHING)
        if (stMarker >= 0) cleanedBase = cleanedBase.substring(0, stMarker)
        // Dropbox / Nextcloud parenthetical: "name (conflicted copy ...)" / "(Case Conflict ...)".
        val parenIdx = cleanedBase.indexOf(NpnPaths.MARKER_CONFLICTED_COPY)
        if (parenIdx >= 0) cleanedBase = cleanedBase.substring(0, parenIdx)
        val caseIdx = cleanedBase.indexOf(NpnPaths.MARKER_CASE_CONFLICT)
        if (caseIdx >= 0) cleanedBase = cleanedBase.substring(0, caseIdx)

        cleanedBase = cleanedBase.trim().ifEmpty { "document" }
        return if (dir.isEmpty()) "$cleanedBase$ext" else "$dir/$cleanedBase$ext"
    }

    private suspend fun uniquify(path: String): String =
        uniquifyPath(path, startIndex = 2, suffix = { " ($it)" }, exists = fs::exists)
}
