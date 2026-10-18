package dev.npn.core.pdf

import dev.npn.core.db.DocumentDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.readDocument
import dev.npn.core.util.runCatchingCancellable

/**
 * Writes/reads first-page thumbnail PNGs into an app-local cache directory (never inside the
 * synced library folder). `DirThumbnailStore` implements it on both JVM targets; the session's
 * wiring (`jvmSessionDeps`, `androidSessionDeps`) picks the directory. The files are the whole
 * cache: the system may clear the directory at any time (Android's cache directory), so whether a
 * thumbnail exists is asked of it, never remembered elsewhere.
 */
interface ThumbnailStore {
    /** The absolute path of [docId]'s thumbnail, or null when the directory holds none. */
    suspend fun existing(docId: String): String?

    /** Persist [png] for [docId]; returns the absolute cache path. */
    suspend fun write(
        docId: String,
        png: ByteArray,
    ): String

    /** Delete every thumbnail; returns how many were deleted. */
    suspend fun clear(): Int
}

/** Cached-or-generate first-page thumbnails. Thumbnails are disposable; [ThumbnailStore] holds them. */
internal class ThumbnailService(
    private val engine: PdfEngine,
    private val fs: LibraryFileSystem,
    private val store: ThumbnailStore,
    private val documentDao: DocumentDao,
    private val renderGate: RenderGate,
) {
    /**
     * Return the cache path of [docId]'s thumbnail, generating it from the first page if
     * absent. Also backfills [dev.npn.core.db.DocumentEntity.pageCount] when it is null (the
     * document is open anyway). Returns null if the file cannot be opened/rendered, or is not the
     * document's: another file at its path would be drawn and counted for it for good.
     */
    suspend fun ensureThumbnail(
        docId: String,
        relPath: String,
    ): String? {
        store.existing(docId)?.let { return it }

        val bytes = runCatchingCancellable { fs.readDocument(relPath, docId) }.getOrNull() ?: return null

        val (png, pageCount) =
            runCatchingCancellable {
                // Gate the heavy open+render so 200 first-load thumbnails cannot run at once and
                // freeze the UI; the gate caps concurrency and uses a CPU-bound dispatcher.
                renderGate.render {
                    engine.open(bytes).use { handle ->
                        // Render first: on Android the count then comes from the open renderer.
                        handle.renderPagePngOrNull(0, MAX_DIM)?.let { png -> png to handle.pageCount }
                    }
                }
            }.getOrNull() ?: return null

        val cachePath = store.write(docId, png)

        // The document is open anyway, so record its page count if the row has none yet.
        documentDao.fillPageCount(docId, pageCount)

        return cachePath
    }

    /**
     * Delete every cached thumbnail so they regenerate on next display. Used to pick up
     * render-quality changes for thumbnails cached under an older, lower-resolution render.
     * Returns the number of thumbnails cleared.
     */
    suspend fun clearCache(): Int = store.clear()

    private companion object {
        /** The longer side of a thumbnail, in pixels. */
        const val MAX_DIM = 480
    }
}
