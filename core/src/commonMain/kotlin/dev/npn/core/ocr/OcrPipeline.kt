package dev.npn.core.ocr

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsRows
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrPageCacheEntity
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.isLive
import dev.npn.core.db.queueState
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.readDocument
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteSettle
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.util.EpochClock
import dev.npn.core.util.SystemEpochClock
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.work.QueueRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Hybrid OCR pipeline with co-existing OCR versions. Every device+engine pair may publish its
 * own sidecar for a document; the version indexed for search is the highest-priority engine
 * ([OcrEngineId.rank]) unless the user pinned an engine per document
 * (`DocumentEntity.ocrEngineOverride`, synced via the journal). A device runs its own engine
 * only when that engine outranks every existing version; otherwise it adopts the best sidecar.
 *
 * More than one engine can be in play per device (a free local one, optionally a paid vision one);
 * picking between them for a given document is [engines]' job, not this class's.
 *
 * Only touches the `dev.npn.core.ocr`/`dev.npn.core.pdf` surface plus the [index] and the OCR queue
 * it is given.
 *
 * @param language the OCR languages a run asks its engine for (`nld+eng`), read once per document so a
 *  change in Settings applies to the next one.
 * New text and re-indexed text are told apart: text is new to the library only when *this device
 * produced* a new sidecar for the run it settles, by the pass that published it, or by the next pass
 * of its run when that one was cut short first. Text adopted or re-selected from a stored sidecar (a
 * rescan, a sidecar another device published, a reopened session, a rebuilt index, whose rescan
 * re-queues every document to re-adopt what is stored) is not new, and fires no hook: to work that
 * writes into the journal or costs a request, a rebuild would otherwise look like a library's worth
 * of new documents.
 *
 * @param onTextProduced called, with the text, for text new to the library: the auto-match rules,
 *  which write what they set into the journal.
 * @param onOcrProduced called, after [onTextProduced], for the same new text: work that costs a paid
 *  request each (queueing automatic AI enrichment), so it sees what the rules set.
 * @param onStaleFile called when a document's file no longer hashes to its docId: it was replaced
 *  or is still being written. Nothing is published for it; a rescan sorts out what the file now is.
 */
