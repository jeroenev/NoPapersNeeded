package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.Sha256Digest
import dev.npn.core.fs.readDocument
import dev.npn.core.fs.toHexLower
import dev.npn.core.index.JournalCoordinator
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
 * Document-as-bytes facade: thumbnails, page rendering, and XMP baking.
 *
 * Composition:
 * - [ThumbnailService] caches first-page thumbnails against a platform [ThumbnailStore].
 * - [PdfEngine] is opened per call and closed via `use` (kept simple; no long-lived handle cache).
 * - [BakeService] + [NpnXmpData] (assembled from the current DB row + tags) drive export/in-place
 *   bake. In-place bake relinks the new hash through [relinkBake] ([BakeRelink]): the relink's rescan
 *   carries the document's tags and fields onto it and marks the old row superseded, and the baked
 *   document keeps its original's text.
 *
 * Reads return null on failure and an in-place bake a [BakeOutcome.Failed]; both log the cause to
 * [log]. The calls that write (the thumbnail cache, the page-count backfill, an in-place bake) run
 * as the session's owned work, so closing the session waits for them before its database is closed.
 */
internal class DocContentFacadeImpl(
    private val lifetime: SessionLifetime,
    private val db: NpnDatabase,
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val thumbnailService: ThumbnailService,
    private val bakeService: BakeService,
    private val coordinator: JournalCoordinator,
    /** Relinks a bake's original to its baked bytes, handing its text on ([BakeRelink.relink]). */
    private val relinkBake: suspend (original: String, baked: String) -> Unit,
    private val renderGate: RenderGate,
    /**
     * Held by each bake in place, the whole of it: every bake of a path writes the same temporary
     * file ([dev.npn.core.fs.NpnPaths.Temp.bakePath]), which the temp sweep relies on, and the relink
     * a bake journals must name the bytes it installed. A trash or delete for good waits on it too.
     */
    private val bakes: BakeLock,
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
        val bytes = fs.readDocument(doc.relPath, docId) ?: return null
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
                pdfEngine.open(bytes).use { handle -> handle.renderPagePngOrNull(pageIndex, maxDim) }
            }
        }

    override suspend fun clearThumbnailCache(): Int =
        lifetime.owned { log.runLogged("Clearing the thumbnail cache") { thumbnailService.clearCache() } ?: 0 }

    override suspend fun extractedText(docId: String): String? =
        log.runLogged("Reading the text of $docId") { db.ftsDao().textOf(docId) }

    override suspend fun streamDocument(
        docId: String,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean =
        log.runLogged("Streaming the bytes of $docId") {
            val doc = documentDao.getByDocId(docId) ?: return@runLogged false
            // Hashed as it streams, so the bytes are proven the document's without a second read.
            val digest = Sha256Digest()
            fs.readChunked(doc.relPath) { bytes, length ->
                digest.update(bytes, 0, length)
                sink(bytes, length)
            }
            digest.digest().toHexLower() == docId
        } ?: false

    override suspend fun bakedCopyBytes(docId: String): ByteArray? =
        log.runLogged("Baking a copy of $docId") {
            val doc = documentDao.getByDocId(docId) ?: return@runLogged null
            bakeService.bakeToBytes(doc.relPath, docId, buildXmpData(doc))
        }

    override suspend fun bakeInPlace(docId: String): BakeOutcome = lifetime.owned { bakes.held { bake(docId) } }

    /**
     * Run under [bakes], its row read there too: a bake queued behind another of the same document
     * finds it superseded, or its file holding other bytes, rather than share the temporary file.
     */
    private suspend fun bake(docId: String): BakeOutcome {
        val doc =
            documentDao.getByDocId(docId)?.takeIf { it.isLive }
                ?: return BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE)
        return runCatchingCancellable {
            // Rewritten bytes whose relink is then refused would orphan the metadata on the old hash.
            coordinator.checkWritable()
            // Read after that wait, and baked only while it is the document's: the file at its path
            // may be another one since the index last looked, or become one at any time.
            val baked =
                bakeService.bakeInPlace(doc.relPath, docId, buildXmpData(doc)) { oldSha, newSha ->
                    relinkBake(oldSha, newSha)
                }
            if (baked == null) BakeOutcome.Failed(BakeFailure.DOCUMENT_UNAVAILABLE) else BakeOutcome.Baked
        }.getOrElse { failure ->
            log.warn("Baking $docId in place failed", failure)
            BakeOutcome.Failed(
                if (failure is BakeRefusedException) BakeFailure.UNSUPPORTED_PDF else BakeFailure.NOT_SAVED,
            )
        }
    }

    /** Assemble the XMP payload from the document's row, its tags and its field rows. */
    private suspend fun buildXmpData(doc: DocumentEntity): NpnXmpData =
        NpnXmpData(
            documentId = doc.docId,
            tags = db.tagDao().tagsForDocument(doc.docId),
            reviewed = if (doc.reviewed) "true" else "false",
            fields = db.documentFieldDao().valuesOf(doc.docId),
        )
}
