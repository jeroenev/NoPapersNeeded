package dev.npn.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * [ThumbnailStore] writing PNGs into a plain directory: an app-local cache path (desktop's
 * `AppDirs.thumbsDir`, under `context.cacheDir` on Android), never inside the synced library folder.
 */
class DirThumbnailStore(
    private val dir: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ThumbnailStore {
    override suspend fun existing(docId: String): String? =
        withContext(io) { fileOf(docId).takeIf { it.isFile }?.absolutePath }

    override suspend fun write(
        docId: String,
        png: ByteArray,
    ): String =
        withContext(io) {
            File(dir).mkdirs()
            val file = fileOf(docId)
            // Written aside and moved into place: the file's presence is the cache entry, so a
            // write cut short must not leave a truncated PNG under the name served.
            val partial = File(dir, "$docId$SUFFIX.part")
            partial.writeBytes(png)
            Files.move(
                partial.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            file.absolutePath
        }

    override suspend fun clear(): Int =
        withContext(io) {
            // Best effort: a file left behind is only served again until it is cleared next time.
            File(dir).listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }.orEmpty().count { it.delete() }
        }

    private fun fileOf(docId: String) = File(dir, "$docId$SUFFIX")

    private companion object {
        const val SUFFIX = ".png"
    }
}
