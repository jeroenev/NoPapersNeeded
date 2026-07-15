package dev.npn.core.pdf

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.ThumbnailDao
import dev.npn.core.db.ThumbnailEntity
import dev.npn.core.fs.LibraryFileSystem
import kotlin.time.Clock

/**
 * Writes/reads first-page thumbnail PNGs into an app-local cache directory (never inside the
 * synced library folder). Implemented by the app layer per platform.
 */
interface ThumbnailStore {
    /** Persist [png] for [docId]; returns the absolute cache path. */
    suspend fun write(docId: String, png: ByteArray): String

    suspend fun delete(docId: String)
}

/**
 * Cached-or-generate first-page thumbnails (PLAN.md sec. 5, library views). Thumbnails are a
 * disposable cache; the [ThumbnailEntity] row records the cache path and generation time.
 */
class ThumbnailService(
    private val engine: PdfEngine,
    private val fs: LibraryFileSystem,
    private val store: ThumbnailStore,
    private val thumbnailDao: ThumbnailDao,
    private val documentDao: DocumentDao,
) {
    /**
     * Return the cache path of [docId]'s thumbnail, generating it from the first page if
     * absent. Also backfills [dev.npn.core.db.DocumentEntity.pageCount] when it is null (the
     * document is open anyway). Returns null if the file cannot be opened/rendered.
     */
    suspend fun ensureThumbnail(docId: String, relPath: String, maxDim: Int = 480): String? {
        thumbnailDao.get(docId)?.let { return it.cachePath }

        val bytes = try {
            fs.readBytes(relPath)
        } catch (_: Throwable) {
            return null
        }

        val (png, pageCount) = try {
            engine.open(bytes).use { handle ->
                if (handle.pageCount <= 0) return null
                handle.renderPagePng(0, maxDim) to handle.pageCount
            }
        } catch (_: Throwable) {
            return null
        }

        val cachePath = store.write(docId, png)
        thumbnailDao.upsert(
            ThumbnailEntity(
                docId = docId,
                cachePath = cachePath,
                generatedAt = Clock.System.now().toEpochMilliseconds(),
            ),
        )

        // Backfill pageCount if unknown. DocumentDao exposes only upsert, so read-modify-upsert.
        val doc = documentDao.getByDocId(docId)
        if (doc != null && doc.pageCount == null) {
            documentDao.upsert(doc.copy(pageCount = pageCount))
        }

        return cachePath
    }
}
