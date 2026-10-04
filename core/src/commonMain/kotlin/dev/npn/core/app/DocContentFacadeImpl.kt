package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.RelinkService
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.xmp.BakeService
import dev.npn.core.xmp.NpnXmpData

/**
 * Document-as-bytes facade: thumbnails, page rendering, raw bytes, and XMP baking.
 *
 * Composition:
 * - [ThumbnailService] caches first-page thumbnails against a platform [ThumbnailStore].
 * - [PdfEngine] is opened per call and closed via `use` (kept simple; no long-lived handle cache).
 * - [BakeService] + [NpnXmpData] (assembled from the current DB row + tags) drive export/in-place
 *   bake. In-place bake relinks the new hash through [RelinkService], whose rescan carries the
 *   document's tags and fields onto it and marks the old row superseded.
 *
 * All failure paths return null/false and report through [logFailure]. The calls that write (the
 * thumbnail cache, the page-count backfill, an in-place bake) run as the session's owned work, so
 * closing the session waits for them before its database is closed.
 */
class DocContentFacadeImpl internal constructor(
    private val lifetime: SessionLifetime,
    private val db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val thumbnailService: ThumbnailService,
    private val bakeService: BakeService,
    private val coordinator: JournalCoordinator,
    private val relinkService: RelinkService,
    private val renderGate: RenderGate = RenderGate(),
    private val logFailure: (String, Throwable) -> Unit = { message, failure ->
        println("$message: ${failure.message}")
    },
) : DocContentFacade {
    private val documentDao get() = db.documentDao()

    override suspend fun thumbnailPath(docId: String): String? =
        lifetime.owned {
            val doc = documentDao.getByDocId(docId) ?: return@owned null
            thumbnailService.ensureThumbnail(docId, doc.relPath)
        }

    override suspend fun pageCount(docId: String): Int? = lifetime.owned { readPageCount(docId) }

    private suspend fun readPageCount(docId: String): Int? {
        val doc = documentDao.getByDocId(docId) ?: return null
        doc.pageCount?.let { return it }
        return try {
            val bytes = fs.readBytes(doc.relPath)
            pdfEngine.open(bytes).use { handle ->
                val pc = handle.pageCount
                if (doc.pageCount == null) documentDao.upsert(doc.copy(pageCount = pc))
                pc
            }
        } catch (t: Throwable) {
            logFailure("pageCount failed for $docId", t)
            null
        }
    }

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            val bytes = fs.readBytes(doc.relPath)
            renderGate.render {
                pdfEngine.open(bytes).use { handle ->
                    if (pageIndex < 0 || pageIndex >= handle.pageCount) {
                        null
                    } else {
                        handle.renderPagePng(pageIndex, maxDim)
                    }
                }
            }
        } catch (t: Throwable) {
            logFailure("renderPage failed for $docId p$pageIndex", t)
            null
        }
    }

    override suspend fun clearThumbnailCache(): Int =
        lifetime.owned { runCatching { thumbnailService.clearCache() }.getOrDefault(0) }

    override suspend fun extractedText(docId: String): String? =
        runCatching { db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() } }.getOrNull()

    override suspend fun rawBytes(docId: String): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            fs.readBytes(doc.relPath)
        } catch (t: Throwable) {
            logFailure("rawBytes failed for $docId", t)
            null
        }
    }

    override suspend fun bakedCopyBytes(docId: String): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            bakeService.bakeToBytes(doc.relPath, buildXmpData(docId))
        } catch (t: Throwable) {
            logFailure("bakedCopyBytes failed for $docId", t)
            null
        }
    }

    override suspend fun bakeInPlace(docId: String): Boolean = lifetime.owned { bake(docId) }

    private suspend fun bake(docId: String): Boolean {
        val doc = documentDao.getByDocId(docId) ?: return false
        return try {
            // Rewritten bytes whose relink is then refused would orphan the metadata on the old hash.
            coordinator.checkWritable()
            bakeService.bakeInPlace(doc.relPath, buildXmpData(docId)) { oldSha, newSha ->
                relinkService.applyRelink(oldSha, newSha)
            }
            true
        } catch (t: Throwable) {
            logFailure("bakeInPlace failed for $docId", t)
            false
        }
    }

    /** Assemble the XMP payload from the current DB row, its tags and its field rows. */
    private suspend fun buildXmpData(docId: String): NpnXmpData {
        val doc = documentDao.getByDocId(docId)
        val tags = db.tagDao().tagsForDocument(docId)
        val fields =
            db
                .documentFieldDao()
                .fieldsForDocs(listOf(docId))
                .associate { it.fieldKey to it.fieldValue }
        return NpnXmpData(
            documentId = docId,
            tags = tags,
            reviewed = if (doc?.reviewed == true) "true" else "false",
            fields = fields,
        )
    }
}
