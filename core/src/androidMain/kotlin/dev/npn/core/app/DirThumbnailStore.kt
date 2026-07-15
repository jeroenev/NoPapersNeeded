package dev.npn.core.app

import dev.npn.core.pdf.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ThumbnailStore] writing PNGs into a plain directory (Android). The directory is an app-local
 * cache path (from [AppDirs.thumbsDir], typically under `context.cacheDir`), never inside the
 * synced library folder. Uses java.io since the cache dir is a real [File] on Android.
 */
class DirThumbnailStore(private val dir: String) : ThumbnailStore {

    override suspend fun write(docId: String, png: ByteArray): String = withContext(Dispatchers.IO) {
        val d = File(dir)
        d.mkdirs()
        val file = File(d, "$docId.png")
        file.writeBytes(png)
        file.absolutePath
    }

    override suspend fun delete(docId: String) {
        withContext(Dispatchers.IO) {
            File(dir, "$docId.png").delete()
        }
    }
}
