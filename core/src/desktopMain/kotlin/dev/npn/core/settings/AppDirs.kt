package dev.npn.core.settings

/**
 * The desktop app's storage locations for one opened library. Both are absolute paths to plain
 * directories outside the synced library folder so the device-private cache is never synced.
 *
 * - [dbDir]     holds this library's two Room databases: the disposable index, and the device's
 *               own state (`device.db`), which is never dropped.
 * - [thumbsDir] holds cached first-page thumbnail PNGs.
 */
data class AppDirs(
    val dbDir: String,
    val thumbsDir: String,
) {
    companion object {
        /**
         * The directories of the library at [location] under the app's data directory
         * ([PlatformDirs.appDataDir]): `<libraryKey>/db` and `<libraryKey>/thumbs`, each library
         * keyed apart by [LibraryLocations.key].
         */
        fun forLibrary(location: String): AppDirs {
            val base = "${PlatformDirs.appDataDir()}/${LibraryLocations.key(location)}"
            return AppDirs(dbDir = "$base/db", thumbsDir = "$base/thumbs")
        }
    }
}
