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
 * temporary file whose document is missing (a bake interrupted between deleting the original and
 * moving the baked bytes into its place).
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
        val paths by lazy { listing.mapTo(HashSet()) { it.relPath } }
        for (meta in listing.filter { !it.isDirectory && it.mtimeMillis in 1 until cutoff }) {
            val deletable =
                when (NpnPaths.Temp.kindOf(meta.relPath)) {
                    null -> false
                    NpnPaths.Temp.Kind.BAKE -> NpnPaths.Temp.bakedDocument(meta.relPath) in paths
                    else -> true
                }
            if (deletable) {
                log.runLogged(
                    "Deleting the stale temporary file ${meta.relPath}",
                ) { fs.delete(meta.relPath) }
            }
        }
    }

    companion object {
        /** How old a temporary file must be before it counts as left behind. */
        const val STALE_AFTER_MS = 24L * 60L * 60L * 1000L
    }
}
