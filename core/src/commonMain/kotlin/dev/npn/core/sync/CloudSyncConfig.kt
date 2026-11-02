package dev.npn.core.sync

import kotlinx.serialization.Serializable

/**
 * One library's cloud sync settings on this device, kept in the library's `device.db` beside the
 * manifest ([SyncService]), so each library has a remote of its own and switching library never
 * points one library at another's folder.
 *
 * [remote] is an rclone remote path (`drive-3fa9c1:NoPapersNeeded`), or a connection string
 * (`:webdav,url=https://host/dav,user=me,pass=<obscured>:NoPapersNeeded`) for a remote set up
 * outside the wizard. Credentials live in the app's rclone config file (or in the connection
 * string), never in the journal: another device has its own rclone setup, so nothing here is
 * library-wide.
 */
@Serializable
data class CloudSyncConfig(
    val enabled: Boolean = false,
    val remote: String? = null,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
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
