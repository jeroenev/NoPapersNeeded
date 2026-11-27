package dev.npn.core.watch

import android.provider.DocumentsContract
import androidx.core.net.toUri
import dev.npn.core.fs.ExternalStorageTreePaths
import dev.npn.core.settings.LibraryLocations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * The library is a path, and so is a watched folder picked directly: they compare as on desktop
 * ([pathLiesIn]). A watched folder that is a tree uri compares by the path [ExternalStorageTreePaths]
 * maps it to. What it cannot tell: a tree of a provider whose document ids are not paths (a cloud
 * provider's are opaque); such a folder is swept as before.
 */
actual suspend fun liesInLibrary(
    folder: String,
    library: String,
    io: CoroutineDispatcher,
): Boolean =
    withContext(io) {
        runCatching {
            val folderPath = pathOf(folder) ?: return@runCatching false
            pathLiesIn(Paths.get(folderPath), Paths.get(library))
        }.getOrDefault(false)
    }

/** The path [location] names: itself, or the one its tree uri maps to; null for a tree that maps to none. */
private fun pathOf(location: String): String? {
    if (!LibraryLocations.isContentUri(location)) return location
    val uri = location.toUri()
    val authority = uri.authority ?: return null
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
    return ExternalStorageTreePaths.pathOf(authority, documentId)
}
