package dev.npn.core.ocr

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.TagDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.index.FtsWriteGate
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
 * Hybrid OCR pipeline with co-existing OCR versions. Every device+engine pair may publish its
 * own sidecar for a document; the version indexed for search is the highest-priority engine
 * ([OcrEngines.rank]) unless the user pinned an engine per document
 * (`DocumentEntity.ocrEngineOverride`, synced via the journal). A device runs its own engine
 * only when that engine outranks every existing version; otherwise it adopts the best sidecar.
 * See PLAN.md "OCR sidecars" and sec. 5.
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
    private val ftsWriteGate: FtsWriteGate = FtsWriteGate(),
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

        const val ENGINE_EMBEDDED = OcrEngines.EMBEDDED

        private val UNPROCESSED_STATES = setOf(OCR_NONE, OCR_PENDING, OCR_FAILED)
    }

    /** One stored OCR version of a document: a sidecar identified by its writer key. */
    data class Version(
        val writerKey: String,
        val deviceId: String,
        val engine: String,
    )

    /** A fully decoded stored version, for UI listing. */
    data class StoredVersion(
        val writerKey: String,
        val deviceId: String,
        val engine: String,
        val lang: String,
        val createdAt: Long,
    )

    /** Engine label per `<hash>.<writerKey>`, so legacy sidecars are decoded at most once. */
    private val engineCache = HashMap<String, String>()

    /**
     * Pop the next pending queue entry, process it, and return whether something was
     * processed. Drives the state machine pending -> running -> done/failed with attempts.
     */
    suspend fun processNext(): Boolean {
        val entry = ocrQueueDao.nextPending() ?: return false
        ocrQueueDao.upsert(entry.copy(state = STATE_RUNNING, updatedAt = now()))
        processDoc(entry.docId)
        return true
    }

    /**
     * Process a single document by id. Idempotent enough to re-run: it runs this device's
     * engine only when it can improve on the existing versions, then indexes the selected
     * (best or overridden) version.
     */
    suspend fun processDoc(docId: String) {
        val doc = documentDao.getByDocId(docId)
        if (doc == null) {
            // Nothing to do; drop any stray queue entry.
            ocrQueueDao.delete(docId)
            return
        }

        var versions = loadVersions(docId)
        if (versions.isEmpty() || canImprove(versions)) {
            val produced = runOwnOcr(doc)
            if (produced == null && versions.isEmpty()) return // failure already recorded
            versions = loadVersions(docId)
        }

        val desired = select(versions, doc.ocrEngineOverride)
        val sidecar = desired?.let { decode(docId, it.writerKey) }
        if (desired == null || sidecar == null) {
            fail(docId, "no readable ocr version for $docId")
            return
        }
        indexText(docId, sidecar.pages)
        val state = if (desired.deviceId == deviceId) OCR_DONE else OCR_ADOPTED
        setOcrState(docId, state, pageCount = sidecar.pages.size, engine = sidecar.engine)
        // Mark the queue entry done, carrying its attempts count forward.
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

    /**
     * Run this device's extract-or-OCR pass and publish the result as an engine-qualified
     * sidecar (`<hash>.<deviceId>.<engine>.json`, write-once). Returns the writer key, or null
     * on failure (recorded via [fail]). An already-published identical key is adopted, not an
     * error (write-once: never rewrite a published object).
     */
    private suspend fun runOwnOcr(doc: DocumentEntity): String? {
        val bytes = try {
            fs.readBytes(doc.relPath)
        } catch (t: Throwable) {
            fail(doc.docId, "read failed: ${t.message}")
            return null
        }
        val result = try {
            extractOrOcr(bytes)
        } catch (t: Throwable) {
            fail(doc.docId, "ocr failed: ${t.message}")
            return null
        }
        val writerKey = NpnPaths.ocrWriterKey(deviceId, result.engine)
        val sidecar = OcrSidecar(
            doc = doc.docId,
            dev = deviceId,
            engine = result.engine,
            lang = config.lang,
            createdAt = now(),
            pages = result.pages,
        )
        try {
            sidecars.writeOnce(doc.docId, writerKey, OcrSidecarCodec.encode(sidecar))
        } catch (_: Throwable) {
            // Already exists for this device+engine: keep the published file.
            if (decode(doc.docId, writerKey) == null) {
                fail(doc.docId, "sidecar write/read failed for $writerKey")
                return null
            }
        }
        return writerKey
    }

    private class PageResult(val pages: List<String>, val engine: String)

    private suspend fun extractOrOcr(bytes: ByteArray): PageResult {
        val handle = pdfEngine.open(bytes) // throws PdfOpenException on invalid pdf
        try {
            val pages = ArrayList<String>(handle.pageCount)
            var usedOcr = false
            for (i in 0 until handle.pageCount) {
                val embedded = handle.extractText(i)
                if (embedded.trim().length >= config.minEmbeddedCharsPerPage || !ocrEngine.available) {
                    // Usable text layer (or no engine): keep the embedded text as-is.
                    pages.add(embedded)
                } else {
                    val png = handle.renderPagePng(i, config.renderDpiDim)
                    pages.add(ocrEngine.recognizePng(png, config.lang))
                    usedOcr = true
                }
            }
            // The engine label records which OCR engine produced the OCR'd pages; embedded
            // pages are deterministic and identical across devices, so a partially embedded
            // result is still labeled (and ranked) by its OCR engine.
            val engine = if (usedOcr) ocrEngine.name else ENGINE_EMBEDDED
            return PageResult(pages, engine)
        } finally {
            handle.close()
        }
    }

    /**
     * Reconcile every document against the sidecars on disk: adopt text for un-OCR'd docs,
     * re-select when a better (or the overridden) version appeared, and queue an OCR run when
     * this device's engine outranks everything stored. Called after rescans, on `.npn/ocr/`
     * watch events, and after journal ingestion (override changes sync as journal events).
     */
    suspend fun reconcileSidecars() {
        for ((hash, writerKeys) in sidecarsByHash()) {
            val doc = documentDao.getByDocId(hash) ?: continue
            val versions = writerKeys.mapNotNull { versionFor(hash, it) }
            if (versions.isEmpty()) continue
            applySelection(doc, versions)
            maybeQueueUpgrade(doc, versions)
        }
    }

    /**
     * Re-select the indexed version for one document (e.g. after its override changed).
     * No-op when nothing is stored or the indexed version is already the selected one.
     */
    suspend fun reselect(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        val versions = loadVersions(docId)
        if (versions.isEmpty()) return
        applySelection(doc, versions)
        maybeQueueUpgrade(doc, versions)
    }

    /** All stored OCR versions of [docId], decoded, best-ranked first. For UI listing. */
    suspend fun listVersions(docId: String): List<StoredVersion> =
        loadVersions(docId)
            .mapNotNull { v ->
                decode(docId, v.writerKey)?.let {
                    StoredVersion(v.writerKey, v.deviceId, it.engine, it.lang, it.createdAt)
                }
            }
            .sortedWith(compareByDescending<StoredVersion> { OcrEngines.rank(it.engine) }.thenBy { it.writerKey })

    // --- selection ---

    /**
     * Pick the version to index: among versions matching the override engine (all versions
     * when no override or no match), the highest-ranked engine, tie-broken by the lowest
     * writer key so every device converges on the same choice.
     */
    private fun select(versions: List<Version>, override: String?): Version? {
        val pool = versions.filter { it.engine == override }.ifEmpty { versions }
        return pool.maxWithOrNull(
            compareBy<Version> { OcrEngines.rank(it.engine) }.thenByDescending { it.writerKey },
        )
    }

    /** Index [versions]' selected sidecar when the doc is un-OCR'd or indexed with another engine. */
    private suspend fun applySelection(doc: DocumentEntity, versions: List<Version>) {
        val desired = select(versions, doc.ocrEngineOverride) ?: return
        val needsIndex = doc.ocrState in UNPROCESSED_STATES || doc.ocrEngine != desired.engine
        if (!needsIndex) return
        val sidecar = decode(doc.docId, desired.writerKey) ?: return
        indexText(doc.docId, sidecar.pages)
        val state = if (desired.deviceId == deviceId) OCR_DONE else OCR_ADOPTED
        setOcrState(doc.docId, state, pageCount = sidecar.pages.size, engine = sidecar.engine)
        ocrQueueDao.delete(doc.docId)
    }

    /**
     * True when running this device's engine would produce a better version than anything
     * stored: the engine outranks every stored version and this device has not already
     * published a sidecar for that engine. An own `embedded` sidecar also counts as "already
     * tried": it means every page had a text layer, so re-running any engine is pointless.
     */
    private fun canImprove(versions: List<Version>): Boolean {
        if (!ocrEngine.available) return false
        if (versions.isEmpty()) return true
        val ownRank = OcrEngines.rank(ocrEngine.name)
        if (ownRank <= versions.maxOf { OcrEngines.rank(it.engine) }) return false
        return versions.none {
            it.deviceId == deviceId && (it.engine == ocrEngine.name || it.engine == ENGINE_EMBEDDED)
        }
    }

    /**
     * Queue an upgrade OCR run for [doc] when [canImprove]. Never resurrects an active or
     * failed entry, and respects the attempts cap: a doc whose own-engine runs kept failing
     * (it adopted a foreign version instead) is not retried until an explicit re-OCR.
     */
    private suspend fun maybeQueueUpgrade(doc: DocumentEntity, versions: List<Version>) {
        if (doc.trashed || doc.deleted || doc.missing) return
        if (!canImprove(versions)) return
        val entry = queueEntry(doc.docId)
        if (entry != null && (entry.state != STATE_DONE || entry.attempts >= config.maxAttempts)) return
        ocrQueueDao.upsert(
            OcrQueueEntity(
                docId = doc.docId,
                state = STATE_PENDING,
                attempts = entry?.attempts ?: 0,
                updatedAt = now(),
            ),
        )
    }

    // --- stored version discovery ---

    /** Hash -> writer keys for every well-formed sidecar in `.npn/ocr/`. */
    private suspend fun sidecarsByHash(): Map<String, List<String>> {
        val prefix = "${NpnPaths.OCR_DIR}/"
        return sidecars.list()
            .mapNotNull { meta ->
                val fileName = meta.relPath.removePrefix(prefix)
                // Shared lenient parser; additionally require a full 64-char SHA-256 hash here so
                // reconciliation only ever keys off a canonical content hash.
                val (hash, dev) = OcrSidecarFiles.parseSidecarName(fileName) ?: return@mapNotNull null
                if (hash.length != 64) return@mapNotNull null
                hash to dev
            }
            .groupBy({ it.first }, { it.second })
    }

    private suspend fun loadVersions(hash: String): List<Version> =
        sidecars.listForHash(hash).mapNotNull { versionFor(hash, it) }

    /**
     * Resolve a writer key to a [Version]. Engine-qualified keys (`<deviceId>.<engine>`)
     * resolve without I/O; legacy keys (`<deviceId>`) decode the sidecar once to learn its
     * engine (cached). Returns null for unreadable sidecars.
     */
    private suspend fun versionFor(hash: String, writerKey: String): Version? {
        val suffix = writerKey.substringAfterLast('.', "")
        if (suffix.isNotEmpty() && OcrEngines.isKnown(suffix)) {
            return Version(writerKey, writerKey.removeSuffix(".$suffix"), suffix)
        }
        val cacheKey = "$hash.$writerKey"
        val engine = engineCache[cacheKey]
            ?: decode(hash, writerKey)?.engine?.also { engineCache[cacheKey] = it }
            ?: return null
        return Version(writerKey, writerKey, engine)
    }

    /** Read and decode one sidecar, or null when missing/corrupt. */
    private suspend fun decode(hash: String, writerKey: String): OcrSidecar? = try {
        OcrSidecarCodec.decode(sidecars.read(hash, writerKey))
    } catch (_: Throwable) {
        null
    }

    // --- indexing / state helpers ---

    private suspend fun indexText(docId: String, pages: List<String>) = ftsWriteGate.withGate {
        // Under the shared gate so this write cannot interleave with a metadata fold's FTS
        // read-then-replace for the same doc (see FtsWriteGate).
        val doc = documentDao.getByDocId(docId)
        val tags = tagDao.tagsForDocument(docId)
        ftsDao.replaceDocument(
            docId = docId,
            fileName = doc?.fileName,
            title = doc?.title,
            content = OcrSidecar.joinPages(pages),
            tags = tags.joinToString(" ").ifEmpty { null },
            correspondent = doc?.correspondent,
            docType = doc?.docType,
        )
    }

    private suspend fun setOcrState(
        docId: String,
        state: String,
        pageCount: Int? = null,
        engine: String? = null,
    ) {
        // Partial UPDATE (not a row upsert) so this background write cannot clobber metadata
        // columns a concurrent journal fold just projected onto the same row: [pageCount] only
        // fills a NULL page count and a null [engine] keeps the stored engine (see
        // DocumentDao.updateOcrState).
        documentDao.updateOcrState(docId, state, pageCount, engine)
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
