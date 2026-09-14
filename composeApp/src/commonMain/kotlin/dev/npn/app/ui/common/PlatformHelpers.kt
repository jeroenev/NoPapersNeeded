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
 * How a picked folder is handed to the app. [DIRECT] is a plain filesystem path the app reads and
 * writes itself: the only mode on desktop and the default on Android, where it is what lets a sync
 * tool such as Syncthing share the folder. [SAF] is Android's Storage Access Framework tree uri,
 * kept as an advanced option for folders that have no path (cloud document providers) and for
 * libraries opened that way before the direct mode existed.
 */
enum class FolderAccess { DIRECT, SAF }

/**
 * Persist access to a picked folder and return the location string used by
 * [dev.npn.app.di.AppEnvironment.openLibrary] and the watched-folder setting, or null when the
 * folder cannot be used in [access]: on Android, [FolderAccess.DIRECT] resolves the picker's tree
 * uri to a device-storage path and has none for cloud providers; [FolderAccess.SAF] is refused on
 * desktop, which has no document providers.
 */
expect fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): String?

/**
 * Whether the platform offers [FolderAccess.SAF] at all (Android). Gates the advanced
 * "Use Storage Access Framework" setting and the onboarding escape hatch.
 */
expect fun supportsSafStorage(): Boolean

/**
 * The folder the platform recommends for a new library, or null when it has no opinion. Android
 * proposes `Documents/NoPapersNeeded` on shared storage: visible in any file manager, reachable
 * by Syncthing, and it survives an uninstall. Desktop leaves the choice to the user's file dialog.
 */
expect fun defaultLibraryLocation(): String?

/**
 * Whether the platform offers a native share sheet. Platforms that do (Android) show a Share
 * action that hands the file to the OS share menu; platforms that don't (desktop) show an
 * Export action that saves the file via a file dialog instead.
 */
expect fun supportsSharing(): Boolean

/**
 * Whether the platform can create a new library folder inside a [FolderAccess.DIRECT] parent.
 * True everywhere now that Android has plain paths; SAF parents are still refused by
 * [createLibraryFolder].
 */
expect fun supportsLibraryFolderCreation(): Boolean

/**
 * Create a new library folder named [name] inside [parentLocation] (a [FolderAccess.DIRECT]
 * location) and return its location string, or null on failure. Creating an existing directory
 * succeeds, so the recommended default folder is opened through this too.
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
