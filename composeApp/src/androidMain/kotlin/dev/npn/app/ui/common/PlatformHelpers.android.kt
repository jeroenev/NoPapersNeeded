package dev.npn.app.ui.common

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.npn.core.fs.ExternalSource
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual suspend fun loadImageBitmap(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val file = File(path)
        if (!file.exists()) return@runCatching null
        BitmapFactory.decodeFile(path)?.asImageBitmap()
    }.getOrNull()
}

/** Resolve a FileKit [PlatformFile] to a content/file Uri, whichever wrapper it carries. */
private fun PlatformFile.toAndroidUri(): Uri = when (val af = androidFile) {
    is AndroidFile.UriWrapper -> af.uri
    is AndroidFile.FileWrapper -> Uri.fromFile(af.file)
}

actual fun platformFileToExternalSource(file: PlatformFile): ExternalSource =
    ExternalSource(file.toAndroidUri())

actual fun persistDirectoryAccess(dir: PlatformFile): String {
    val uri = dir.toAndroidUri()
    val resolver = FileKit.context.contentResolver
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { resolver.takePersistableUriPermission(uri, flags) }
    return uri.toString()
}
