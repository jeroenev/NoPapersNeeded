package dev.npn.app.ui.common

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
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

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource =
    ExternalSource(Paths.get(file.absolutePath()))

actual fun persistDirectoryAccess(dir: PlatformFile): String = dir.absolutePath()

actual fun supportsSharing(): Boolean = false

actual fun supportsLibraryFolderCreation(): Boolean = true

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? =
    withContext(Dispatchers.IO) {
        runCatching {
            val safeName = name.filterNot { it == '/' || it == '\\' || it == '\u0000' }.ifBlank { "library" }
            val parent = Paths.get(parentLocation).toAbsolutePath().normalize()
            val target = parent.resolve(safeName).normalize()
            // Reject anything that escapes the chosen parent (e.g. a name of "..").
            if (target == parent || !target.startsWith(parent)) return@runCatching null
            Files.createDirectories(target)
            target.toString()
        }.getOrNull()
    }

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
) {
    error("Sharing is not supported on desktop; export via the save-file dialog instead.")
}
