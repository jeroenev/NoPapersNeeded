package dev.npn.core.fs

import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged

/**
 * Deletes the app's temporary files ([NpnPaths.Temp]) that a crash or a killed process left in the
 * library, once they are [STALE_AFTER_MS] old: no write in progress keeps one that long. Most of
 * them sit in the user's folders, where a sync tool would carry them to every device.
 *
 * Never a file that may hold the only copy of a document: a recovery file (a move interrupted
 * mid-way leaves the document under that name, and a rescan indexes it like any PDF), or a bake's
 * temporary file unless the file at its document's path holds the same bytes (a bake interrupted
 * between deleting the original and moving the baked bytes into its place leaves the only copy
 * there, and another file can take the path since).
 */
internal class TempFileSweep(
    private val fs: LibraryFileSystem,
    private val log: NpnLog,
) {
    private var internalSwept = false

    /**
     * Delete the stale temporary files in [library], a rescan's listing of the library outside
     * `.npn/`; the first time in a session, list `.npn/` and do the same there.
     */
    suspend fun sweep(
        library: List<FileMeta>,
        nowMillis: Long,
    ) {
        deleteStale(library, nowMillis)
        if (internalSwept) return
        internalSwept = true
        log
            .runLogged("Listing ${NpnPaths.NPN_DIR} for stale temporary files") { fs.listAllUnder(NpnPaths.NPN_DIR) }
            ?.let { deleteStale(it, nowMillis) }
    }

    /** Delete the stale temporary files in [listing], which also holds the files beside them. */
    private suspend fun deleteStale(
        listing: List<FileMeta>,
        nowMillis: Long,
    ) {
        val cutoff = nowMillis - STALE_AFTER_MS
        val byPath by lazy { listing.associateBy { it.relPath } }
        for (meta in listing.filter { !it.isDirectory && it.mtimeMillis in 1 until cutoff }) {
            // A name the library cannot address is none of its temporary files.
            if (!NpnPaths.isSafe(meta.relPath)) continue
            val deletable =
                when (NpnPaths.Temp.kindOf(meta.relPath)) {
                    null -> false

                    NpnPaths.Temp.Kind.BAKE -> spareBake(meta, byPath)

                    NpnPaths.Temp.Kind.WRITE,
                    NpnPaths.Temp.Kind.MOVE,
                    NpnPaths.Temp.Kind.RELOCATION,
                    NpnPaths.Temp.Kind.IMPORT,
                    NpnPaths.Temp.Kind.MARKER_STAGING,
                    NpnPaths.Temp.Kind.JOURNAL_STAGING,
                    -> true
                }
            if (deletable) {
                log.runLogged(
                    "Deleting the stale temporary file ${meta.relPath}",
                ) { fs.delete(meta.relPath) }
            }
        }
    }

    /**
     * Whether [bake], a bake's temporary file, is a spare copy: the file at its document's path in
     * [byPath] holds the same bytes, the same size and SHA-256. Otherwise it may be the document's
     * only copy, and is kept; so is one that cannot be read.
     */
    private suspend fun spareBake(
        bake: FileMeta,
        byPath: Map<String, FileMeta>,
    ): Boolean {
        val document = NpnPaths.Temp.bakedDocument(bake.relPath)?.let(byPath::get) ?: return false
        if (document.isDirectory || document.size != bake.size) return false
        return log.runLogged("Comparing ${bake.relPath} with ${document.relPath}") {
            fs.sha256(bake.relPath) == fs.sha256(document.relPath)
        } == true
    }

    companion object {
        /** How old a temporary file must be before it counts as left behind. */
        const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    }
}
