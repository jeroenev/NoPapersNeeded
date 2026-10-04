package dev.npn.core.pdf

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.ThumbnailDao
import dev.npn.core.db.ThumbnailEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.util.runCatchingCancellable

/**
 * Writes/reads first-page thumbnail PNGs into an app-local cache directory (never inside the
 * synced library folder). Implemented by the app layer per platform.
 */
interface ThumbnailStore {
    /** Persist [png] for [docId]; returns the absolute cache path. */
    suspend fun write(
        docId: String,
        png: ByteArray,
    ): String

    suspend fun delete(docId: String)
}

/**
 * Cached-or-generate first-page thumbnails. Thumbnails are disposable; the [ThumbnailEntity] row
 * records the cache path and generation time.
 */
class ThumbnailService(
    private val engine: PdfEngine,
    private val fs: LibraryFileSystem,
    private val store: ThumbnailStore,
    private val thumbnailDao: ThumbnailDao,
    private val documentDao: DocumentDao,
    private val renderGate: RenderGate,
    private val clock: EpochClock = SystemEpochClock,
) {
    /**
     * Return the cache path of [docId]'s thumbnail, generating it from the first page if
     * absent. Also backfills [dev.npn.core.db.DocumentEntity.pageCount] when it is null (the
     * document is open anyway). Returns null if the file cannot be opened/rendered.
     */
    suspend fun ensureThumbnail(
        docId: String,
        relPath: String,
        maxDim: Int = 480,
    ): String? {
        thumbnailDao.get(docId)?.let { return it.cachePath }

        val bytes = runCatchingCancellable { fs.readBytes(relPath) }.getOrElse { return null }

        val (png, pageCount) =
            runCatchingCancellable {
                // Gate the heavy open+render so 200 first-load thumbnails cannot run at once and
                // freeze the UI; the gate caps concurrency and uses a CPU-bound dispatcher.
                renderGate.render {
                    engine.open(bytes).use { handle ->
                        // Render first: on Android the count then comes from the open renderer.
                        handle.renderPagePngOrNull(0, maxDim)?.let { png -> png to handle.pageCount }
                    }
                }
            }.getOrNull() ?: return null

        val cachePath = store.write(docId, png)
        thumbnailDao.upsert(
            ThumbnailEntity(
                docId = docId,
                cachePath = cachePath,
                generatedAt = clock.nowMillis(),
            ),
        )

        // The document is open anyway, so record its page count if the row has none yet.
        documentDao.fillPageCount(docId, pageCount)

        return cachePath
    }

    /**
     * Delete every cached thumbnail (files + DB rows) so they regenerate on next display. Used to
     * pick up render-quality changes for thumbnails cached under an older, lower-resolution render.
     * Returns the number of cache entries cleared.
     */
    suspend fun clearCache(): Int {
        val all = thumbnailDao.getAll()
        // Best effort: a file left behind is never served again once its row is gone.
        all.forEach { runCatchingCancellable { store.delete(it.docId) } }
        thumbnailDao.deleteAll()
        return all.size
    }
}
