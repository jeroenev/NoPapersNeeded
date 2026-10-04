package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DuplicateDao
import dev.npn.core.db.DuplicateEntity
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
) {
    /**
     * Resolve an external duplicate recorded at [extraRelPath] for [docId].
     *
     * - [keepCanonical] true: delete the extra copy, keep the canonical path unchanged.
     * - [keepCanonical] false: make the extra path canonical (delete the current canonical file,
     *   point the doc row at the extra path). Used when the user prefers the other location.
     *
     * The duplicates table row for [extraRelPath] is removed either way.
     */
    suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    ) {
        val doc = documentDao.getByDocId(docId)
        if (keepCanonical || doc == null) {
            if (fs.exists(extraRelPath)) runCatchingCancellable { fs.delete(extraRelPath) }
        } else {
            // Swap: delete the old canonical file, adopt the extra path as canonical.
            if (fs.exists(doc.relPath)) runCatchingCancellable { fs.delete(doc.relPath) }
            documentDao.upsert(
                doc.copy(
                    relPath = extraRelPath,
                    fileName = extraRelPath.substringAfterLast('/'),
                ),
            )
        }
        val remaining = duplicateDao.forDoc(docId).filter { it.relPath != extraRelPath }
        duplicateDao.replaceAll(docId, remaining)
    }

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
                val cleanName = cleanConflictName(relPath).substringAfterLast('/')
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
        val slash = relPath.lastIndexOf('/')
        val dir = if (slash >= 0) relPath.substring(0, slash + 1) else ""
        val name = if (slash >= 0) relPath.substring(slash + 1) else relPath

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
        return "$dir$cleanedBase$ext"
    }

    private suspend fun uniquify(path: String): String =
        uniquifyPath(path, startIndex = 2, suffix = { " ($it)" }, exists = fs::exists)
}
