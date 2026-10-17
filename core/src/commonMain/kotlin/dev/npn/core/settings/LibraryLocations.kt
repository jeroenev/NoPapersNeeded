package dev.npn.core.settings

/**
 * The persisted library location string is one of two shapes, and the platform wiring picks the
 * filesystem implementation by looking at it: a plain absolute path opens the direct-filesystem
 * library (desktop, and Android's default mode), a `content://` tree uri opens Android's Storage
 * Access Framework library. Keeping the mode in the string rather than in a separate setting
 * means an installation that predates the direct mode keeps opening its SAF library unchanged.
 */
object LibraryLocations {
    private const val CONTENT_SCHEME = "content://"

    fun isContentUri(location: String): Boolean = location.startsWith(CONTENT_SCHEME)
}
