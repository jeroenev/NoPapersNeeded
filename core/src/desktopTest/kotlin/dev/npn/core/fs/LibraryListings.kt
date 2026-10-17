package dev.npn.core.fs

/** The files in the library's trash, directories left out. */
suspend fun LibraryFileSystem.trashEntries(): List<FileMeta> =
    listAllUnder(NpnPaths.TRASH_DIR).filter {
        !it.isDirectory
    }

/** The paths of the library's PDFs, as a rescan lists them. */
suspend fun LibraryFileSystem.libraryPdfs(): List<String> =
    listAllIndexable().filter { !it.isDirectory }.map { it.relPath }