internal class OcrPipeline(
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val engines: OcrEngineSelector,
    private val index: NpnDatabase,
    private val ocrQueueDao: OcrQueueDao,
    private val deviceId: String,
    private val language: suspend () -> String,
    private val clock: EpochClock = SystemEpochClock,
    private val onTextProduced: suspend (docId: String, text: String) -> Unit = { _, _ -> },
    private val onOcrProduced: suspend (docId: String) -> Unit = {},
    private val onStaleFile: suspend (docId: String) -> Unit = {},
) {
    companion object {
        /**
         * Per-page threshold: a page whose trimmed embedded text has at least this many characters
         * is treated as already having a usable text layer, so it is not rendered and OCR'd.
         */
        const val MIN_EMBEDDED_CHARS_PER_PAGE = 32

        /** Max longer-side pixel dimension used when rendering a page for OCR. */
        const val RENDER_DIM = 2000

        /**
         * Attempts before a queue entry is marked permanently failed. Each failed attempt first
         * waits out the [QueueRetryPolicy] backoff, so a failing document is not retried at once.
         */
        const val MAX_ATTEMPTS = 3

        private val UNPROCESSED_STATES = setOf(OcrState.NONE, OcrState.PENDING, OcrState.FAILED)

        /**
         * SHA-256 of no bytes. Never a document, so never an OCR key: a sidecar under it was
         * written by an older build for a file indexed before its bytes arrived.
         */
        private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        /** A queue row's error after a read found other bytes than the document's. */
        private const val STALE_FILE = "the file did not hold the document's bytes when read"
    }

    private val retry = QueueRetryPolicy(MAX_ATTEMPTS)
    private val documentDao = index.documentDao()

    /** What is stored for each document, and which version of it to index. */
    private val versions = OcrVersions(OcrSidecarFiles(fs), MIN_EMBEDDED_CHARS_PER_PAGE)

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
     * Documents whose [onTextProduced] was refused because journal writes were blocked
     * ([MetadataWriteBlocked]): nothing else runs the rules for their new text, so the next call
     * that goes through runs them again ([retryOwedRules]). In memory only: a restart forgets them,
     * and then a later edit or rule save applies the rules.
     */
    private val rulesOwed = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Pop the next queue entry owed a pass and due now, process it, and return whether something
     * was processed. Drives the state machine pending -> done/failed with attempts.
     *
     * The row is left `pending` throughout and moved only by [processDoc]'s own terminal write, so
     * an interrupted pass is indistinguishable from one that never started and the next drain
     * simply takes it again. Safe because the drain is single-flight in-process
     * ([dev.npn.core.work.QueueDrainer]): no second worker can pop the same row meanwhile. A request
     * recorded on the row while the pass ran outlives it ([OcrQueueDao.settle]): the row stays
     * pending with it, and the drain takes it again.
     */
    suspend fun processNext(): Boolean {
        val entry = ocrQueueDao.nextPending(clock.nowMillis())
        if (entry == null) {
            // The drain is over: the next one lists `.npn/ocr/` afresh, so it sees what other devices
            // published meanwhile even where no watch event or rescan announced it.
            versions.forget()
            return false
        }
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
    internal suspend fun processDoc(docId: String) {
        val doc = documentDao.getByDocId(docId)
        if (doc == null || docId == EMPTY_SHA256) {
            // Nothing to do; drop any stray queue entry.
            ocrQueueDao.delete(docId)
            return
        }
        if (!doc.isLive) {
            // No file to read. Unqueued as never processed, so the rescan that brings it back
            // queues it again.
            ocrQueueDao.delete(docId)
            if (OcrState.fromColumn(doc.ocrState) == OcrState.PENDING) setOcrState(docId, OcrState.NONE)
            return
        }

        var stored = versions.of(docId)
        var own: OwnOcr? = null
        val entry = ocrQueueDao.getByDocId(docId)
        val aiRequested = entry?.aiRequested == true
        // Judged by the versions that read: a sidecar cut short mid-write neither outranks this
        // device's run nor stands for its try.
        val readable = versions.readable(docId, stored)
        if (readable.isEmpty() || canImprove(doc, readable, rerun = entry?.rerun == true, aiRequested)) {
            own = runOwnOcr(doc, stored, aiRequested)
            if (own == OwnOcr.Stale) {
                // Not this document's bytes, so not its failure either, the first time: the run
                // waits, uncharged, for the rescan [onStaleFile] asks for to sort out what the file
                // is now. Other bytes read again are charged, so a file no rescan changes (one whose
                // size and mtime stayed, or an empty one) is given up on at the attempt cap.
                val first = entry?.lastError != STALE_FILE
                if (first) awaitRescan(docId, seen = entry)
                onStaleFile(docId)
                if (first) return
                own = OwnOcr.Failed(STALE_FILE)
            }
            stored = versions.of(docId)
        }
        val failure = (own as? OwnOcr.Failed)?.error

        val selected = versions.selectReadable(docId, stored, doc.ocrEngineOverride)
        if (selected == null) {
            // A listed key that did not read may be gone: the next pass lists `.npn/ocr/` again.
            if (stored.isNotEmpty()) versions.forget(docId)
            fail(docId, failure ?: "no readable ocr version for $docId", textIndexed = false, seen = entry)
            return
        }
        val sidecar = selected.sidecar
        // A failed run still leaves the best stored version searchable meanwhile.
        indexAndMarkSelected(docId, selected.version, sidecar)
        // Only a sidecar published for this run counts as new text: by this call, or by an earlier
        // pass of the run cut short before it got here. Being queued is not enough: a rebuilt index
        // re-queues documents, and those runs land here and adopt what is already stored.
        val produced = own == OwnOcr.Published || publishedFor(entry, stored)
        val hookError = if (produced) notifyTextProduced(docId, sidecar) else null
        // The queue row is decided here, once, after the hooks, so an observer watching the queue
        // empty out (the Android maintenance job) cannot see this document finish before the
        // follow-up work they schedule has been recorded.
        if (failure != null) {
            // This device's run is still owed: the attempt, the error and a requested re-run stay.
            fail(docId, failure, textIndexed = true, seen = entry)
        } else {
            // Done even when a hook failed — the text is indexed and searchable, which is what
            // this queue tracks — but the failure is kept on the row rather than dropped, since
            // nothing retries a hook and there is no other trace of it. A request made while this
            // pass ran keeps the row owed instead. Either way this run is over, and the pages it
            // read go: a re-run asked for meanwhile must read them again.
            ocrQueueDao.clearCachedPages(docId)
            val now = clock.nowMillis()
            ocrQueueDao.settle(docId, seen = entry) { row ->
                OcrQueueEntity(
                    docId = docId,
                    state = QueueState.DONE.column,
                    attempts = row?.attempts ?: 0,
                    updatedAt = now,
                    lastError = hookError,
                )
            }
        }
    }

    /**
     * Leave [docId]'s run owed, with what was asked of it and no attempt spent, but not due before the
     * file has had [WriteSettle.QUIET_MILLIS] to settle. The follow-up rescan then refreshes the
     * document's row, or finds the file replaced and the document gone, which the run cleans up after.
     * Kept rather than dropped: a rescan queues only documents without an OCR state, and would leave a
     * pending one owed nothing. A request made since the pass read the row ([seen]) leaves the row as
     * that request wrote it, owed.
     */
    private suspend fun awaitRescan(
        docId: String,
        seen: OcrQueueEntity?,
    ) {
        val now = clock.nowMillis()
        ocrQueueDao.settle(docId, seen) { row ->
            (row ?: OcrQueueEntity(docId, QueueState.PENDING.column, updatedAt = now)).copy(
                state = QueueState.PENDING.column,
                updatedAt = now,
                nextAttemptAt = now + WriteSettle.QUIET_MILLIS,
                lastError = STALE_FILE,
            )
        }
    }

    /**
     * Run the hooks for text new to the library, and return the first failure for the caller to
     * record on the queue row.
     *
     * Each is contained separately. They are follow-up work (auto-match rules, queueing AI
     * enrichment) on a result that is already indexed and searchable, so one throwing must neither
     * abort the queue drain, nor leave this document's entry unresolved, nor cost the document the
     * other hook — which nothing would retry.
     */
    private suspend fun notifyTextProduced(
        docId: String,
        sidecar: OcrSidecar,
    ): String? {
        val hooks =
            listOf(
                "onTextProduced" to suspend { runRules(docId, sidecar.fullText()) },
                "onOcrProduced" to suspend { onOcrProduced(docId) },
            )
        var firstError: String? = null
        for ((name, hook) in hooks) {
            runCatchingCancellable { hook() }.onFailure { if (firstError == null) firstError = "$name: ${it.reason()}" }
        }
        return firstError
    }

    /** [onTextProduced] for [docId], owed again while writes are blocked, then for what was owed before. */
    private suspend fun runRules(
        docId: String,
        text: String,
    ) {
        try {
            onTextProduced(docId, text)
        } catch (blocked: MetadataWriteBlocked) {
            rulesOwed.update { it + docId }
            throw blocked
        }
        rulesOwed.update { it - docId }
        retryOwedRules()
    }

    /**
     * Run [onTextProduced] again for every document [rulesOwed] it, from its selected text, until
     * writes are refused again. Any other failure goes on the document's queue row, as its pass's would.
     */
    private suspend fun retryOwedRules() {
        for (docId in rulesOwed.value) {
            rulesOwed.update { it - docId }
            if (!retryRules(docId)) {
                rulesOwed.update { it + docId }
                return
            }
        }
    }

    /** [retryOwedRules] for one document; false when writes were refused again. */
    private suspend fun retryRules(docId: String): Boolean {
        val doc = documentDao.getByDocId(docId)
        val sidecar = doc?.let { versions.selectReadable(docId, versions.of(docId), it.ocrEngineOverride)?.sidecar }
        val failure = sidecar?.let { runCatchingCancellable { onTextProduced(docId, it.fullText()) }.exceptionOrNull() }
        if (failure == null || failure is MetadataWriteBlocked) return failure == null
        ocrQueueDao.getByDocId(docId)?.let { row ->
            ocrQueueDao.upsert(row.copy(lastError = "onTextProduced: ${failure.reason()}"))
        }
        return true
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
        stored: List<OcrVersions.Version>,
        aiRequested: Boolean,
    ): OwnOcr {
        var step = "read failed"
        val result =
            runCatchingCancellable {
                // The sidecar is keyed by docId and is write-once and synced, so text from any other
                // bytes would stick to this document everywhere.
                val bytes = fs.readDocument(doc.relPath, doc.docId) ?: return OwnOcr.Stale
                step = "ocr failed"
                extractOrOcr(bytes, doc, aiRequested)
            }.getOrElse { return OwnOcr.Failed(describe(step, it)) }
        val sameEngine = stored.filter { it.engine == result.engine }
        // A re-run that read the same text again adds nothing; keep the library free of copies.
        if (sameEngine.any { versions.read(doc.docId, it.writerKey)?.pages == result.pages }) return OwnOcr.Adopted
        val writerKey = versions.newWriterKey(stored, deviceId, result.engine, clock.nowMillis())
        val sidecar =
            OcrSidecar(
                doc = doc.docId,
                dev = deviceId,
                engine = result.engine,
                lang = result.lang,
                createdAt = clock.nowMillis(),
                pages = result.pages,
            )
        val encoded = OcrSidecarCodec.encode(sidecar)
        // Already published for this device+engine: the published file is kept.
        return runCatchingCancellable { versions.publish(doc.docId, writerKey, encoded) }.fold(
            onSuccess = { written -> if (written) OwnOcr.Published else OwnOcr.Adopted },
            onFailure = { failure -> OwnOcr.Failed(describe("sidecar write/read failed for $writerKey", failure)) },
        )
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
        // Throws PdfOpenException on an invalid pdf.
        return pdfEngine.open(bytes).use { handle ->
            // Chosen once, off the PDF's real page count (the doc row's may still be null on a
            // first run), so the whole document is transcribed by one engine and its sidecar's
            // engine label is the truth about every OCR'd page in it.
            val engine = engines.forDocument(doc.ocrEngineOverride, handle.pageCount, aiRequested)
            val canOcr = engine.available()
            // Resolved once, like the engine, so every page reads the same languages and the
            // sidecar records the ones actually used.
            val requested = language()
            val lang = if (canOcr) engine.languagesFor(requested) else requested
            // Pages an earlier, failed attempt already paid for (see OcrPageCacheEntity).
            val read =
                if (canOcr) {
                    ocrQueueDao.cachedPages(doc.docId, engine.name, lang).associate { it.page to it.text }
                } else {
                    emptyMap()
                }
            val pages = ArrayList<String>(handle.pageCount)
            var usedOcr = false
            for (i in 0 until handle.pageCount) {
                val embedded = handle.extractText(i)
                if (embedded.trim().length >= MIN_EMBEDDED_CHARS_PER_PAGE || !canOcr) {
                    // Usable text layer (or no engine): keep the embedded text as-is.
                    pages.add(embedded)
                } else {
                    val text =
                        read[i] ?: engine.recognizePng(handle.renderPagePng(i, RENDER_DIM), lang).also {
                            // Kept until the run publishes: a later page failing must not cost this one again.
                            ocrQueueDao.cachePage(OcrPageCacheEntity(doc.docId, engine.name, lang, i, it))
                        }
                    pages.add(text)
                    usedOcr = true
                }
            }
            // The engine label records which OCR engine produced the OCR'd pages; embedded
            // pages are deterministic and identical across devices, so a partially embedded
            // result is still labeled (and ranked) by its OCR engine.
            val name = if (usedOcr) engine.name else OcrEngineId.EMBEDDED.column
            PageResult(pages, name, lang)
        }
    }

    /**
     * Reconcile every document against the sidecars on disk: adopt text for un-OCR'd docs,
     * re-select when a better (or the overridden) version appeared, and queue an OCR run when
     * this device's engine outranks everything stored. Called after rescans, on `.npn/ocr/`
     * watch events, and after journal ingestion (override changes sync as journal events).
     */
    suspend fun reconcileSidecars() {
        for ((hash, writerKeys) in versions.writerKeysByHash()) {
            if (hash == EMPTY_SHA256) continue
            documentDao.getByDocId(hash)?.let { doc -> reselect(doc, versions.of(hash, writerKeys)) }
        }
    }

    /**
     * Re-select the indexed version for one document (e.g. after its override changed).
     * No-op when nothing is stored or the indexed version is already the selected one.
     */
    suspend fun reselect(docId: String) {
        val doc = documentDao.getByDocId(docId) ?: return
        reselect(doc, versions.of(docId))
    }

    /** Index [doc]'s selected version of [stored] and queue an upgrade run where one is due. */
    private suspend fun reselect(
        doc: DocumentEntity,
        stored: List<OcrVersions.Version>,
    ) {
        if (stored.isEmpty()) return
        applySelection(doc, stored)
        maybeQueueUpgrade(doc, stored)
    }

    /**
     * Publish [from]'s text for [to], bytes with the same pages (a bake of [from]): of each engine
     * [from] has a version of, the one [OcrVersions.select] indexes with that engine pinned, under
     * this device's writer key for that engine, best engine first. Write-once: a version [to] already
     * has under that key is kept. Indexing it is [reselect]'s, once [to] has a row.
     */
    suspend fun copyText(
        from: String,
        to: String,
    ) {
        val stored = versions.of(from)
        // A label this build does not know has no writer key of its own to go under.
        val engines = stored.map { it.engine }.distinct().filter { OcrEngineId.fromColumn(it) != null }
        for (engine in engines.sortedByDescending { OcrEngineId.rank(it) }) {
            val sidecar = versions.select(stored, engine)?.let { versions.read(from, it.writerKey) } ?: continue
            val writerKey = OcrSidecarName.writerKey(deviceId, engine)
            val copy = OcrSidecarCodec.encode(sidecar.copy(doc = to, dev = deviceId))
            versions.publish(to, writerKey, copy)
        }
    }

    /** All stored OCR versions of [docId], decoded, best-ranked first. For UI listing. */
    suspend fun listVersions(docId: String): List<OcrVersions.StoredVersion> = versions.list(docId)

    // --- selection ---

    /** Index [stored]'s selected sidecar when the doc is un-OCR'd or indexed with another engine. */
    private suspend fun applySelection(
        doc: DocumentEntity,
        stored: List<OcrVersions.Version>,
    ) {
        val desired = versions.select(stored, doc.ocrEngineOverride) ?: return
        // Asked before reading anything: on almost every reconcile, the indexed version is the one.
        if (isIndexed(doc, desired)) return
        // One that does not read is passed over for the next best, which may be the indexed one.
        val selected =
            versions.selectReadable(doc.docId, stored, doc.ocrEngineOverride) ?: return versions.forget(doc.docId)
        if (isIndexed(doc, selected.version)) return
        // Adoption or re-selection of a stored sidecar: no new text, so no hook.
        indexAndMarkSelected(doc.docId, selected.version, selected.sidecar)
        // Adopted text does not settle a run still owed here, nor one whose sidecar a pass cut short
        // already published; processDoc does, once it is due, and hands that text on as produced.
        val row = ocrQueueDao.getByDocId(doc.docId)
        if (row?.isOwedHere() != true && !publishedFor(row, stored)) {
            ocrQueueDao.deleteIfUnchanged(doc.docId, row)
        }
    }

    /** Whether [doc] is processed with [version]'s text indexed. */
    private fun isIndexed(
        doc: DocumentEntity,
        version: OcrVersions.Version,
    ): Boolean = OcrState.fromColumn(doc.ocrState) !in UNPROCESSED_STATES && doc.ocrSource == version.writerKey

    /**
     * Whether this row holds what only this device knows about its own run: a request made here
     * ([OcrQueueEntity.rerun], [OcrQueueEntity.aiRequested]) or failed attempts, with their backoff,
     * their cap and the pages they already read. Dropping such a row on adoption would forget the
     * request, or queue the same run afresh and pay for those pages again.
     */
    private fun OcrQueueEntity.isOwedHere(): Boolean =
        queueState != QueueState.DONE && (rerun || aiRequested || attempts > 0)

    /**
     * Whether [stored] holds a sidecar this device published for the run [row] still owes: one
     * written at or after the row's stamp, which only a pass of that run writes, by a pass cut short
     * before it settled the row (the app closed, the drain was stopped). Its text is new here all the
     * same. A sidecar older than the stamp answered an earlier run, as the stored ones do for the runs
     * a rebuilt index queues, so it does not count.
     */
    private suspend fun publishedFor(
        row: OcrQueueEntity?,
        stored: List<OcrVersions.Version>,
    ): Boolean {
        if (row == null || row.queueState == QueueState.DONE) return false
        return stored.any { version ->
            version.deviceId == deviceId &&
                versions.read(row.docId, version.writerKey)?.let { it.createdAt >= row.updatedAt } == true
        }
    }

    /**
     * True when running this device's engine for [doc] would produce a better version than anything
     * stored: the engine outranks every stored version, this device has not already published a
     * sidecar for that engine, and no stored `embedded` sidecar has a full text layer.
     *
     * Selects with the doc row's page count rather than the PDF's, so this and the run it gates
     * agree about the AI page cap. They have to: without that, a document over the cap would be
     * queued because AI outranks its stored version, produce a local sidecar instead, and be queued
     * again on the next reconcile, forever.
     *
     * A [rerun] the user asked for lowers the bar to "at least as good as the indexed version":
     * re-running an engine that ranks below it would produce text nobody selects. So would a re-run
     * of an engine other than the one the document is pinned to, while a version of that one is
     * stored: the pin keeps selecting it. [aiRequested] is the user's "OCR with AI" on this device;
     * see [OcrEngineSelector.forDocument].
     */
    private suspend fun canImprove(
        doc: DocumentEntity,
        stored: List<OcrVersions.Version>,
        rerun: Boolean = false,
        aiRequested: Boolean = false,
    ): Boolean {
        val engine = engines.forDocument(doc.ocrEngineOverride, doc.pageCount, aiRequested)
        if (!engine.available()) return false
        if (stored.isEmpty()) return true
        val ownRank = OcrEngineId.rank(engine.name)
        if (rerun) {
            val pin = doc.ocrEngineOverride
            val pinKeepsAnother = pin != null && pin != engine.name && stored.any { it.engine == pin }
            return !pinKeepsAnother && ownRank >= OcrEngineId.rank(versions.select(stored, pin)?.engine)
        }
        val outranksStored = ownRank > stored.maxOf { OcrEngineId.rank(it.engine) }
        val triedHere = stored.any { it.deviceId == deviceId && it.engine == engine.name }
        if (!outranksStored || triedHere) return false
        // An `embedded` sidecar whose every page has a usable text layer means this device's run
        // would OCR no page and publish the same text again: without this, a library opened on a
        // new device re-extracts every born-digital document. One with a thin page does not count,
        // this device's own included, since it may come from a run that had no OCR engine (desktop
        // before Tesseract is installed). With no engine now, the check above has already said no.
        return stored.none { versions.hasFullTextLayer(doc.docId, it) }
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
        stored: List<OcrVersions.Version>,
    ) {
        if (!doc.isLive) return
        if (!canImprove(doc, stored)) return
        ocrQueueDao.enqueueUpgrade(doc.docId, clock.nowMillis(), retry.maxAttempts)
    }

    // --- indexing / state helpers ---

    /**
     * Index the selected [sidecar]'s text for [docId] and record the resulting OCR state on the
     * doc row: [OcrState.DONE] when this device produced [desired], else [OcrState.ADOPTED]. Shared by the
     * queue-drain path ([processDoc]) and the reconcile/re-select path ([applySelection]); each
     * caller handles its own queue-entry bookkeeping, and [processDoc] the hooks, afterwards.
     */
    private suspend fun indexAndMarkSelected(
        docId: String,
        desired: OcrVersions.Version,
        sidecar: OcrSidecar,
    ) {
        // Only the content column is this pipeline's; the fold writes the others. The projection's
        // values are read for a document the index has no FTS row for yet, which gets a whole one.
        val fileName = documentDao.getByDocId(docId)?.fileName
        index.ftsDao().upsertContent(FtsRows.indexed(index, docId, fileName, content = sidecar.fullText()))
        val state = if (desired.deviceId == deviceId) OcrState.DONE else OcrState.ADOPTED
        setOcrState(docId, state, pageCount = sidecar.pages.size, engine = sidecar.engine, source = desired.writerKey)
    }

    private suspend fun setOcrState(
        docId: String,
        state: OcrState,
        pageCount: Int? = null,
        engine: String? = null,
        source: String? = null,
    ) {
        // Partial UPDATE (not a row upsert) so this background write cannot clobber metadata
        // columns a concurrent journal fold just projected onto the same row: [pageCount] only
        // fills a NULL page count and a null [engine] keeps the stored engine (see
        // DocumentDao.updateOcrState).
        documentDao.updateOcrState(docId, state.column, pageCount, engine, source)
    }

    /**
     * Charge a failed pass to [docId]: spend an attempt, leave the row pending for a retry after
     * its backoff or failed once the cap is reached, and record [error] as the diagnostic. A request
     * made since the pass read the row ([seen]) is another run, not this one: the row is left as that
     * request wrote it, owed and uncharged.
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
        seen: OcrQueueEntity?,
    ) {
        val now = clock.nowMillis()
        val settled =
            ocrQueueDao.settle(docId, seen) { row ->
                val attempts = (row?.attempts ?: 0) + 1
                OcrQueueEntity(
                    docId = docId,
                    state = (if (retry.givesUp(attempts)) QueueState.FAILED else QueueState.PENDING).column,
                    attempts = attempts,
                    updatedAt = now,
                    nextAttemptAt = retry.nextAttemptAt(now, attempts),
                    lastError = error,
                    // A retry of a requested run is still that request.
                    rerun = row?.rerun ?: false,
                    aiRequested = row?.aiRequested ?: false,
                )
            }
        if (!textIndexed) {
            setOcrState(docId, if (settled?.queueState == QueueState.FAILED) OcrState.FAILED else OcrState.PENDING)
        }
    }
}

/** [what] plus [cause]'s reason: the diagnostic a failure is recorded with. */
private fun describe(
    what: String,
    cause: Throwable,
): String = "$what: ${cause.reason()}"
