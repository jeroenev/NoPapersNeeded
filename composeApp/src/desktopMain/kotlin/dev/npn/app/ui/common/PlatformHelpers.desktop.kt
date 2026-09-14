package dev.npn.app.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LocalLibraryFolders
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Paths

actual suspend fun loadImageBitmap(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
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
): String? =
    when (access) {
        FolderAccess.DIRECT -> dir.absolutePath()
        FolderAccess.SAF -> null
    }

actual fun supportsSafStorage(): Boolean = false

actual fun defaultLibraryLocation(): String? = null

actual fun supportsSharing(): Boolean = false

actual fun supportsLibraryFolderCreation(): Boolean = true

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? = LocalLibraryFolders.create(parentLocation, name)

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
) {
    error("Sharing is not supported on desktop; export via the save-file dialog instead.")
}
