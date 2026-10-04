package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiOcrEngine
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.toEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.debounced
import dev.npn.core.index.DbLocalJournalState
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.EmptyImportException
import dev.npn.core.index.FsSegmentStore
import dev.npn.core.index.FtsWriteGate
import dev.npn.core.index.ImportResult
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.MaintenanceService
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.RelinkService
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SplitService
import dev.npn.core.index.WriteSettle
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrConfig
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrEngineSelector
import dev.npn.core.ocr.OcrEngines
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.rules.AutoMatchRuleJson
import dev.npn.core.rules.RuleApplier
import dev.npn.core.util.NpnLog
import dev.npn.core.work.QueueDrainer
import dev.npn.core.xmp.BakeService
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Everything wired for one opened library folder. Constructed by [openSession] (a suspend factory
 * that runs the device-identity + fork check and initial fold), then [start]ed with a scope.
 */
class LibrarySessionImpl private constructor(
    val deviceId: String,
    private val deps: SessionDeps,
    private val coordinator: JournalCoordinator,
    private val indexer: Indexer,
    private val importService: ImportService,
    private val maintenanceService: MaintenanceService,
    private val relinkService: RelinkService,
    private val splitService: SplitService,
    private val ocrPipeline: OcrPipeline,
    /** Same instance the pipeline runs on, so the AI-OCR facet answers from the real policy. */
    private val ocrEngines: OcrEngineSelector,
    private val ocrWorker: QueueDrainer,
    private val enrichWorker: QueueDrainer,
    private val editQueue: AiEditQueue,
    private val editWorker: QueueDrainer,
    override val documents: DocumentRepository,
    override val tags: TagRepository,
    override val savedViews: SavedViewRepository,
    override val rules: AutoMatchRuleRepository,
    override val fieldRegistry: FieldRegistry,
    private val writer: MetadataWriterImpl,
    override val content: DocContentFacade,
    private val aiFacade: AiFacadeImpl,
) : LibrarySession,
    IngestFacade,
    OcrFacade,
    ReviewConfigFacade,
    MaintenanceFacade,
    WorkFacade,
    AiEditQueueFacade {
    private val fs get() = deps.fs
    private val db get() = deps.db

    // The session implements every facet itself; the facet properties only narrow the type a
    // consumer sees. [MetadataWriterImpl] likewise backs all three write facets, and
    // [AiFacadeImpl] backs both AI facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade get() = this
    override val reviewConfig: ReviewConfigFacade get() = this
    override val maintenance: MaintenanceFacade get() = this
    override val work: WorkFacade get() = this
    override val aiEditQueue: AiEditQueueFacade get() = this
    override val meta: MetadataWriter get() = writer
    override val tagAdmin: TagAdmin get() = writer
    override val docLifecycle: DocLifecycle get() = writer
    override val aiChat: AiChat get() = aiFacade
    override val aiSuggest: AiSuggest get() = aiFacade

    private val _lastScanReport = MutableStateFlow<ScanReport?>(null)
    override val lastScanReport: Flow<ScanReport?> = _lastScanReport.asStateFlow()

    override val scanProgress: Flow<ScanProgressUi?> =
        indexer.scanState.map { p ->
            p?.let { ScanProgressUi(it.phase.name, it.hashed, it.total) }
        }

    // `running` is asked of the pipeline rather than inferred from the count. The two are not the
    // same question — a queue can be full while nothing is draining it (nothing has kicked yet, or
    // the endpoint is down) — and inferring it made the indicator claim work was under way whenever
    // anything was owed, including for rows no drain would ever pick up.
    override val ocrStatus: Flow<OcrStatus> =
        combine(db.ocrQueueDao().countActive(), ocrPipeline.inFlight) { owed, inFlight ->
            OcrStatus(pending = owed, running = inFlight != null)
        }

    private val workQueueReader =
        WorkQueueReader(
            db.documentDao(),
            db.ocrQueueDao(),
            db.aiEnrichQueueDao(),
            db.aiEditJobDao(),
            ocrPipeline.inFlight,
        )

    override val queue: Flow<List<QueuedWork>> = workQueueReader.queue

    override val editRuns: Flow<List<QueuedEditRun>> = workQueueReader.editRuns

    override val reviewMode: Flow<ReviewMode> =
        coordinator.settingFlow(ReviewMode.WIRE_KEY).map(ReviewMode::fromWire)

    override suspend fun setReviewMode(mode: ReviewMode) {
        coordinator.commitAndApply(listOf(PendingOp.settingSet(ReviewMode.WIRE_KEY, mode.wire)))
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) {
        val dao = db.documentDao()
        val live =
            dao
                .getByDocIds(dao.allPathsAndMeta().map { it.docId })
                .filter { !it.trashed && !it.deleted && !it.missing }
        val targets =
            if (!onlyTagged) {
                live
            } else {
                val tagged = dao.tagsForDocs(live.map { it.docId }).map { it.docId }.toHashSet()
                live.filter { it.docId in tagged }
            }
        val ops =
            targets.map {
                PendingOp.propSet(it.docId, SystemProperty.REVIEWED, "true")
            }
        if (ops.isNotEmpty()) coordinator.commitAndApply(ops)
    }

    private var jobs: MutableList<Job> = ArrayList()
    private var started = false

    // Caller-driven work that writes outside the coordinator (rescans, imports) runs on this job
    // rather than the caller's coroutine, so close() cancels and awaits it before the environment
    // closes the database. Callers start these from scopes that outlive the session: the activity
    // rescans on the app scope whenever it resumes, including on the way back from the folder
    // picker that is about to swap the library.
    private val operations = SupervisorJob(deps.scope.coroutineContext[Job])
    private val operationScope = CoroutineScope(deps.scope.coroutineContext + operations)

    private suspend fun <T> owned(block: suspend () -> T): T {
        val work = operationScope.async { block() }
        return try {
            work.await()
        } catch (e: CancellationException) {
            work.cancel()
            // The caller was cancelled: let its own cancellation propagate. Otherwise the session
            // closed under the call, which is a failure the caller should see, not a cancellation.
            currentCoroutineContext().ensureActive()
            throw IllegalStateException("Library session is closed", e)
        }
    }

    override suspend fun rescan(): ScanReport =
        owned {
            val report = coordinator.rescan()
            _lastScanReport.value = report
            report.retryAfterMillis?.let { scheduleSettleRescan(it) }
            // Adopt/re-select OCR sidecars picked up by the rescan, then queue/kick + purge trash.
            ocrPipeline.reconcileSidecars()
            queuePendingOcr()
            report
        }

    // The one pending follow-up rescan for files a scan deferred as still being written. A file
    // can finish without another watch event, and SAF libraries have no watcher at all.
    private val settleRescanLock = Mutex()
    private var settleRescan: Job? = null

    private suspend fun scheduleSettleRescan(afterMillis: Long) {
        settleRescanLock.withLock {
            settleRescan?.cancel()
            settleRescan =
                operationScope.launch {
                    delay(afterMillis + SETTLE_RESCAN_MARGIN_MS)
                    // Cleared first: the rescan below may schedule the next one.
                    settleRescanLock.withLock { settleRescan = null }
                    runCatching { doWatchTriggeredRescan() }
                }
        }
    }

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome =
        owned { importing { map(importService.importPdf(source, suggestedName)).also { afterImport(it) } } }

    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome =
        owned { importing { map(importService.importBytes(bytes, suggestedName)).also { afterImport(it) } } }

    private inline fun importing(block: () -> ImportOutcome): ImportOutcome =
        try {
            block()
        } catch (e: EmptyImportException) {
            ImportOutcome.Failed(e.message ?: "empty file")
        }

    override suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome =
        try {
            val pdf = deps.pdfEngine.imagesToPdf(images)
            importPdfBytes(pdf, suggestedName)
        } catch (t: Throwable) {
            ImportOutcome.Failed(t.message ?: "image conversion failed")
        }

    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    ) = owned { maintenanceService.resolveDuplicate(docId, extraRelPath, keepCanonical) }

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) = owned {
        // commitAndRescan already refreshes the coordinator's folded state under the lock.
        _lastScanReport.value = relinkService.applyRelink(oldDocId, newDocId)
    }

    /**
     * Internal auto-purge, run after watch-triggered rescans; not part of any public facet.
     *
     * A document's row decides when it has one: it also covers a trash in progress, whose PDF is
     * moved before the event is committed. The journal covers documents with no row, which is every
     * trashed one on a device whose index was just built; their PDFs keep an old mtime too.
     */
    internal suspend fun purgeTrash(olderThanDays: Int) {
        val cutoff = deps.clock.nowMillis() - olderThanDays.toLong() * MILLIS_PER_DAY
        val rows = db.documentDao().trashTimes().associate { it.docId to it.trashedAt }
        trashOps().purgeOlderThan(cutoff, coordinator.journalTrashTimes() + rows)
    }

    override suspend fun reOcr(docId: String) {
        if (db.documentDao().getByDocId(docId) == null) return
        enqueuePendingOcr(docId, rerun = true)
        ocrWorker.kick()
    }

    /**
     * The page count is asked of [content] rather than read off `DocumentEntity`: the row's
     * `pageCount` is a backfilled cache that stays NULL until something has opened the PDF, and a
     * NULL would quietly downgrade a 200-page document's warning to [AiOcrOffer.Ready]. A count
     * that cannot be read at all (unreadable PDF) is not this warning's problem — offer it plainly
     * and let the OCR run report the real fault.
     */
    override suspend fun aiOcrOffer(docId: String): AiOcrOffer {
        if (!ocrEngines.aiAvailable()) return AiOcrOffer.Unavailable
        val pages = content.pageCount(docId) ?: return AiOcrOffer.Ready
        return if (ocrEngines.isLargeForAi(pages)) AiOcrOffer.Large(pages) else AiOcrOffer.Ready
    }

    override suspend fun ocrWithAi(docId: String) {
        db.documentDao().getByDocId(docId) ?: return
        check(ocrEngines.aiAvailable()) {
            "AI OCR needs a configured AI endpoint (Settings > AI assistant)"
        }
        // Deliberately no page limit here: length is something aiOcrOffer warns about, not
        // something this refuses. The caller asked for this document by name.
        //
        // The pin picks the ai sidecar for search once it exists, on every replica; the request to
        // pay for one is this device's queue flag (see OcrFacade.ocrWithAi). Queue after pinning so
        // the run that drains sees the override already folded onto the row.
        setOcrEngine(docId, OcrEngines.AI)
        enqueuePendingOcr(docId, aiRequested = true)
        ocrWorker.kick()
    }

    override suspend fun ocrVersions(docId: String): List<OcrVersion> {
        val doc = db.documentDao().getByDocId(docId)
        return ocrPipeline.listVersions(docId).map {
            OcrVersion(
                engine = it.engine,
                deviceId = it.deviceId,
                lang = it.lang,
                createdAt = it.createdAt,
                // Rows indexed before the writer key was recorded only know the engine.
                active = doc?.ocrSource?.let { source -> it.writerKey == source } ?: (it.engine == doc?.ocrEngine),
            )
        }
    }

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        writer.setOcrEngine(docId, engine)
        // The commit above already folded the override into the doc row; re-select now so this
        // device's index reflects the choice immediately.
        ocrPipeline.reselect(docId)
    }

    // Both drains go through the session's own drainers rather than running on the caller's
    // coroutine, so closing the session cancels them (see QueueDrainer.drainAndAwait).
    override suspend fun drainOcr() {
        ocrWorker.drainAndAwait()
    }

    override suspend fun drainEnrichment() {
        enrichWorker.drainAndAwait()
    }

    override suspend fun drainEditRuns() {
        editWorker.drainAndAwait()
    }

    // Queueing is a local write; the kick is what turns it into work, so the two belong together
    // at the one boundary a caller reaches. The queue itself never sees the drainer.
    override suspend fun enqueueEditRun(
        docIds: List<String>,
        request: AiEditRequest,
    ): String = editQueue.enqueueEditRun(docIds, request).also { editWorker.kick() }

    override suspend fun openEditRun(jobId: String): EditRunReview? = editQueue.openEditRun(jobId)

    override suspend fun discardEditRun(jobId: String) = editQueue.discardEditRun(jobId)

    override suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome = owned { split(docId, segments) }

    private suspend fun split(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome {
        val result = splitService.split(docId, segments)
        val parts =
            when (result) {
                is SplitService.Result.Failed -> return SplitOutcome.Failed(result.message)
                is SplitService.Result.Done -> result.parts
            }
        val partIds =
            parts.map {
                when (it) {
                    is ImportResult.Imported -> it.docId
                    is ImportResult.Duplicate -> it.existingDocId
                }
            }
        copyMetadataToParts(docId, partIds)
        // Trash last: if anything above fails the original stays untouched next to the parts.
        writer.trashDoc(docId)
        ocrWorker.kick()
        return SplitOutcome.Done(partIds)
    }

    /**
     * Carry tags and single-value properties of the split source onto its parts.
     *
     * One [MetadataWriter.applyEdits] for every part rather than a setter per tag and per property:
     * the setters each re-run the storage path rules, so a source with several tags would rename
     * every part once per tag before it settled. Parts are brand-new files, so those intermediate
     * names are churn no replica needs to see.
     *
     * All-or-nothing across the parts is what this path wants: the parts were created together
     * from one source, so a half-copied set is worse than none, and the caller's retry is a rerun
     * of the same split.
     */
    private suspend fun copyMetadataToParts(
        docId: String,
        partIds: List<String>,
    ) {
        val doc = db.documentDao().getByDocId(docId) ?: return
        val tags =
            db
                .documentDao()
                .tagsForDocs(listOf(docId))
                .map { it.tag }
                .distinct()
        // Every field carries onto the parts except the ones that describe the whole document
        // (title, notes): a part is not "the" invoice, and its notes would be misleading.
        val fields =
            db
                .documentFieldDao()
                .fieldsForDocs(listOf(docId))
                .filter { it.fieldKey !in FieldKeys.NOT_INHERITED_ON_SPLIT }
                .associate<_, String, String?> { it.fieldKey to it.fieldValue }
        writer.applyEdits(partIds, addTags = tags, fields = fields)
    }

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        // OCR found a document's file no longer matches its hash: rescan once it has settled.
        ocrPipeline.onStaleFile = { scheduleSettleRescan(WriteSettle.QUIET_MILLIS) }

        // OCR worker drain loop: kick on each queue change.
        jobs.add(
            scope.launch {
                db.ocrQueueDao().countActive().collect { active ->
                    if (active > 0) ocrWorker.kick()
                }
            },
        )

        // Automatic AI enrichment is NOT driven by an observer of its queue table. Such an observer
        // cannot distinguish new work from a failed document being rescheduled — a reschedule
        // rewrites a row without changing the row count — so it would either loop the drain back
        // into an endpoint that just failed, or, once filtered to changes only, silently drop a
        // kick whenever a delete and an insert coalesce into one refresh at the same count.
        // Instead every producer of new work kicks explicitly (see AiAutoEnricher.enqueue), and
        // time-based retries come from the maintenance tick below.
        //
        // This one kick resumes whatever survived the last run: a killed process, an endpoint that
        // was down. It costs a single query when the queue is empty.
        enrichWorker.kick()

        // Queued edit runs are kicked the same way and for the same reason: a run's rows change
        // shape (a document gets its suggestion) without the row count moving, so an observer of
        // the table could not tell new work from progress. Producers kick (see enqueueEditRun),
        // this resumes a run the last process left half-suggested, and the maintenance tick below
        // serves backoffs.
        editWorker.kick()

        // Metadata paths are routed separately so journal sync never scans or hashes PDFs.
        jobs.add(
            scope.launch {
                fs
                    .watch()
                    .filterNot { event ->
                        val rel = (event as? FsEvent.Changed)?.relPath
                        rel != null && NpnPaths.isOwnDeviceWrite(rel, deviceId)
                    }.filter { event -> routeWatchEvent(event) == WatchRoute.PassToRescan }
                    .debounced()
                    .collect { event ->
                        val rel = (event as? FsEvent.Changed)?.relPath
                        if (rel != null && rel.startsWith("${NpnPaths.OCR_DIR}/")) {
                            runCatching { ocrPipeline.reconcileSidecars() }
                        } else {
                            runCatching { coordinator.reconcileMetadata() }
                            runCatching { doWatchTriggeredRescan() }
                        }
                    }
            },
        )

        // Initial rescan.
        jobs.add(scope.launch { runCatching { doWatchTriggeredRescan() } })

        // Watch services and sync providers may omit events. This reconciliation reads only the
        // metadata directory and skips unchanged source fingerprints; it never walks PDFs.
        jobs.add(
            scope.launch {
                while (isActive) {
                    delay(METADATA_RECONCILIATION_INTERVAL_MS)
                    runCatching { coordinator.reconcileMetadata() }
                    // Compaction runs under the same single-writer mutex; it only fires when the
                    // stable object count exceeds the threshold, so most reconciles are a cheap no-op.
                    runCatching { coordinator.compact() }
                    // OCR retries, enrichment rows and edit runs become due by the passage of time,
                    // which no Room flow can observe; this tick is what actually retries a
                    // backed-off one.
                    ocrWorker.kick()
                    enrichWorker.kick()
                    editWorker.kick()
                }
            },
        )
    }

    /**
     * How a watch event should be handled once its journal side effect (if any) has run.
     * [PassToRescan] events fall through the flow to the debounced rescan/OCR collector; the
     * rest are fully handled by their side effect and must not trigger a PDF rescan.
     */
    private enum class WatchRoute { HandledByMetadata, PassToRescan }

    /**
     * Pure classification of a watch event into a [WatchRoute]. No side effects: it only decides
     * how the event should be routed, so the routing itself ([routeWatchEvent]) stays explicit and
     * this decision is independently testable. A non-journal (or non-Changed) event always passes
     * through to the rescan collector.
     */
    private fun classifyWatchEvent(event: FsEvent): WatchRoute {
        val rel = (event as? FsEvent.Changed)?.relPath ?: return WatchRoute.PassToRescan
        return when (NpnPaths.journalPath(rel)) {
            is NpnPaths.JournalPath.ObjectV2,
            is NpnPaths.JournalPath.SnapshotV2,
            is NpnPaths.JournalPath.Legacy,
            is NpnPaths.JournalPath.UnsupportedObjectStore,
            NpnPaths.JournalPath.Staging,
            -> {
                WatchRoute.HandledByMetadata
            }

            NpnPaths.JournalPath.Unknown -> {
                if (rel == NpnPaths.JOURNAL_DIR || rel.startsWith("${NpnPaths.JOURNAL_DIR}/")) {
                    WatchRoute.HandledByMetadata
                } else {
                    WatchRoute.PassToRescan
                }
            }
        }
    }

    /**
     * Run the metadata side effect for a journal watch event and return its [WatchRoute]. A single
     * content-addressed object gets the cheap single-object ingest; snapshots, legacy segments and
     * unrecognized journal writes go through the full metadata reconcile (a snapshot may supersede
     * already-applied objects and carries multi-actor events, so the single-object path is unsafe);
     * staging files are ignored. Everything else passes through to the PDF rescan.
     */
    private suspend fun routeWatchEvent(event: FsEvent): WatchRoute {
        val route = classifyWatchEvent(event)
        if (route == WatchRoute.PassToRescan) return route
        val rel = (event as? FsEvent.Changed)?.relPath
        when (rel?.let(NpnPaths::journalPath)) {
            is NpnPaths.JournalPath.ObjectV2 -> {
                runCatching { coordinator.ingestObject(rel) }
                // A remote OCR-engine override may have just folded in; re-select promptly
                // instead of waiting for the next rescan.
                runCatching { ocrPipeline.reconcileSidecars() }
            }

            // A staging file is a write in progress, and a null path means the event was not a
            // change under `.npn/` at all. Neither has anything to ingest.
            NpnPaths.JournalPath.Staging, null -> {}

            else -> {
                runCatching { coordinator.reconcileMetadata() }
                runCatching { ocrPipeline.reconcileSidecars() }
            }
        }
        return route
    }

    private suspend fun doWatchTriggeredRescan() {
        rescan()
        ocrWorker.kick()
        enrichWorker.kick()
        editWorker.kick()
        // Respect the configured retention at call time.
        val days =
            runCatching { deps.trashRetentionDays() }
                .getOrDefault(TrashSettings.DEFAULT_TRASH_RETENTION_DAYS)
        runCatching { purgeTrash(days) }
    }

    override suspend fun close() {
        jobs.forEach { it.cancelAndJoin() }
        jobs.clear()
        ocrWorker.close()
        enrichWorker.close()
        editWorker.close()
        operations.cancelAndJoin()
        coordinator.close()
        started = false
        // DB ownership stays with the environment; it may close the DB after this shutdown barrier.
    }

    private fun trashOps() = TrashOps(fs)

    private fun map(result: ImportResult): ImportOutcome =
        when (result) {
            is ImportResult.Imported -> ImportOutcome.Imported(result.docId, result.relPath)
            is ImportResult.Duplicate -> ImportOutcome.Duplicate(result.existingDocId, result.existingPath)
        }

    private suspend fun afterImport(outcome: ImportOutcome) {
        if (outcome is ImportOutcome.Imported) {
            ocrWorker.kick()
        }
    }

    /** Queue OCR for any live doc that has not been processed yet. */
    private suspend fun queuePendingOcr() {
        val dao = db.documentDao()
        // One bulk read for the whole projection instead of a query per document; this runs after
        // every rescan, so the per-row read was an N+1 across the library.
        val docs = dao.getByDocIds(dao.allPathsAndMeta().map { it.docId })
        for (doc in docs) {
            if (doc.trashed || doc.deleted || doc.missing) continue
            if (doc.ocrState == OcrPipeline.OCR_NONE) {
                enqueuePendingOcr(doc.docId)
            }
        }
    }

    /**
     * Mark [docId] pending in the OCR queue and flip its doc-row OCR state to pending. The row
     * write is a partial UPDATE so this state flip cannot clobber metadata columns a concurrent
     * journal fold just projected onto the same row.
     *
     * A request already on the row ([OcrQueueEntity.rerun], [OcrQueueEntity.aiRequested]) is kept:
     * asking for one thing does not withdraw what was asked before it ran.
     */
    private suspend fun enqueuePendingOcr(
        docId: String,
        rerun: Boolean = false,
        aiRequested: Boolean = false,
    ) {
        val existing = db.ocrQueueDao().getByDocId(docId)
        db.ocrQueueDao().upsert(
            OcrQueueEntity(
                docId = docId,
                state = OcrPipeline.STATE_PENDING,
                updatedAt = deps.clock.nowMillis(),
                rerun = rerun || existing?.rerun == true,
                aiRequested = aiRequested || existing?.aiRequested == true,
            ),
        )
        db.documentDao().updateOcrState(docId, OcrPipeline.OCR_PENDING, null, null)
    }

    companion object {
        private const val METADATA_RECONCILIATION_INTERVAL_MS = 15L * 60L * 1000L

        /** Slack past a deferred file's settle time, so the follow-up does not land just short. */
        private const val SETTLE_RESCAN_MARGIN_MS = 500L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * Boot a session over [deps]: get-or-create the deviceId, run the single-writer fork check
         * (rebuilding the writer under a fresh id if a fork is detected), wire the journal engine,
         * repositories, pipelines, then do the initial fold so [documents] is populated before the
         * caller's initial rescan.
         */
        @Suppress("LongMethod")
        suspend fun openSession(deps: SessionDeps): LibrarySessionImpl {
            val db = deps.db
            val fs = deps.fs

            val segmentStore = FsSegmentStore(fs)
            // Shared FTS content gate: serializes metadata-fold content carry-forward against
            // OCR content writes so they cannot clobber the same FTS row.
            val ftsWriteGate = FtsWriteGate()
            val indexer = Indexer(fs, db, deps.clock, ftsWriteGate)

            val identity = DeviceIdentity(db.keyValueDao())
            val initialDeviceId = identity.getOrCreate()
            val localState = DbLocalJournalState(db.keyValueDao())

            // Build a provisional writer to run the fork check.
            val provisional =
                SegmentWriter(
                    store = segmentStore,
                    deviceId = initialDeviceId,
                    hlcClock = HlcClock(initialDeviceId, deps.clock),
                    localState = localState,
                )
            val effectiveDeviceId = identity.ensureSingleWriter(provisional, localState)
            val writer =
                if (effectiveDeviceId == initialDeviceId) {
                    provisional
                } else {
                    SegmentWriter(
                        store = segmentStore,
                        deviceId = effectiveDeviceId,
                        hlcClock = HlcClock(effectiveDeviceId, deps.clock),
                        localState = localState,
                    )
                }

            val coordinator = createCoordinator(deps, writer, indexer)
            coordinator.refreshState()
            coordinator.seedFieldRegistryIfNeeded(FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) })

            val pathRuleApplicator =
                StoragePathRuleApplicator(fs, db, deps.storagePathRule) { docId, path ->
                    coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, path)))
                }
            val importService =
                ImportService(
                    fs = fs,
                    documentDao = db.documentDao(),
                    ocrQueueDao = db.ocrQueueDao(),
                    clock = deps.clock,
                    ruleProvider = deps.storagePathRule,
                )
            val maintenance = MaintenanceService(fs, db.documentDao(), db.duplicateDao(), importService)
            val relinkService = RelinkService(coordinator)
            val splitService = SplitService(fs, deps.pdfEngine, importService, db.documentDao())

            // Built before the OCR pipeline because it is also the pipeline's AI engine: one
            // facade, so the vision calls share the cached endpoint client with chat and edits.
            val ai = AiFacadeImpl(db, configProvider = deps.aiConfig)
            val ocrEngines =
                OcrEngineSelector(
                    local = deps.ocrEngine,
                    ai = AiOcrEngine(ai),
                    aiAuto = deps.unattendedAi::autoOcr,
                )
            val ocrPipeline =
                OcrPipeline(
                    fs = fs,
                    sidecars = OcrSidecarFiles(fs),
                    pdfEngine = deps.pdfEngine,
                    engines = ocrEngines,
                    documentDao = db.documentDao(),
                    ftsDao = db.ftsDao(),
                    ocrQueueDao = db.ocrQueueDao(),
                    tagDao = db.tagDao(),
                    fieldDao = db.documentFieldDao(),
                    deviceId = effectiveDeviceId,
                    config = OcrConfig(lang = deps.ocrLanguage()),
                    ftsWriteGate = ftsWriteGate,
                    clock = deps.clock,
                )
            val ocrWorker = QueueDrainer(deps.scope) { ocrPipeline.processNext() }

            // Single shared gate so thumbnail + page rendering share one bounded concurrency
            // budget and a first-load burst (e.g. 200 PDFs) cannot flood the dispatchers.
            val renderGate = RenderGate()
            val thumbnailService =
                ThumbnailService(
                    engine = deps.pdfEngine,
                    fs = fs,
                    store = deps.thumbnailStore,
                    thumbnailDao = db.thumbnailDao(),
                    documentDao = db.documentDao(),
                    renderGate = renderGate,
                )
            val bakeService = BakeService(fs, deps.xmpIO)

            val documents = DocumentRepositoryImpl(db)
            val fieldRegistry = FieldRegistryImpl(db, coordinator)
            val tags = TagRepositoryImpl(db.tagDao())
            val savedViews = SavedViewRepositoryImpl(db.savedViewDao())
            val content =
                DocContentFacadeImpl(
                    db,
                    fs,
                    deps.pdfEngine,
                    thumbnailService,
                    bakeService,
                    coordinator,
                    relinkService,
                    renderGate,
                )

            val ruleApplier =
                RuleApplier(
                    ruleDao = db.autoMatchRuleDao(),
                    ftsDao = db.ftsDao(),
                    documentDao = db.documentDao(),
                    coordinator = coordinator,
                    onMetadataApplied = pathRuleApplicator::applyFor,
                )
            val rulesReadable = mirrorAutoMatchRules(deps)
            val rules =
                AutoMatchRuleRepositoryImpl(
                    dao = db.autoMatchRuleDao(),
                    applier = ruleApplier,
                    persist = if (rulesReadable) deps.persistAutoMatchRulesJson else { _ -> },
                )
            val meta =
                MetadataWriterImpl(
                    coordinator,
                    db,
                    fs,
                    TrashOps(fs),
                    pathRuleApplicator,
                    applyAutoMatch = { ruleApplier.applyToDoc(it, organize = false) },
                )
            val autoEnricher =
                AiAutoEnricher(
                    documentDao = db.documentDao(),
                    queueDao = db.aiEnrichQueueDao(),
                    ai = ai,
                    meta = meta,
                    fieldRegistry = fieldRegistry,
                    policy = deps.unattendedAi,
                    clock = deps.clock,
                )
            val enrichWorker = QueueDrainer(deps.scope) { autoEnricher.processNext() }
            val editQueue =
                AiEditQueue(
                    dao = db.aiEditJobDao(),
                    documents = documents,
                    content = content,
                    meta = meta,
                    ai = ai,
                    fieldRegistry = fieldRegistry,
                    readConfig = deps.aiConfig,
                    clock = deps.clock,
                )
            val editWorker = QueueDrainer(deps.scope) { editQueue.processNext() }
            // Deterministic local rules run on any newly indexed text, including a re-index.
            ocrPipeline.onTextIndexed = { docId, text -> ruleApplier.applyToDoc(docId, text) }
            // The paid pass runs only on text this device just produced. It fires after the rules
            // above, so rule-applied tags and fields are part of the context the model sees. Only
            // the *intent* is recorded here: this runs on the OCR queue-drain thread, where a
            // network call would stall OCR of every remaining document.
            ocrPipeline.onOcrProduced = { docId ->
                if (autoEnricher.enqueue(docId)) enrichWorker.kick()
            }

            return LibrarySessionImpl(
                deviceId = effectiveDeviceId,
                deps = deps,
                coordinator = coordinator,
                indexer = indexer,
                importService = importService,
                maintenanceService = maintenance,
                relinkService = relinkService,
                splitService = splitService,
                ocrPipeline = ocrPipeline,
                ocrEngines = ocrEngines,
                ocrWorker = ocrWorker,
                enrichWorker = enrichWorker,
                editQueue = editQueue,
                editWorker = editWorker,
                documents = documents,
                tags = tags,
                savedViews = savedViews,
                rules = rules,
                fieldRegistry = fieldRegistry,
                writer = meta,
                content = content,
                aiFacade = ai,
            )
        }

        /**
         * Bring the rules table in step with the settings JSON it mirrors, and report whether that
         * JSON could be read at all.
         *
         * The JSON is the source of truth; the table is its disposable mirror, reconciled only when
         * it actually differs — an unconditional clear + per-rule upsert on every open is a write
         * burst on the awaited open path that invalidates the rules Room flow for no change.
         *
         * JSON that does not decode (written by a newer build, or damaged) is not "no rules". The
         * table keeps the last rules that did decode, and the caller must not persist the table
         * over the JSON either: the next save would write back only what this build understood.
         */
        private suspend fun mirrorAutoMatchRules(deps: SessionDeps): Boolean {
            val json = deps.autoMatchRulesJson()
            val decoded =
                try {
                    AutoMatchRuleJson.decodeList(json)
                } catch (e: IllegalArgumentException) {
                    println("Auto-match rules did not decode; keeping the last ones that did: ${e.message}")
                    return false
                }
            val storedRules =
                decoded
                    .map { it.toEntity() }
                    .sortedWith(compareBy({ it.position }, { it.id }))
            val dao = deps.db.autoMatchRuleDao()
            if (dao.getAll() != storedRules) dao.replaceAll(storedRules)
            return true
        }

        /**
         * v2 (content-addressed objects) is the only write format. Every production filesystem
         * reports [dev.npn.core.fs.VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE], so this
         * always returns a writer there. It returns null only for a filesystem that does not
         * support verified publication (unknown future provider), in which case the coordinator
         * falls back to the legacy v1 [SegmentWriter]. v1 remains fully readable regardless.
         */
        private suspend fun createObjectWriter(deps: SessionDeps): ContentAddressedJournalWriter? {
            val state = deps.db.metadataProjectionDao().state()
            return ContentAddressedJournalWriter(
                fs = deps.fs,
                installationId = deps.installationId,
                allocator =
                    HlcWatermarkAllocator(
                        clock = deps.clock,
                        initialPhysicalMillis = state?.allocatorPhysical ?: 0L,
                        initialCounter = state?.allocatorCounter ?: 0,
                    ),
            ).takeIf { it.supported }
        }

        private suspend fun createCoordinator(
            deps: SessionDeps,
            writer: SegmentWriter,
            indexer: Indexer,
        ): JournalCoordinator =
            JournalCoordinator(
                writer = writer,
                indexer = indexer,
                accumulator = MetadataAccumulator(deps.fs, deps.db, deps.installationId),
                objectWriter = createObjectWriter(deps),
                compactor = SnapshotCompactor(deps.fs, deps.installationId).takeIf { it.supported },
            )
    }
}

