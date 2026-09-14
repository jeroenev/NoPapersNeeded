package dev.npn.core.fs

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

/**
 * Android [ExternalIo] over a `ContentResolver`, so a direct-filesystem library can import from
 * share-sheet and document-picker `content://` uris and export to a save-dialog uri exactly as the
 * SAF library does. `file://` uris are served by the resolver too.
 */
class ContentResolverExternalIo(
    context: Context,
) : ExternalIo {
    private val resolver = context.applicationContext.contentResolver

    override fun openInput(source: ExternalSource): InputStream =
        resolver.openInputStream(source.uri)
            ?: throw IOException("Cannot open external source: ${source.uri}")

    override fun openOutput(target: ExternalSource): OutputStream =
        // "w" rather than "wt": the caller-owned target normally comes fresh from a save dialog,
        // and some document providers reject the truncate mode outright (mirrors SafLibraryFileSystem).
        resolver.openOutputStream(target.uri, "w")
            ?: throw IOException("Cannot open external target: ${target.uri}")
}

/**
 * Without a [Context] only `file://` uris can be opened. Production Android wiring passes a
 * [ContentResolverExternalIo]; this default exists so the shared constructor signature holds.
 */
private object FileUriExternalIo : ExternalIo {
    override fun openInput(source: ExternalSource): InputStream = FileInputStream(source.uri.toLocalFile())

    override fun openOutput(target: ExternalSource): OutputStream {
        val file = target.uri.toLocalFile()
        file.parentFile?.mkdirs()
        return FileOutputStream(file, false)
    }

    private fun Uri.toLocalFile(): File {
        if (scheme != null && scheme != "file") {
            throw IOException("Only file:// uris can be opened without a ContentResolver: $this")
        }
        return File(path ?: throw IOException("Uri has no path: $this"))
    }
}

actual fun defaultExternalIo(): ExternalIo = FileUriExternalIo

actual fun externalSourceOfPath(path: Path): ExternalSource = ExternalSource(Uri.fromFile(path.toFile()))
