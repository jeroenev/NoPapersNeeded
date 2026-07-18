package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.PendingOp
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
 *   bake. In-place bake emits a relink through the [JournalCoordinator] and updates the documents
 *   row to the new hash, reusing the same relink semantics the [dev.npn.core.index.RelinkService]
 *   relies on.
 *
 * All failure paths return null/false and log via println (a real logger comes later).
 */
class DocContentFacadeImpl(
    private val db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val thumbnailService: ThumbnailService,
    private val bakeService: BakeService,
    private val coordinator: JournalCoordinator,
    private val renderGate: RenderGate = RenderGate(),
) : DocContentFacade {

    private val documentDao get() = db.documentDao()

    override suspend fun thumbnailPath(docId: String): String? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return thumbnailService.ensureThumbnail(docId, doc.relPath)
    }

    override suspend fun pageCount(docId: String): Int? {
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
            println("pageCount failed for $docId: ${t.message}")
            null
        }
    }

    override suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            val bytes = fs.readBytes(doc.relPath)
            renderGate.render {
                pdfEngine.open(bytes).use { handle ->
                    if (pageIndex < 0 || pageIndex >= handle.pageCount) null
                    else handle.renderPagePng(pageIndex, maxDim)
                }
            }
        } catch (t: Throwable) {
            println("renderPage failed for $docId p$pageIndex: ${t.message}")
            null
        }
    }

    override suspend fun clearThumbnailCache(): Int =
        runCatching { thumbnailService.clearCache() }.getOrDefault(0)

    override suspend fun rawBytes(docId: String): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            fs.readBytes(doc.relPath)
        } catch (t: Throwable) {
            println("rawBytes failed for $docId: ${t.message}")
            null
        }
    }

    override suspend fun bakedCopyBytes(docId: String): ByteArray? {
        val doc = documentDao.getByDocId(docId) ?: return null
        return try {
            bakeService.bakeToBytes(doc.relPath, buildXmpData(docId))
        } catch (t: Throwable) {
            println("bakedCopyBytes failed for $docId: ${t.message}")
            null
        }
    }

    override suspend fun bakeInPlace(docId: String): Boolean {
        val doc = documentDao.getByDocId(docId) ?: return false
        return try {
            val result = bakeService.bakeInPlace(doc.relPath, buildXmpData(docId)) { oldSha, newSha ->
                coordinator.commitAndApply(listOf(PendingOp.relink(oldSha, newSha)))
            }
            // Update the documents row to the new identity, carrying metadata over. The relink
            // fold already resolves old->new; here we materialize the new canonical row now so the
            // UI reflects the new docId immediately.
            val fresh = documentDao.getByDocId(docId)
            if (fresh != null) {
                val newSize = fs.stat(result.finalRelPath)?.size ?: fresh.size
                val newMtime = fs.stat(result.finalRelPath)?.mtimeMillis ?: fresh.mtime
                documentDao.upsert(
                    fresh.copy(
                        docId = result.newSha256,
                        relPath = result.finalRelPath,
                        fileName = result.finalRelPath.substringAfterLast('/'),
                        size = newSize,
                        mtime = newMtime,
                        pageCount = null,
                    ),
                )
                // Carry tags to the new id.
                val tags = db.tagDao().tagsForDocument(docId)
                if (tags.isNotEmpty()) db.tagDao().setForDocument(result.newSha256, tags)
                // Mark old row superseded (deleted) so it drops out of listings.
                if (result.newSha256 != docId) {
                    documentDao.upsert(fresh.copy(deleted = true))
                }
            }
            true
        } catch (t: Throwable) {
            println("bakeInPlace failed for $docId: ${t.message}")
            false
        }
    }

    /** Assemble the XMP payload from the current DB row + tags. documentId defaults to the hash. */
    private suspend fun buildXmpData(docId: String): NpnXmpData {
        val doc = documentDao.getByDocId(docId)
        val tags = db.tagDao().tagsForDocument(docId)
        return NpnXmpData(
            documentId = docId,
            tags = tags,
            title = doc?.title,
            correspondent = doc?.correspondent,
            docType = doc?.docType,
            docDate = doc?.docDate,
            reviewed = if (doc?.reviewed == true) "true" else "false",
        )
    }
}
