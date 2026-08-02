package dev.npn.app.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.PlatformFile

/**
 * Decode an image file at [path] into an [ImageBitmap]. Returns null on failure.
 * commonMain cannot touch java.io, so actuals read the file in their own source set
 * (Android: BitmapFactory, desktop: skia Image.makeFromEncoded).
 */
expect suspend fun loadImageBitmap(path: String): ImageBitmap?

/**
 * Max entries in the process-wide in-memory decoded-thumbnail LRU. Android has a smaller memory
 * budget, so it keeps far fewer ~480px ARGB bitmaps (~900 KB each) cached than desktop.
 */
expect fun thumbCacheMaxEntries(): Int

/**
 * Convert a FileKit [PlatformFile] into a core [ExternalSource] the session can import.
 * Desktop wraps the nio path; Android wraps the content uri.
 */
expect fun platformFileToExternalSource(file: PlatformFile): ExternalSource

/**
 * Persist directory access for a picked library folder and return the location string used
 * by [dev.npn.app.di.AppEnvironment.openLibrary]. Desktop returns the absolute path; Android
 * takes a persistable uri permission and returns the tree uri string.
 */
expect fun persistDirectoryAccess(dir: PlatformFile): String

/**
 * Whether the platform offers a native share sheet. Platforms that do (Android) show a Share
 * action that hands the file to the OS share menu; platforms that don't (desktop) show an
 * Export action that saves the file via a file dialog instead.
 */
expect fun supportsSharing(): Boolean

/**
 * Whether the platform can create a new library folder directly (desktop). Platforms that cannot
 * (Android's SAF tree picker) only offer choosing an existing folder.
 */
expect fun supportsLibraryFolderCreation(): Boolean

/**
 * Create a new library folder named [name] inside [parentLocation] and return its location string,
 * or null on failure. Only called when [supportsLibraryFolderCreation] is true.
 */
expect suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String?

/**
 * Hand the given baked PDF bytes to the platform share sheet (e.g. ACTION_SEND on Android).
 * Only called when [supportsSharing] is true. Throws if sharing could not be offered.
 */
expect suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
)
