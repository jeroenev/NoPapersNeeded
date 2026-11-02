package dev.npn.app.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LocalLibraryFolders
import dev.npn.core.settings.PlatformDirs
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Paths

actual suspend fun loadImageBitmap(
    path: String,
    io: CoroutineDispatcher,
): ImageBitmap? =
    withContext(io) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            val bytes = file.readBytes()
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull()
    }

actual fun thumbCacheMaxEntries(): Int = 256

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource =
    ExternalSource(Paths.get(file.absolutePath()))

actual fun persistDirectoryAccess(
    dir: PlatformFile,
    access: FolderAccess,
): FolderPick =
    when (access) {
        FolderAccess.DIRECT -> FolderPick.Location(dir.absolutePath())
        FolderAccess.SAF -> FolderPick.Unusable
    }

actual fun supportsSafStorage(): Boolean = false

actual fun ocrHasLanguageSetting(): Boolean = true

actual fun defaultLibraryLocation(): String? = null

actual fun cloudLibrariesDir(): String = File(PlatformDirs.appDataDir(), CLOUD_LIBRARIES_DIR).path

/** Desktop asks for no storage grant, so nothing is exempt from one. */
actual fun isAppPrivateLocation(location: String): Boolean = false

/** The folder under the app's data directory that holds the cloud libraries' working copies. */
private const val CLOUD_LIBRARIES_DIR = "cloud-libraries"

actual fun supportsSharing(): Boolean = false

/** Flatpak sets FLATPAK_ID inside the sandbox; Flathub ships the updates there. */
actual fun checksForUpdates(): Boolean = System.getenv("FLATPAK_ID").isNullOrBlank()

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? = LocalLibraryFolders.create(parentLocation, name)

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
    io: CoroutineDispatcher,
) {
    error("Sharing is not supported on desktop; export via the save-file dialog instead.")
}
