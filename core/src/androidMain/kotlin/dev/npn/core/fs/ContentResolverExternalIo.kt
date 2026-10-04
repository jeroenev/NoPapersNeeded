package dev.npn.core.fs

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path

/**
 * Android [ExternalIo] over a `ContentResolver`, so a direct-filesystem library can import from
 * share-sheet and document-picker `content://` uris exactly as the SAF library does. `file://` uris
 * are served by the resolver too.
 */
class ContentResolverExternalIo(
    context: Context,
) : ExternalIo {
    private val resolver = context.applicationContext.contentResolver

    override fun openInput(source: ExternalSource): InputStream =
        resolver.openInputStream(source.uri)
            ?: throw IOException("Cannot open external source: ${source.uri}")
}

actual fun externalSourceOfPath(path: Path): ExternalSource = ExternalSource(Uri.fromFile(path.toFile()))
