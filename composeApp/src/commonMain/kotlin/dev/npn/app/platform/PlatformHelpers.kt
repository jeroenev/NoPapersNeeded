package dev.npn.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Decode an image file at [path] into an [ImageBitmap]. Returns null on failure.
 * commonMain cannot touch java.io, so actuals read the file in their own source set
 * (Android: BitmapFactory, desktop: skia Image.makeFromEncoded).
 */
expect suspend fun loadImageBitmap(
    path: String,
    io: CoroutineDispatcher = Dispatchers.IO,
): ImageBitmap?

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

/** What a picked folder came to in [persistDirectoryAccess]. */
sealed interface FolderPick {
    /** The location string of [dev.npn.app.di.AppEnvironment.openLibrary] and the watched-folder setting. */
    data class Location(
        val value: String,
    ) : FolderPick

    /**
     * The folder has no location in that access: on Android, [FolderAccess.DIRECT] has no
     * device-storage path for a cloud provider's folder; desktop has no [FolderAccess.SAF].
     */
    data object Unusable : FolderPick

    /**
     * Android gave [FolderAccess.SAF] access to the folder for now but would not keep it, so it
     * would be gone by a later start: the folder must not replace the one chosen before.
     */
    data object AccessNotKept : FolderPick
}

/**
 * Persist access to a picked folder in [access] and return its location, or why there is none:
 * on Android, [FolderAccess.DIRECT] resolves the picker's tree uri to a device-storage path, and
 * [FolderAccess.SAF] keeps the uri only once the system keeps this app's access to it.
 */
expect fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): FolderPick

/**
 * Whether the platform offers [FolderAccess.SAF] at all (Android). Gates the advanced
 * "Use Storage Access Framework" setting and the onboarding escape hatch.
 */
expect fun supportsSafStorage(): Boolean

/**
 * Whether OCR takes a language setting. Desktop's Tesseract needs to be told which languages to
 * recognise; Android's MLKit Latin recogniser reads every Latin-script language without one, so
 * Android hides the picker.
 */
expect fun ocrHasLanguageSetting(): Boolean

/**
 * Whether this platform offers email import (IMAP). Desktop does; Android does not, so the settings
 * category is absent there rather than a dead control.
 */
expect fun supportsEmailImport(): Boolean

/**
 * The folder the platform recommends for a new library, or null when it has no opinion. Android
 * proposes `Documents/NoPapersNeeded` on shared storage: visible in any file manager, reachable
 * by Syncthing, and it survives an uninstall. Desktop leaves the choice to the user's file dialog.
 */
expect fun defaultLibraryLocation(): String?

/**
 * Where the app keeps the local working copy of a library the user keeps in a cloud drive: a
 * folder of the app's own that the user never has to choose, needs no storage grant, and is left
 * out of the platform's backups (the cloud holds the library). Android: the app's no-backup files;
 * desktop: the app's data directory.
 */
expect fun cloudLibrariesDir(): String

/**
 * Whether [location] is in the app's own storage ([cloudLibrariesDir]), where no storage grant
 * applies.
 */
expect fun isAppPrivateLocation(location: String): Boolean

/**
 * Whether the platform offers a native share sheet. Platforms that do (Android) show a Share
 * action that hands the file to the OS share menu; platforms that don't (desktop) show an
 * Export action that saves the file via a file dialog instead.
 */
expect fun supportsSharing(): Boolean

/** Whether files dropped on the window are imported (desktop), so the UI may show a drop zone. */
expect fun acceptsFileDrops(): Boolean

/**
 * Whether the app should look for a newer release itself. False where a package manager already
 * keeps it up to date (the desktop app inside Flatpak).
 */
expect fun checksForUpdates(): Boolean

/**
 * Whether the platform currently wants animations: false when the user turned them off
 * (Android's "Remove animations" setting, which zeroes the animator duration scale). The app's own
 * reduced-motion setting adds to this; see [dev.npn.core.settings.reducesMotion].
 */
expect fun systemAnimationsEnabled(): Boolean

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
    io: CoroutineDispatcher = Dispatchers.IO,
)

/**
 * Put [text] on the system clipboard, for a report the user asked to keep or pass on. Throws when
 * the platform refuses; the caller reports that instead of claiming the copy landed.
 */
expect suspend fun copyToClipboard(text: String)

/**
 * The file that holds this device's settings, for the app lock's recovery copy: a forgotten PIN is
 * reset by deleting the app-lock entries (or the file) while the app is closed. Null where the
 * settings are not a plain file the user can reach (Android), whose recovery is the device's own
 * unlock or clearing the app's data.
 */
expect fun appLockRecoveryFile(): String?
