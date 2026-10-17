package dev.npn.core.app

import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.settings.AppDirs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ThumbnailStore] writing PNGs into a plain directory: an app-local cache path (from
 * [AppDirs.thumbsDir], under `context.cacheDir` on Android), never inside the synced library folder.
 */
class DirThumbnailStore(
    private val dir: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThumbnailStore {
    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String =
        withContext(io) {
            val d = File(dir)
            d.mkdirs()
            val file = File(d, "$docId.png")
            file.writeBytes(png)
            file.absolutePath
        }

    override suspend fun delete(docId: String) {
        withContext(io) {
            File(dir, "$docId.png").delete()
        }
    }
}
