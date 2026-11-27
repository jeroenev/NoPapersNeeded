package dev.npn.core.fs

import android.content.Context
import java.io.IOException
import java.io.InputStream

/**
 * Android [ExternalIo] over a `ContentResolver`, so the library can import from share-sheet,
 * document-picker and watched-folder `content://` uris. `file://` uris are served by the resolver
 * too.
 */
internal class ContentResolverExternalIo(
    context: Context,
) : ExternalIo {
    private val resolver = context.applicationContext.contentResolver

    override fun openInput(source: ExternalSource): InputStream =
        resolver.openInputStream(source.uri)
            ?: throw IOException("Cannot open external source: ${source.uri}")
}
