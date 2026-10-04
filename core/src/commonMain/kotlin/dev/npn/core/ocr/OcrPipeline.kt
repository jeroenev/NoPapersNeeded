package dev.npn.core.ocr

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.DocumentFieldDao
import dev.npn.core.db.FtsDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.TagDao
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.index.FtsRows
import dev.npn.core.index.FtsWriteGate
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.work.backoffMs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tunables for the hybrid extract-or-OCR pipeline.
 *
 * @param minEmbeddedCharsPerPage per-page threshold: a page whose trimmed embedded text has
 *  at least this many characters is treated as already having a usable text layer, so it is
 *  not rendered and OCR'd.
 * @param renderDpiDim max longer-side pixel dimension used when rendering a page for OCR.
 * @param maxAttempts attempts before a queue entry is marked permanently failed. Each failed
 *  attempt first waits out [backoffMs], so a failing document is not retried at once.
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
 *
 * More than one engine can be in play per device (a free local one, optionally a paid vision one);
 * picking between them for a given document is [engines]' job, not this class's.
 *
 * Only touches the `dev.npn.core.ocr`/`dev.npn.core.pdf` surface plus the DAOs it is given.
 */
class OcrPipeline(
    private val fs: LibraryFileSystem,
    private val sidecars: OcrSidecarFiles,
    private val pdfEngine: PdfEngine,
    private val engines: OcrEngineSelector,
    private val documentDao: DocumentDao,
    private val ftsDao: FtsDao,
    private val ocrQueueDao: OcrQueueDao,
    private val tagDao: TagDao,
    private val fieldDao: DocumentFieldDao,
    private val deviceId: String,
    private val config: OcrConfig = OcrConfig(),
    private val ftsWriteGate: FtsWriteGate = FtsWriteGate(),
    private val clock: EpochClock = SystemEpochClock,
) {
    /**
     * Optional hook called whenever text is indexed for a document — including when an existing
     * sidecar is merely adopted or re-selected, and so on every index rebuild. Used by the
     * auto-matching rule engine, whose work is local, deterministic and free to repeat.
     */
    var onTextIndexed: (suspend (docId: String, text: String) -> Unit)? = null

    /**
     * Optional hook called only when *this device just produced* a new OCR sidecar, i.e. the
     * document's text is new here rather than re-indexed. Used by automatic AI enrichment, which
     * costs a paid request per document and so must not fire for work already done.
     *
     * This is deliberately narrower than [onTextIndexed]. The index is a disposable cache: after a
     * destructive rebuild every document is re-queued and re-adopts its stored sidecar, which would
     * otherwise look exactly like a library's worth of brand-new documents.
     */
    var onOcrProduced: (suspend (docId: String) -> Unit)? = null

    /**
     * Optional hook called when a document's file no longer hashes to its docId: it was replaced
     * or is still being written. Nothing is published for it; a rescan sorts out what the file
     * now is.
     */
    var onStaleFile: (suspend (docId: String) -> Unit)? = null

    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"

        const val OCR_NONE = "none"
        const val OCR_PENDING = "pending"
        const val OCR_DONE = "done"
        const val OCR_FAILED = "failed"
        const val OCR_ADOPTED = "adopted"

        const val ENGINE_EMBEDDED = OcrEngines.EMBEDDED

        private val UNPROCESSED_STATES = setOf(OCR_NONE, OCR_PENDING, OCR_FAILED)

        /**
         * SHA-256 of no bytes. Never a document, so never an OCR key: a sidecar under it was
         * written by an older build for a file indexed before its bytes arrived.
         */
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** The device part of a re-run writer key: `<deviceId>.r<runMillis>`. */
        private val RERUN_WRITER = Regex("""(.+)\.r(\d+)""")
    }

    /** One stored OCR version of a document: a sidecar identified by its writer key. */
    data class Version(
        val writerKey: String,
        val deviceId: String,
        val engine: String,
        /** Run stamp of a re-run ([NpnPaths.ocrRerunWriterKey]); 0 for a device's first run. */
        val run: Long = 0,
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
     * Whether an `embedded` sidecar has a usable text layer on every page, per
     * `<hash>.<writerKey>` (see [canImprove]). Sidecars are write-once, so the answer holds.
     */
    private val fullTextLayerCache = HashMap<String, Boolean>()

    private val _inFlight = MutableStateFlow<String?>(null)

    /**
     * The document this device's drain is working on right now, null when it is idle. For anything
     * that reports background work to the user ([dev.npn.core.app.OcrStatus],
     * [dev.npn.core.app.WorkFacade.queue]).
     *
     * Deliberately in-process rather than a `running` state on the queue row: a stored one could
     * only be cleared by the pass that wrote it, so an interrupted pass stranded the row for good
     * (see [dev.npn.core.db.OcrQueueEntity]). Held here, "running" cannot outlive the process that
     * is doing the running, and so cannot be wrong after a restart.
     */
    val inFlight: StateFlow<String?> = _inFlight.asStateFlow()

    /**
     * Pop the next queue entry owed a pass and due now, process it, and return whether something
     * was processed. Drives the state machine pending -> done/failed with attempts.
     *
     * The row is left `pending` throughout and moved only by [processDoc]'s own terminal write, so
     * an interrupted pass is indistinguishable from one that never started and the next drain
     * simply takes it again. Safe because the drain is single-flight in-process
     * ([dev.npn.core.work.QueueDrainer]): no second worker can pop the same row meanwhile.
     */
    suspend fun processNext(): Boolean {
        val entry = ocrQueueDao.nextPending(clock.nowMillis()) ?: return false
        _inFlight.value = entry.docId
        try {
            processDoc(entry.docId)
        } finally {
            _inFlight.value = null
        }
        return true
    }

    /**
     * Process a single document by id. Idempotent enough to re-run: it runs this device's
     * engine only when it can improve on the existing versions, then indexes the selected
     * (best or overridden) version.
     */
    suspend fun processDoc(docId: String) {
        val doc = documentDao.getByDocId(docId)
        if (doc == null || docId == EMPTY_SHA256) {
            // Nothing to do; drop any stray queue entry.
            ocrQueueDao.delete(docId)
            return
        }
        if (doc.missing || doc.deleted || doc.trashed) {
            // No file to read. Unqueued as never processed, so the rescan that brings it back
            // queues it again.
            ocrQueueDao.delete(docId)
            if (doc.ocrState == OCR_PENDING) setOcrState(docId, OCR_NONE)
            return
        }

        var versions = loadVersions(docId)
        var own: OwnOcr? = null
        val entry = ocrQueueDao.getByDocId(docId)
        val aiRequested = entry?.aiRequested == true
        if (versions.isEmpty() || canImprove(doc, versions, rerun = entry?.rerun == true, aiRequested)) {
            own = runOwnOcr(doc, versions, aiRequested)
            if (own == OwnOcr.Stale) {
                // Not this document's bytes, so not its failure either. Left out of the queue: the
                // rescan replaces the row, or refreshes it if the file turns out unchanged.
                ocrQueueDao.delete(docId)
                onStaleFile?.invoke(docId)
                return
            }
            versions = loadVersions(docId)
        }
        val failure = (own as? OwnOcr.Failed)?.error

        val desired = select(versions, doc.ocrEngineOverride)
        val sidecar = desired?.let { decode(docId, it.writerKey) }
        if (desired == null || sidecar == null) {
            fail(docId, failure ?: "no readable ocr version for $docId", textIndexed = false)
            return
        }
        // A failed run still leaves the best stored version searchable meanwhile.
        indexAndMarkSelected(docId, desired, sidecar)
        // Only a sidecar this call published counts as new text. Being queued is not enough: a
        // rebuilt index re-queues every document, and those runs land here and adopt what is
        // already stored.
        val hookError = notifyTextIndexed(docId, sidecar, ocrProduced = own == OwnOcr.Published)
        // The queue row is decided here, once, after the hooks, so an observer watching the queue
        // empty out (the Android maintenance job) cannot see this document finish before the
        // follow-up work they schedule has been recorded.
        if (failure != null) {
            // This device's run is still owed: the attempt, the error and a requested re-run stay.
            fail(docId, failure, textIndexed = true)
        } else {
            // Done even when a hook failed — the text is indexed and searchable, which is what
            // this queue tracks — but the failure is kept on the row rather than dropped, since
            // nothing retries a hook and there is no other trace of it.
            ocrQueueDao.upsert(
                OcrQueueEntity(
                    docId = docId,
                    state = STATE_DONE,
                    attempts = ocrQueueDao.getByDocId(docId)?.attempts ?: 0,
                    updatedAt = clock.nowMillis(),
                    lastError = hookError,
                ),
            )
        }
    }

    /**
     * Run the post-index hooks, [onOcrProduced] only when [ocrProduced], and return the first
     * failure for the caller to record if it has somewhere durable to put it.
     *
     * Each is contained separately. They are follow-up work (auto-match rules, queueing AI
     * enrichment) on a result that is already indexed and searchable, so one throwing must neither
     * abort the queue drain, nor leave this document's entry unresolved, nor cost the document the
     * other hook — which nothing would retry.
     */
    private suspend fun notifyTextIndexed(
        docId: String,
        sidecar: OcrSidecar,
        ocrProduced: Boolean,
    ): String? {
        val hooks =
            listOfNotNull(
                onTextIndexed?.let { hook -> "onTextIndexed" to suspend { hook(docId, sidecar.fullText()) } },
                onOcrProduced?.takeIf { ocrProduced }?.let { hook -> "onOcrProduced" to suspend { hook(docId) } },
            )
        var firstError: String? = null
        for ((name, hook) in hooks) {
            try {
                hook()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (firstError == null) {
                    firstError = "$name: ${t.message ?: t::class.simpleName}"
                }
            }
        }
        return firstError
    }

    /**
     * Run this device's extract-or-OCR pass and publish the result as an engine-qualified
     * sidecar (`<hash>.<deviceId>.<engine>.json`, write-once). An already-published identical key
     * is adopted rather than an error (write-once: never rewrite a published object), which the
     * result distinguishes from [OwnOcr.Published] because adopting text that was already on disk
     * is not the arrival of new text. The caller re-reads the versions either way, so the key
     * itself is not returned.
     *
     * Every failure here is the document's, and is returned for [processDoc] to record. A
     * [CancellationException] is not: it says the drain is being torn down — a closing app, a
     * session ending — not that this document cannot be read, and charging it an attempt would let
     * three interrupted app sessions mark a perfectly good scan permanently failed. It is rethrown,
     * which leaves the row exactly as the pass found it.
     */
    private suspend fun runOwnOcr(
        doc: DocumentEntity,
        stored: List<Version>,
        aiRequested: Boolean,
    ): OwnOcr {
        var step = "read failed"
        val result =
            try {
                val bytes = fs.readBytes(doc.relPath)
                // The sidecar is keyed by docId and is write-once and synced, so text from any other
                // bytes would stick to this document everywhere.
                if (sha256Bytes(bytes) != doc.docId) return OwnOcr.Stale
                step = "ocr failed"
                extractOrOcr(bytes, doc, aiRequested)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return OwnOcr.Failed(describe(step, t))
            }
        val sameEngine = stored.filter { it.engine == result.engine }
        // A re-run that read the same text again adds nothing; keep the library free of copies.
        if (sameEngine.any { decode(doc.docId, it.writerKey)?.pages == result.pages }) return OwnOcr.Adopted
        val writerKey =
            if (sameEngine.any { it.deviceId == deviceId }) {
                NpnPaths.ocrRerunWriterKey(deviceId, clock.nowMillis(), result.engine)
            } else {
                NpnPaths.ocrWriterKey(deviceId, result.engine)
            }
        val sidecar =
            OcrSidecar(
                doc = doc.docId,
                dev = deviceId,
                engine = result.engine,
                lang = result.lang,
                createdAt = clock.nowMillis(),
                pages = result.pages,
            )
        return try {
            sidecars.writeOnce(doc.docId, writerKey, OcrSidecarCodec.encode(sidecar))
            OwnOcr.Published
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Already exists for this device+engine: keep the published file.
            if (decode(doc.docId, writerKey) == null) {
                OwnOcr.Failed(describe("sidecar write/read failed for $writerKey", t))
            } else {
                OwnOcr.Adopted
            }
        }
    }

    /** Outcome of a [runOwnOcr] pass. */
    private sealed interface OwnOcr {
        /** This call wrote the sidecar: new text exists that did not before. */
        data object Published : OwnOcr

        /** The sidecar for this device and engine was already on disk and is readable. */
        data object Adopted : OwnOcr

        /** Nothing usable came out of the pass, for the reason in [error]; nothing was recorded. */
        data class Failed(
            val error: String,
        ) : OwnOcr

        /** The file's bytes no longer hash to the docId; nothing was run or recorded. */
        data object Stale : OwnOcr
    }

    private class PageResult(
        val pages: List<String>,
        val engine: String,
        val lang: String,
    )

    private suspend fun extractOrOcr(
        bytes: ByteArray,
        doc: DocumentEntity,
        aiRequested: Boolean,
    ): PageResult {
        val handle = pdfEngine.open(bytes) // throws PdfOpenException on invalid pdf
        try {
            // Chosen once, off the PDF's real page count (the doc row's may still be null on a
            // first run), so the whole document is transcribed by one engine and its sidecar's
            // engine label is the truth about every OCR'd page in it.
            val engine = engines.forDocument(doc.ocrEngineOverride, handle.pageCount, aiRequested)
            val canOcr = engine.available()
            // Resolved once, like the engine, so every page reads the same languages and the
            // sidecar records the ones actually used.
            val lang = if (canOcr) engine.languagesFor(config.lang) else config.lang
            val pages = ArrayList<String>(handle.pageCount)
            var usedOcr = false
            for (i in 0 until handle.pageCount) {
                val embedded = handle.extractText(i)
                if (embedded.trim().length >= config.minEmbeddedCharsPerPage || !canOcr) {
                    // Usable text layer (or no engine): keep the embedded text as-is.
                    pages.add(embedded)
                } else {
                    val png = handle.renderPagePng(i, config.renderDpiDim)
                    pages.add(engine.recognizePng(png, lang))
                    usedOcr = true
                }
            }
            // The engine label records which OCR engine produced the OCR'd pages; embedded
            // pages are deterministic and identical across devices, so a partially embedded
            // result is still labeled (and ranked) by its OCR engine.
            val name = if (usedOcr) engine.name else ENGINE_EMBEDDED
            return PageResult(pages, name, lang)
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
            if (hash == EMPTY_SHA256) continue
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
            }.sortedWith(compareByDescending<StoredVersion> { OcrEngines.rank(it.engine) }.thenBy { it.writerKey })

    // --- selection ---

    /**
     * Pick the version to index: among versions matching the override engine (all versions
     * when no override or no match), the highest-ranked engine, then the newest re-run of it, then
     * the lowest writer key, so every device converges on the same choice.
     */
    private fun select(
        versions: List<Version>,
        override: String?,
    ): Version? {
        val pool = versions.filter { it.engine == override }.ifEmpty { versions }
        return pool.maxWithOrNull(
            compareBy<Version> { OcrEngines.rank(it.engine) }
                .thenBy { it.run }
                .thenByDescending { it.writerKey },
        )
    }

    /** Index [versions]' selected sidecar when the doc is un-OCR'd or indexed with another engine. */
    private suspend fun applySelection(
        doc: DocumentEntity,
        versions: List<Version>,
    ) {
        val desired = select(versions, doc.ocrEngineOverride) ?: return
        val needsIndex = doc.ocrState in UNPROCESSED_STATES || doc.ocrSource != desired.writerKey
        if (!needsIndex) return
        val sidecar = decode(doc.docId, desired.writerKey) ?: return
        indexAndMarkSelected(doc.docId, desired, sidecar)
        // Adoption or re-selection of a stored sidecar: no new text, so no enrichment. A hook
        // failure is discarded rather than recorded, unlike in processDoc: the row is deleted here,
        // and the only hook that can run is onTextIndexed, which reconcile re-fires on every rescan
        // rather than marking the document done. Nothing is permanently lost by dropping it.
        notifyTextIndexed(doc.docId, sidecar, ocrProduced = false)
        ocrQueueDao.delete(doc.docId)
    }

    /**
     * True when running this device's engine for [doc] would produce a better version than anything
     * stored: the engine outranks every stored version and this device has not already
     * published a sidecar for that engine. An own `embedded` sidecar also counts as "already
     * tried": it means every page had a text layer, so re-running any engine is pointless.
     *
     * Selects with the doc row's page count rather than the PDF's, so this and the run it gates
     * agree about the AI page cap. They have to: without that, a document over the cap would be
     * queued because AI outranks its stored version, produce a local sidecar instead, and be queued
     * again on the next reconcile, forever.
     *
     * A [rerun] the user asked for lowers the bar to "at least as good as the indexed version":
     * re-running an engine that ranks below it would produce text nobody selects. [aiRequested] is
     * the user's "OCR with AI" on this device; see [OcrEngineSelector.forDocument].
     */
    private suspend fun canImprove(
        doc: DocumentEntity,
        versions: List<Version>,
        rerun: Boolean = false,
        aiRequested: Boolean = false,
    ): Boolean {
        val engine = engines.forDocument(doc.ocrEngineOverride, doc.pageCount, aiRequested)
        if (!engine.available()) return false
        if (versions.isEmpty()) return true
        val ownRank = OcrEngines.rank(engine.name)
        if (rerun) return ownRank >= OcrEngines.rank(select(versions, doc.ocrEngineOverride)?.engine)
        val outranksStored = ownRank > versions.maxOf { OcrEngines.rank(it.engine) }
        val triedHere =
            versions.any {
                it.deviceId == deviceId && (it.engine == engine.name || it.engine == ENGINE_EMBEDDED)
            }
        if (!outranksStored || triedHere) return false
        // Another device's `embedded` sidecar whose every page has a usable text layer means this
        // device's run would OCR no page and publish the same text again: without this, a library
        // opened on a new device re-extracts every born-digital document. One with a thin page does
        // not count, since it may come from a device that had no OCR engine to run.
        val fullTextLayer =
            versions.any { version ->
                if (version.engine != ENGINE_EMBEDDED) return@any false
                fullTextLayerCache.getOrPut("${doc.docId}.${version.writerKey}") {
                    val sidecar = decode(doc.docId, version.writerKey) ?: return@any false
                    sidecar.pages.all { it.trim().length >= config.minEmbeddedCharsPerPage }
                }
            }
        return !fullTextLayer
    }

    /**
     * Queue an upgrade OCR run for [doc] when [canImprove]. Never resurrects an active or
     * failed entry, and respects the attempts cap: a doc whose own-engine runs kept failing
     * (it adopted a foreign version instead) is not retried until an explicit re-OCR.
     *
     * Asks the unattended question only: nothing here was asked for on this device, so an `ai` pin
     * synced from another replica queues a paid run only as the automatic setting would.
     */
    private suspend fun maybeQueueUpgrade(
        doc: DocumentEntity,
        versions: List<Version>,
    ) {
        if (doc.trashed || doc.deleted || doc.missing) return
        if (!canImprove(doc, versions)) return
        val entry = ocrQueueDao.getByDocId(doc.docId)
        if (entry != null && (entry.state != STATE_DONE || entry.attempts >= config.maxAttempts)) return
        ocrQueueDao.upsert(
            OcrQueueEntity(
                docId = doc.docId,
                state = STATE_PENDING,
                attempts = entry?.attempts ?: 0,
                updatedAt = clock.nowMillis(),
            ),
        )
    }

    // --- stored version discovery ---

    /** Hash -> writer keys for every well-formed sidecar in `.npn/ocr/`. */
    private suspend fun sidecarsByHash(): Map<String, List<String>> {
        val prefix = "${NpnPaths.OCR_DIR}/"
        return sidecars
            .list()
            .mapNotNull { meta ->
                val fileName = meta.relPath.removePrefix(prefix)
                // Shared lenient parser; additionally require a full 64-char SHA-256 hash here so
                // reconciliation only ever keys off a canonical content hash.
                val (hash, dev) = OcrSidecarFiles.parseSidecarName(fileName) ?: return@mapNotNull null
                if (hash.length != NpnPaths.SHA256_HEX_LENGTH) return@mapNotNull null
                hash to dev
            }.groupBy({ it.first }, { it.second })
    }

    private suspend fun loadVersions(hash: String): List<Version> =
        sidecars.listForHash(hash).mapNotNull { versionFor(hash, it) }

    /**
     * Resolve a writer key to a [Version]. Engine-qualified keys (`<deviceId>.<engine>`)
     * resolve without I/O; legacy keys (`<deviceId>`) decode the sidecar once to learn its
     * engine (cached). Returns null for unreadable sidecars.
     */
    private suspend fun versionFor(
        hash: String,
        writerKey: String,
    ): Version? {
        val suffix = writerKey.substringAfterLast('.', "")
        if (suffix.isNotEmpty() && OcrEngines.isKnown(suffix)) {
            val writer = writerKey.removeSuffix(".$suffix")
            val rerun = RERUN_WRITER.matchEntire(writer)
            val run = rerun?.groupValues?.get(2)?.toLongOrNull()
            return if (rerun != null && run != null) {
                Version(writerKey, rerun.groupValues[1], suffix, run)
            } else {
                Version(writerKey, writer, suffix)
            }
        }
        val cacheKey = "$hash.$writerKey"
        val engine =
            engineCache[cacheKey]
                ?: decode(hash, writerKey)?.engine?.also { engineCache[cacheKey] = it }
                ?: return null
        return Version(writerKey, writerKey, engine)
    }

    /** Read and decode one sidecar, or null when missing/corrupt. */
    private suspend fun decode(
        hash: String,
        writerKey: String,
    ): OcrSidecar? =
        try {
            OcrSidecarCodec.decode(sidecars.read(hash, writerKey))
        } catch (_: Throwable) {
            null
        }

    // --- indexing / state helpers ---

    /**
     * Index the selected [sidecar]'s text for [docId] and record the resulting OCR state on the
     * doc row: [OCR_DONE] when this device produced [desired], else [OCR_ADOPTED]. Shared by the
     * queue-drain path ([processDoc]) and the reconcile/re-select path ([applySelection]); each
     * caller handles its own queue-entry bookkeeping and [onTextIndexed] notification afterwards.
     */
    private suspend fun indexAndMarkSelected(
        docId: String,
        desired: Version,
        sidecar: OcrSidecar,
    ) {
        ftsWriteGate.withGate {
            // Under the shared gate so this write cannot interleave with a metadata fold's FTS
            // read-then-replace for the same doc (see FtsWriteGate).
            val doc = documentDao.getByDocId(docId)
            val tags = tagDao.tagsForDocument(docId)
            val fields = fieldDao.fieldsForDocs(listOf(docId)).associate { it.fieldKey to it.fieldValue }
            ftsDao.replaceDocument(
                FtsRows.row(
                    docId = docId,
                    fileName = doc?.fileName,
                    tags = tags,
                    fields = fields,
                    content = OcrSidecar.joinPages(sidecar.pages),
                ),
            )
        }
        val state = if (desired.deviceId == deviceId) OCR_DONE else OCR_ADOPTED
        setOcrState(docId, state, pageCount = sidecar.pages.size, engine = sidecar.engine, source = desired.writerKey)
    }

    private suspend fun setOcrState(
        docId: String,
        state: String,
        pageCount: Int? = null,
        engine: String? = null,
        source: String? = null,
    ) {
        // Partial UPDATE (not a row upsert) so this background write cannot clobber metadata
        // columns a concurrent journal fold just projected onto the same row: [pageCount] only
        // fills a NULL page count and a null [engine] keeps the stored engine (see
        // DocumentDao.updateOcrState).
        documentDao.updateOcrState(docId, state, pageCount, engine, source)
    }

    /**
     * Charge a failed pass to [docId]: spend an attempt, leave the row pending for a retry after
     * its backoff or failed once the cap is reached, and record [error] as the diagnostic.
     *
     * [textIndexed] says another version's text was indexed for the document by this same pass. The
     * doc row then keeps the state that indexing gave it: it describes the searchable text, and a
     * pending or failed state there would have the next reconcile re-adopt that text and drop the
     * queue row this writes.
     */
    private suspend fun fail(
        docId: String,
        error: String,
        textIndexed: Boolean,
    ) {
        val entry = ocrQueueDao.getByDocId(docId)
        val attempts = (entry?.attempts ?: 0) + 1
        val state = if (attempts >= config.maxAttempts) STATE_FAILED else STATE_PENDING
        val now = clock.nowMillis()
        ocrQueueDao.upsert(
            OcrQueueEntity(
                docId = docId,
                state = state,
                attempts = attempts,
                updatedAt = now,
                nextAttemptAt = now + backoffMs(attempts),
                lastError = error,
                // A retry of a requested run is still that request.
                rerun = entry?.rerun ?: false,
                aiRequested = entry?.aiRequested ?: false,
            ),
        )
        if (!textIndexed) setOcrState(docId, if (state == STATE_FAILED) OCR_FAILED else OCR_PENDING)
    }
}

/** [what] plus [cause]'s message, when it has one: the diagnostic a failure is recorded with. */
private fun describe(
    what: String,
    cause: Throwable,
): String = cause.message?.let { "$what: $it" } ?: what
