package dev.npn.core.watch

/**
 * Whether the folder at [folder] is the library at [library], or a folder inside it. Both are
 * location strings as the settings keep them: a path, or on Android a SAF tree uri. A watched folder
 * there is never swept ([WatchedFolderImporter]): its files are the library's own, which the rescan
 * indexes, and one an import found to be a document's would be deleted as its duplicate. A parent of
 * the library is not inside it; a sweep lists no subfolder.
 *
 * False wherever the platform cannot tell, which leaves such a folder swept as before; each actual
 * says where that is.
 */
expect suspend fun liesInLibrary(
    folder: String,
    library: String,
): Boolean
