package dev.npn.app.platform

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import dev.npn.core.app.LibraryLocations
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.ExternalStorageTreePaths
import dev.npn.core.fs.LocalLibraryFolders
import dev.npn.core.fs.NpnPaths
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Folder name of the recommended library under shared storage's `Documents/`. */
private const val DEFAULT_LIBRARY_DIR = "NoPapersNeeded"

/** The release build's applicationId; other builds append a suffix (see androidApp/build.gradle.kts). */
private const val RELEASE_PACKAGE = "dev.npn"

actual suspend fun loadImageBitmap(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
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
 * tree uri. [FolderAccess.DIRECT] maps the uri back to the device-storage path it names (null for
 * cloud providers, which have none); [FolderAccess.SAF] takes the persistable grant and keeps the
 * uri, exactly as every library was opened before the direct mode existed.
 */
actual fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): String? {
    val uri =
        when (val af = dir.androidFile) {
            is AndroidFile.FileWrapper -> return if (access == FolderAccess.DIRECT) af.file.absolutePath else null
            is AndroidFile.UriWrapper -> af.uri
        }
    return when (access) {
        FolderAccess.DIRECT -> {
            ExternalStorageTreePaths.localDirectoryForTreeUri(uri)
        }

        FolderAccess.SAF -> {
            val resolver = FileKit.context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { resolver.takePersistableUriPermission(uri, flags) }
            uri.toString()
        }
    }
}

actual fun supportsSafStorage(): Boolean = true

actual fun ocrHasLanguageSetting(): Boolean = false

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

actual fun supportsSharing(): Boolean = true

/** The APK is sideloaded from the GitHub release, so nothing else updates it. */
actual fun checksForUpdates(): Boolean = true

actual fun supportsLibraryFolderCreation(): Boolean = true

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? {
    if (LibraryLocations.isContentUri(parentLocation)) return null
    return LocalLibraryFolders.create(parentLocation, name)
}

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
) {
    val context = FileKit.context.applicationContext
    val file =
        withContext(Dispatchers.IO) {
            shareFile(File(context.cacheDir, "share").apply { mkdirs() }, fileName).apply { writeBytes(bytes) }
        }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * The file in [dir] a copy shared as [fileName] is written to: [fileName] made one safe file name,
 * so a title never reaches a folder of its own or outside [dir]. A name that still would (a blank
 * one names [dir] itself) throws instead of writing anywhere else.
 */
internal fun shareFile(
    dir: File,
    fileName: String,
): File {
    val file = File(dir, NpnPaths.sanitizeFileName(fileName))
    require(file.canonicalFile.parentFile == dir.canonicalFile) { "Not a file name: $fileName" }
    return file
}
