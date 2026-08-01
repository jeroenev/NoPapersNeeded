package dev.npn.app.ui.common

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun loadImageBitmap(path: String): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.exists()) return@runCatching null
            BitmapFactory.decodeFile(path)?.asImageBitmap()
        }.getOrNull()
    }

/** Resolve a FileKit [PlatformFile] to a content/file Uri, whichever wrapper it carries. */
private fun PlatformFile.toAndroidUri(): Uri =
    when (val af = androidFile) {
        is AndroidFile.UriWrapper -> af.uri
        is AndroidFile.FileWrapper -> Uri.fromFile(af.file)
    }

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource = ExternalSource(file.toAndroidUri())

actual fun persistDirectoryAccess(dir: PlatformFile): String {
    val uri = dir.toAndroidUri()
    val resolver = FileKit.context.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { resolver.takePersistableUriPermission(uri, flags) }
    return uri.toString()
}

actual fun supportsSharing(): Boolean = true

actual fun supportsLibraryFolderCreation(): Boolean = false

actual suspend fun createLibraryFolder(
    parentLocation: String,
    name: String,
): String? = null

actual suspend fun sharePdfBytes(
    fileName: String,
    bytes: ByteArray,
) {
    val context = FileKit.context.applicationContext
    val file = File(context.cacheDir, "share").apply { mkdirs() }.resolve(fileName)
    withContext(Dispatchers.IO) { file.writeBytes(bytes) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val share =
        Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    context.startActivity(Intent.createChooser(share, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
