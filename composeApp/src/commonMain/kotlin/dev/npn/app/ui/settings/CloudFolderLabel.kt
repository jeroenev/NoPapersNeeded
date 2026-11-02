package dev.npn.app.ui.settings

import dev.npn.core.sync.RcloneCatalog

/** What stands between the provider and each folder in a [cloudFolderLabel]. */
private const val CLOUD_PATH_SEPARATOR = " › "

/**
 * A library's cloud folder as the user knows it: the provider's name, then the folders down to the
 * library's, such as "Google Drive › NoPapersNeeded" for `drive-3fa9c1:NoPapersNeeded`, rather
 * than the rclone address the wizard stored.
 *
 * The provider is read from the remote's name, which the wizard makes from the provider type
 * ([newCloudRemoteName]), or from the type of a bare connection string
 * (`:webdav,url=…:NoPapersNeeded`). A remote named any other way keeps its own name.
 */
internal fun cloudFolderLabel(remote: String): String {
    val (provider, path) =
        if (remote.startsWith(':')) {
            // A connection string's options may hold colons of their own (a URL): the path follows
            // the last one.
            val type = remote.drop(1).substringBefore(',').substringBefore(':')
            (providerName(type) ?: type.ifBlank { null }) to remote.substringAfterLast(':', "")
        } else {
            val name = remote.substringBefore(':')
            (providerName(name.substringBeforeLast('-')) ?: providerName(name) ?: name) to
                remote.substringAfter(':', "")
        }
    val folders = path.split('/').filter(String::isNotBlank)
    return (listOfNotNull(provider) + folders).joinToString(CLOUD_PATH_SEPARATOR).ifEmpty { remote }
}

/** The catalog's name for the rclone backend [type], or null for one it does not list. */
private fun providerName(type: String): String? = RcloneCatalog.CHOICES.firstOrNull { it.type == type }?.name
