package dev.npn.core.fs

import android.net.Uri

/**
 * Android external location: a content/file [Uri]. The [SafLibraryFileSystem] carries the
 * [android.content.Context] needed to resolve it, so this holds only the uri.
 */
actual class ExternalSource(val uri: Uri)
