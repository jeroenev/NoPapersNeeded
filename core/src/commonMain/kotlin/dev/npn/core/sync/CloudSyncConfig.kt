package dev.npn.core.sync

/**
 * Device-local cloud sync settings, projected from the app settings snapshot.
 *
 * [remote] is an rclone remote path (`gdrive:NoPapersNeeded`) or connection string
 * (`:webdav,url=https://host/dav,user=me,pass=<obscured>:NoPapersNeeded`). Credentials live in
 * rclone's own config file (or in the connection string), never in the journal: another device has
 * its own rclone setup, so nothing here is library-wide.
 */
data class CloudSyncConfig(
    val enabled: Boolean = false,
    val remote: String? = null,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Explicit rclone executable; null means the platform default (PATH, or the bundled binary). */
    val rcloneBinary: String? = null,
    /** Explicit `rclone.conf`; null means rclone's default location. */
    val rcloneConfig: String? = null,
) {
    /** A remote is configured when the user named one, whatever the automatic toggle says. */
    val configured: Boolean get() = !remote.isNullOrBlank()

    companion object {
        const val MIN_INTERVAL_MINUTES = 5
        const val MAX_INTERVAL_MINUTES = 24 * 60
        const val DEFAULT_INTERVAL_MINUTES = 15
        val INTERVAL_RANGE = MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES

        val DISABLED = CloudSyncConfig()
    }
}
