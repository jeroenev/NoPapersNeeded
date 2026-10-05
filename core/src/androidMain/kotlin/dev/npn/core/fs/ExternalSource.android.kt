package dev.npn.core.fs

import android.net.Uri

/**
 * Android external location: a content/file [Uri]. The library filesystem resolves it through a
 * `ContentResolver` ([SafLibraryFileSystem], or [ContentResolverExternalIo] for a direct-path
 * library), so this holds only the uri.
 */
actual class ExternalSource(
    val uri: Uri,
)
