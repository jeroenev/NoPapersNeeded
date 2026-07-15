package dev.npn.core.ocr

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.TagDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.pdf.PdfEngine
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

/**
 * Tunables for the OCR pipeline (PLAN.md sec. 5, hybrid extract-or-OCR strategy).
 *
 * @param minEmbeddedCharsPerPage per-page threshold: a page whose trimmed embedded text has
 *  at least this many characters is treated as already having a usable text layer, so it is
 *  not rendered and OCR'd.
 * @param renderDpiDim max longer-side pixel dimension used when rendering a page for OCR.
 * @param maxAttempts attempts before a queue entry is marked permanently failed.
 */
data class OcrConfig(
    val lang: String = "eng",
    val minEmbeddedCharsPerPage: Int = 32,
    val renderDpiDim: Int = 2000,
    val maxAttempts: Int = 3,
)

/**
 * Hybrid OCR pipeline: adopt an existing sidecar if any device already produced one for the
 * hash, otherwise extract embedded text per page and OCR the pages that lack it. Results are
 * written as an OCR sidecar and indexed into FTS. See PLAN.md "OCR sidecars" and sec. 5.
 *
 * Only touches the `dev.npn.core.ocr`/`dev.npn.core.pdf` surface plus the DAOs it is given.
 */
class OcrPipeline(
    private val fs: LibraryFileSystem,
    private val sidecars: OcrSidecarFiles,
    private val pdfEngine: PdfEngine,
    private val ocrEngine: OcrEngine,
    private val documentDao: DocumentDao,
    private val ftsDao: FtsDao,
    private val ocrQueueDao: OcrQueueDao,
    private val tagDao: TagDao,
    private val deviceId: String,
    private val config: OcrConfig = OcrConfig(),
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"

        const val OCR_NONE = "none"
        const val OCR_PENDING = "pending"
        const val OCR_DONE = "done"
        const val OCR_FAILED = "failed"
        const val OCR_ADOPTED = "adopted"

        const val ENGINE_EMBEDDED = "embedded"
        const val ENGINE_MIXED = "mixed"
    }

    /**
     * Pop the next pending queue entry, process it, and return whether something was
     * processed. Drives the state machine pending -> running -> done/failed with attempts.
     */
    suspend fun processNext(): Boolean {
        val entry = ocrQueueDao.nextPending() ?: return false
        markRunning(entry)
        processDoc(entry.docId)
        return true
    }

    private suspend fun markRunning(entry: OcrQueueEntity) {
        ocrQueueDao.upsert(
            entry.copy(state = STATE_RUNNING, updatedAt = now()),
        )
    }

    /**
     * Process a single document by id. Idempotent enough to re-run: it adopts an existing
     * sidecar (own or foreign) rather than re-OCRing.
     */
    suspend fun processDoc(docId: String) {
        val doc = documentDao.getByDocId(docId)
        if (doc == null) {
            // Nothing to do; drop any stray queue entry.
            ocrQueueDao.delete(docId)
            return
        }

        // 1. Adopt any existing sidecar for this hash (from any device), picking the
        //    lexicographically-first deviceId deterministically. Do not re-OCR.
        val existingDevices = sidecars.listForHash(docId)
        if (existingDevices.isNotEmpty()) {
            val chosen = existingDevices.min()
            try {
                val sidecar = OcrSidecarCodec.decode(sidecars.read(docId, chosen))
                indexText(docId, sidecar.pages)
                setOcrState(docId, OCR_ADOPTED)
                markDone(docId)
                return
            } catch (_: Throwable) {
                // Corrupt sidecar: fall through to re-OCR.
            }
        }

        // 2. Read the PDF and process per page.
        val bytes = try {
            fs.readBytes(doc.relPath)
        } catch (t: Throwable) {
            fail(docId, "read failed: ${t.message}")
            return
        }

        val result = try {
            extractOrOcr(bytes)
        } catch (t: Throwable) {
            fail(docId, "ocr failed: ${t.message}")
            return
        }

        // 3. Write the sidecar (write-once). Tolerate our own existing file (adopt it).
        val sidecar = OcrSidecar(
            doc = docId,
            dev = deviceId,
            engine = result.engine,
            lang = config.lang,
            createdAt = now(),
            pages = result.pages,
        )
        try {
            sidecars.writeOnce(docId, deviceId, OcrSidecarCodec.encode(sidecar))
        } catch (_: Throwable) {
            // Already exists for THIS device: adopt our own file rather than failing.
            try {
                val own = OcrSidecarCodec.decode(sidecars.read(docId, deviceId))
                indexText(docId, own.pages)
                setOcrState(docId, OCR_DONE)
                markDone(docId)
                return
            } catch (t: Throwable) {
                fail(docId, "sidecar write/read failed: ${t.message}")
                return
            }
        }

        // 4. Index and mark done.
        indexText(docId, result.pages)
        setOcrState(docId, OCR_DONE, pageCount = result.pages.size)
        markDone(docId)
    }

    private class PageResult(val pages: List<String>, val engine: String)

    private suspend fun extractOrOcr(bytes: ByteArray): PageResult {
        val handle = pdfEngine.open(bytes) // throws PdfOpenException on invalid pdf
        try {
            val pages = ArrayList<String>(handle.pageCount)
            var usedEmbedded = false
            var usedOcr = false
            for (i in 0 until handle.pageCount) {
                val embedded = handle.extractText(i)
                if (embedded.trim().length >= config.minEmbeddedCharsPerPage) {
                    pages.add(embedded)
                    usedEmbedded = true
                } else if (ocrEngine.available) {
                    val png = handle.renderPagePng(i, config.renderDpiDim)
                    val text = ocrEngine.recognizePng(png, config.lang)
                    pages.add(text)
                    usedOcr = true
                } else {
                    // No text layer and no OCR engine: keep whatever embedded text existed.
                    pages.add(embedded)
                    if (embedded.isNotEmpty()) usedEmbedded = true
                }
            }
            val engine = when {
                usedEmbedded && usedOcr -> ENGINE_MIXED
                usedOcr -> ocrEngine.name
                else -> ENGINE_EMBEDDED
            }
            return PageResult(pages, engine)
        } finally {
            handle.close()
        }
    }

    /**
     * Adopt every foreign sidecar whose document exists in the DB in an un-OCR'd state
     * (none/pending/failed). Called after rescans so a device picks up text another device
     * produced. Removes the doc from the OCR queue once adopted.
     */
    suspend fun adoptForeignSidecars() {
        val prefix = "${NpnPaths.OCR_DIR}/"
        val byHash = sidecars.list()
            .mapNotNull { meta ->
                val fileName = meta.relPath.removePrefix(prefix)
                // <sha256>.<deviceId>.json
                if (!fileName.endsWith(".json")) return@mapNotNull null
                val stem = fileName.removeSuffix(".json")
                val dot = stem.indexOf('.')
                if (dot <= 0) return@mapNotNull null
                val hash = stem.substring(0, dot)
                val dev = stem.substring(dot + 1)
                if (hash.length != 64 || dev.isEmpty()) return@mapNotNull null
                hash to dev
            }
            .groupBy({ it.first }, { it.second })

        for ((hash, devices) in byHash) {
            val doc = documentDao.getByDocId(hash) ?: continue
            if (doc.ocrState !in setOf(OCR_NONE, OCR_PENDING, OCR_FAILED)) continue
            val chosen = devices.min()
            val sidecar = try {
                OcrSidecarCodec.decode(sidecars.read(hash, chosen))
            } catch (_: Throwable) {
                continue
            }
            indexText(hash, sidecar.pages)
            setOcrState(hash, OCR_ADOPTED)
            ocrQueueDao.delete(hash)
        }
    }

    // --- indexing / state helpers ---

    private suspend fun indexText(docId: String, pages: List<String>) {
        val doc = documentDao.getByDocId(docId)
        val tags = tagDao.tagsForDocument(docId)
        ftsDao.replaceDocument(
            docId = docId,
            fileName = doc?.fileName,
            title = doc?.title,
            content = pages.joinToString("\n\n"),
            tags = tags.joinToString(" ").ifEmpty { null },
            correspondent = doc?.correspondent,
            docType = doc?.docType,
        )
    }

    private suspend fun setOcrState(docId: String, state: String, pageCount: Int? = null) {
        val doc = documentDao.getByDocId(docId) ?: return
        val newPageCount = if (doc.pageCount == null && pageCount != null) pageCount else doc.pageCount
        documentDao.upsert(doc.copy(ocrState = state, pageCount = newPageCount))
    }

    private suspend fun markDone(docId: String) {
        ocrQueueDao.upsert(
            OcrQueueEntity(
                docId = docId,
                state = STATE_DONE,
                attempts = queueEntry(docId)?.attempts ?: 0,
                updatedAt = now(),
                lastError = null,
            ),
        )
    }

    private suspend fun fail(docId: String, error: String) {
        val attempts = (queueEntry(docId)?.attempts ?: 0) + 1
        val state = if (attempts >= config.maxAttempts) STATE_FAILED else STATE_PENDING
        ocrQueueDao.upsert(
            OcrQueueEntity(
                docId = docId,
                state = state,
                attempts = attempts,
                updatedAt = now(),
                lastError = error,
            ),
        )
        setOcrState(docId, if (state == STATE_FAILED) OCR_FAILED else OCR_PENDING)
    }

    /**
     * One-shot read of a queue row. [OcrQueueDao] exposes no single-row suspend getter, only
     * [OcrQueueDao.observeQueue]; take its first emission (the current snapshot).
     */
    private suspend fun queueEntry(docId: String): OcrQueueEntity? =
        ocrQueueDao.observeQueue().first().firstOrNull { it.docId == docId }

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()
}
