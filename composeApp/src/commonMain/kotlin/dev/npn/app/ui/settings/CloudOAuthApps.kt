package dev.npn.app.ui.settings

import dev.npn.app.BuildInfo

/**
 * The OAuth apps this build signs in with, as the parameters the setup wizard hands rclone when it
 * creates a remote: per rclone backend type, its `client_id`, `client_secret` and the narrowest
 * scope sync works with. A provider missing here signs in through rclone's own shared app.
 */
class CloudOAuthApps(
    private val params: Map<String, Map<String, String>>,
) {
    /** The parameters a new remote of rclone backend [type] starts with; empty for rclone's app. */
    fun paramsFor(type: String): Map<String, String> = params[type].orEmpty()

    companion object {
        /** rclone's backend type for Google Drive. */
        const val DRIVE = "drive"

        /** No app of this build's own: every provider uses rclone's. */
        val NONE = CloudOAuthApps(emptyMap())

        /** The apps this build was configured with (see `GenerateBuildInfoTask`). */
        val BUILT_IN: CloudOAuthApps get() = of(BuildInfo.DRIVE_CLIENT_ID, BuildInfo.DRIVE_CLIENT_SECRET)

        /**
         * Google Drive through this build's own client, scoped to `drive.file`: the app sees only
         * the files and folders it made itself (the library folder the wizard creates), never the
         * rest of the user's Drive. A blank [driveClientId] keeps rclone's shared app and its full
         * `drive` scope.
         */
        fun of(
            driveClientId: String,
            driveClientSecret: String,
        ): CloudOAuthApps =
            if (driveClientId.isBlank()) {
                NONE
            } else {
                CloudOAuthApps(
                    mapOf(
                        DRIVE to
                            mapOf(
                                "client_id" to driveClientId,
                                "client_secret" to driveClientSecret,
                                "scope" to "drive.file",
                            ),
                    ),
                )
            }
    }
}
