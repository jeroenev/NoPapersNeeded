package dev.npn.core.watch

import android.net.Uri
import android.provider.DocumentsContract
import dev.npn.core.fs.ExternalStorageTreePaths
import dev.npn.core.settings.LibraryLocations
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * Two paths compare as on desktop ([pathLiesIn]). Two SAF trees of one provider compare by document
 * id ([TreeDocument.liesIn]), and a tree whose provider is device storage also by the path
 * [ExternalStorageTreePaths] maps it to, which is how a tree compares with a path.
 *
 * What it cannot tell: a tree inside another of a provider whose document ids are not paths (a
 * cloud provider's are opaque, so only the library's own tree is found there), and a tree that maps
 * to no path compared with a path. Such a folder is swept as before.
 */
actual suspend fun liesInLibrary(
    folder: String,
    library: String,
    io: CoroutineDispatcher,
): Boolean =
    withContext(io) {
        runCatching {
            val folderTree = TreeDocument.of(folder)
            val libraryTree = TreeDocument.of(library)
            if (folderTree != null && libraryTree != null && folderTree.liesIn(libraryTree)) return@runCatching true
            val folderPath = pathOf(folder, folderTree) ?: return@runCatching false
            val libraryPath = pathOf(library, libraryTree) ?: return@runCatching false
            pathLiesIn(Paths.get(folderPath), Paths.get(libraryPath))
        }.getOrDefault(false)
    }

/** The path [location] names: itself, or the one its [tree] maps to; null for a tree that maps to none. */
private fun pathOf(
    location: String,
    tree: TreeDocument?,
): String? {
    if (tree == null) return location.takeUnless { LibraryLocations.isContentUri(it) }
    return ExternalStorageTreePaths.pathOf(tree.authority, tree.documentId)
}

/** A SAF tree uri's provider and the document id of the folder it grants. */
internal data class TreeDocument(
    val authority: String,
    val documentId: String,
) {
    /**
     * Whether this is [library]'s folder or one below it: the same provider, and [library]'s document
     * id a prefix of this one's on whole path segments, as device storage (`primary:Documents/NPN`)
     * and the downloads provider (`raw:/storage/...`) form them.
     */
    fun liesIn(library: TreeDocument): Boolean {
        if (authority != library.authority) return false
        val outer = library.documentId
        if (documentId == outer) return true
        if (!documentId.startsWith(outer)) return false
        return outer.endsWith('/') || outer.endsWith(':') || documentId[outer.length] == '/'
    }

    companion object {
        /** The tree [location] grants, or null when it is no tree uri. */
        fun of(location: String): TreeDocument? {
            if (!LibraryLocations.isContentUri(location)) return null
            val uri = Uri.parse(location)
            val authority = uri.authority ?: return null
            val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
            return TreeDocument(authority, documentId)
        }
    }
}
