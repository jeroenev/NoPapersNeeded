package dev.npn.core.app

import dev.npn.core.pdf.ThumbnailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [ThumbnailStore] writing PNGs into a plain directory (desktop). The directory is an app-local
 * cache path (from [AppDirs.thumbsDir]), never inside the synced library folder.
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
