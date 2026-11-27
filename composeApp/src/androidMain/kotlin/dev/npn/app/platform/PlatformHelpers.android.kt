package dev.npn.app.platform

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.ExternalStorageTreePaths
import dev.npn.core.fs.LocalLibraryFolders
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File

/** Folder name of the recommended library under shared storage's `Documents/`. */
private const val DEFAULT_LIBRARY_DIR = "NoPapersNeeded"

/** The folder under the app's no-backup files that holds the cloud libraries' working copies. */
private const val CLOUD_LIBRARIES_DIR = "cloud-libraries"

/** The release build's applicationId; other builds append a suffix (see androidApp/build.gradle.kts). */
private const val RELEASE_PACKAGE = "io.github.jeroenev.NoPapersNeeded"

actual suspend fun loadImageBitmap(
    path: String,
    io: CoroutineDispatcher,
): ImageBitmap? =
    withContext(io) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }.getOrNull()
    }

actual fun thumbCacheMaxEntries(): Int = 32

/** Resolve a FileKit [PlatformFile] to a content/file Uri, whichever wrapper it carries. */
private fun PlatformFile.toAndroidUri(): Uri =
    when (val af = androidFile) {
        is AndroidFile.UriWrapper -> af.uri
        is AndroidFile.FileWrapper -> Uri.fromFile(af.file)
    }

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource = ExternalSource(file.toAndroidUri())

/**
 * The system document picker is the only folder picker Android has, so both modes start from its
 * tree uri. [FolderAccess.DIRECT] maps the uri back to the device-storage path it names (none for
 * cloud providers); [FolderAccess.SAF], for a watched folder, takes the persistable grant and, once
 * the system keeps it ([keepTreeAccess]), keeps the uri.
 */
actual fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): FolderPick {
    val uri =
        when (val af = dir.androidFile) {
            is AndroidFile.FileWrapper -> {
                val path = af.file.absolutePath
                return if (access == FolderAccess.DIRECT) FolderPick.Location(path) else FolderPick.Unusable
            }

            is AndroidFile.UriWrapper -> {
                af.uri
            }
        }
    return when (access) {
        FolderAccess.DIRECT -> {
            ExternalStorageTreePaths.localDirectoryForTreeUri(uri)?.let(FolderPick::Location) ?: FolderPick.Unusable
        }

        FolderAccess.SAF -> {
            val resolver = FileKit.context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            keepTreeAccess(uri.toString()) { resolver.takePersistableUriPermission(uri, flags) }
        }
    }
}

/**
 * [location], once [take] has the system keep this app's access to it. Without that grant the access
 * the picker gave lasts only until the app stops, so a refusal ([SecurityException]) is
 * [FolderPick.AccessNotKept], never a location to save.
 */
internal fun keepTreeAccess(
    location: String,
    take: () -> Unit,
): FolderPick =
    try {
        take()
        FolderPick.Location(location)
    } catch (_: SecurityException) {
        FolderPick.AccessNotKept
    }

/**
 * Android's animator duration scale: "Remove animations" (Accessibility settings, and battery saver
 * on some builds) zeroes it, which this asks about rather than guessing from a setting.
 */
actual fun systemAnimationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

actual fun ocrHasLanguageSetting(): Boolean = false

/** Email import is desktop-only: there is no IMAP client or OS mail keyring on this build. */
actual fun supportsEmailImport(): Boolean = false

/**
 * Every build but the release one (debug, and the benchmark build that generates baseline profiles)
 * proposes a folder of its own, named after its applicationId suffix, so installing one next to the
 * release build only touches the real library when that is picked on purpose.
 */
@Suppress("DEPRECATION") // The public Documents directory is exactly where a user-owned library belongs.
actual fun defaultLibraryLocation(): String? {
    val suffix =
        FileKit.context.packageName
            .removePrefix(RELEASE_PACKAGE)
            .removePrefix(".")
    val name = if (suffix.isEmpty()) DEFAULT_LIBRARY_DIR else "$DEFAULT_LIBRARY_DIR-$suffix"
    return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), name).absolutePath
}

actual fun cloudLibrariesDir(): String = File(FileKit.context.noBackupFilesDir, CLOUD_LIBRARIES_DIR).absolutePath

actual fun isAppPrivateLocation(location: String): Boolean {
    val own =
        FileKit.context.noBackupFilesDir
            .toPath()
            .toAbsolutePath()
            .normalize()
    return File(location)
        .toPath()
        .toAbsolutePath()
        .normalize()
        .startsWith(own)
}

actual fun supportsSharing(): Boolean = true

/** Files come in by share, scan and picker instead. */
actual fun acceptsFileDrops(): Boolean = false

/**
 * Only a sideloaded APK (the GitHub release) looks for updates itself. Google Play updates its own
 * installs, and its policy forbids an app pointing them at another source; an install whose
 * source cannot be read counts as sideloaded.
 */
actual fun checksForUpdates(): Boolean = !installedFromPlay

private const val PLAY_STORE_PACKAGE = "com.android.vending"

private val installedFromPlay: Boolean by lazy {
    val context = FileKit.context
    runCatching { context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName }
        .getOrNull() == PLAY_STORE_PACKAGE
}

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? = LocalLibraryFolders.create(parentLocation, name)

actual suspend fun copyToClipboard(text: String) {
    val context = FileKit.context.applicationContext
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("No Papers Needed", text))
}

/** Android's settings are shared preferences, not a file the user can reach; the device unlocks instead. */
actual fun appLockRecoveryFile(): String? = null

actual fun usesOnScreenKeyboard(): Boolean = true
