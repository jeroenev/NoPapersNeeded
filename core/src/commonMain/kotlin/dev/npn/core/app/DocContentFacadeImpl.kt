package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.RelinkService
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import dev.npn.core.xmp.BakeRefusedException
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
 * Reads return null on failure and an in-place bake a [BakeOutcome.Failed]; both log the cause to
 * [log]. The calls that write (the thumbnail cache, the page-count backfill, an in-place bake) run
 * as the session's owned work, so closing the session waits for them before its database is closed.
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
    private val renderGate: RenderGate,
    private val log: NpnLog,
) : DocContentFacade {
    private val documentDao get() = db.documentDao()

    override suspend fun thumbnailPath(docId: String): String? =
        log.runLogged("Making the thumbnail of $docId") {
            lifetime.owned {
                val doc = documentDao.getByDocId(docId) ?: return@owned null
                thumbnailService.ensureThumbnail(docId, doc.relPath)
            }
        }

    override suspend fun pageCount(docId: String): Int? =
        log.runLogged("Reading the page count of $docId") { lifetime.owned { readPageCount(docId) } }

    private suspend fun readPageCount(docId: String): Int? {
        val doc = documentDao.getByDocId(docId) ?: return null
        doc.pageCount?.let { return it }
        val bytes = fs.readBytes(doc.relPath)
        return pdfEngine.open(bytes).use { handle ->
            handle.pageCount.also { documentDao.fillPageCount(docId, it) }
        }
    }

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? =
        log.runLogged("Rendering page $pageIndex of $docId") {
            val doc = documentDao.getByDocId(docId) ?: return@runLogged null
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
        }

    override suspend fun clearThumbnailCache(): Int =
        lifetime.owned { log.runLogged("Clearing the thumbnail cache") { thumbnailService.clearCache() } ?: 0 }

    override suspend fun extractedText(docId: String): String? =
        log.runLogged("Reading the text of $docId") { db.ftsDao().contentForDoc(docId)?.takeIf { it.isNotBlank() } }

    override suspend fun rawBytes(docId: String): ByteArray? =
        log.runLogged("Reading $docId") {
            val doc = documentDao.getByDocId(docId) ?: return@runLogged null
            fs.readBytes(doc.relPath)
        }

    override suspend fun bakedCopyBytes(docId: String): ByteArray? =
        log.runLogged("Baking a copy of $docId") {
            val doc = documentDao.getByDocId(docId) ?: return@runLogged null
            bakeService.bakeToBytes(doc.relPath, buildXmpData(docId))
        }

    override suspend fun bakeInPlace(docId: String): BakeOutcome = lifetime.owned { bake(docId) }

    private suspend fun bake(docId: String): BakeOutcome {
        val doc =
            documentDao.getByDocId(docId)?.takeUnless { it.trashed || it.deleted || it.missing }
                ?: return BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
        return runCatchingCancellable {
            // Rewritten bytes whose relink is then refused would orphan the metadata on the old hash.
            coordinator.checkWritable()
            bakeService.bakeInPlace(doc.relPath, buildXmpData(docId)) { oldSha, newSha ->
                relinkService.applyRelink(oldSha, newSha)
            }
            BakeOutcome.Baked
        }.getOrElse { failure ->
            log.warn("Baking $docId in place failed", failure)
            BakeOutcome.Failed(
                if (failure is BakeRefusedException) BakeFailure.UNSUPPORTED_PDF else BakeFailure.NOT_SAVED,
            )
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