/**
 * Bundle of everything a [LibrarySessionImpl] needs, assembled by the platform [AppEnvironment].
 * Settings values are read as suspend accessors so the session picks up live changes at call time
 * (e.g. trash retention on each purge, ocr language when a pipeline is built).
 */
class SessionDeps(
    val fs: LibraryFileSystem,
    val db: NpnDatabase,
    val pdfEngine: PdfEngine,
    val ocrEngine: OcrEngine,
    val xmpIO: PdfXmpIO,
    val thumbnailStore: ThumbnailStore,
    val scope: CoroutineScope,
    val ocrLanguage: suspend () -> String,
    val trashRetentionDays: suspend () -> Int,
    /** Where the session reports what fails in the background, with no caller to tell. */
    val log: NpnLog,
    val storagePathRule: suspend () -> StoragePathRule? = { null },
    val autoMatchRulesJson: suspend () -> String = { "[]" },
    val persistAutoMatchRulesJson: suspend (String) -> Unit = {},
    val aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
    /**
     * Who may spend on AI unattended. Defaults to the user's own [aiConfig] toggles — the device
     * policy; a host with a different answer (a server running on a library's behalf) supplies
     * its own.
     */
    val unattendedAi: UnattendedAiPolicy = UnattendedAiPolicy.fromConfig(aiConfig),
    val clock: EpochClock = SystemEpochClock,
    val installationId: String = "local-installation",
) {
    /**
     * Close the underlying index database. Exposed here so the composeApp DI layer (which does not
     * depend on the androidx.room3 runtime directly) can free the cache on a library swap without
     * touching [NpnDatabase]'s room supertype.
     */
    fun closeDb() = db.close()
}
