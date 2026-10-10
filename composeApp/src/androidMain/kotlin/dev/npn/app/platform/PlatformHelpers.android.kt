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
import java.nio.file.Files
import kotlin.time.Duration.Companion.days

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
 * tree uri. [FolderAccess.DIRECT] maps the uri back to the device-storage path it names (none for
 * cloud providers); [FolderAccess.SAF] takes the persistable grant and, once the system keeps it
 * ([keepTreeAccess]), keeps the uri, as every library was opened before the direct mode existed.
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
            writeShareCopy(File(context.cacheDir, "share"), fileName, bytes, System.currentTimeMillis())
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
 * How long a shared copy is kept: the app it went to may read it any time after the share sheet
 * closes, and the system may clear the cache before then anyway.
 */
private val SHARED_COPY_LIFETIME = 1.days

/**
 * Writes [bytes] as the copy shared as [fileName], in a new directory of its own under [shareDir]:
 * its URI is derived from its path, so a later share, even of a document with the same name, never
 * writes to a file an earlier one handed out. The file keeps the name the receiving app shows. Every
 * copy (or entry of the share directory) older than [SHARED_COPY_LIFETIME] at [nowMillis] is
 * deleted first.
 */
internal fun writeShareCopy(
    shareDir: File,
    fileName: String,
    bytes: ByteArray,
    nowMillis: Long,
): File {
    shareDir.mkdirs()
    val expired = nowMillis - SHARED_COPY_LIFETIME.inWholeMilliseconds
    shareDir.listFiles()?.filter { it.lastModified() < expired }?.forEach { it.deleteRecursively() }
    val dir = Files.createTempDirectory(shareDir.toPath(), null).toFile()
    return shareFile(dir, fileName).apply { writeBytes(bytes) }
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
