package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiFacade
import dev.npn.core.ai.AiFacadeImpl
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
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import dev.npn.core.ocr.OcrConfig
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.ocr.OcrWorker
import dev.npn.core.pathrules.StoragePathRule
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.rules.RuleApplier
import dev.npn.core.xmp.BakeService
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    private val ocrWorker: OcrWorker,
    override val documents: DocumentRepository,
    override val tags: TagRepository,
    override val savedViews: SavedViewRepository,
    override val rules: AutoMatchRuleRepository,
    private val writer: MetadataWriterImpl,
    override val content: DocContentFacade,
    override val ai: AiFacade,
) : LibrarySession,
    IngestFacade,
    OcrFacade,
    ReviewConfigFacade,
    MaintenanceFacade {
    private val fs get() = deps.fs
    private val db get() = deps.db

    // The session implements every facet itself; the facet properties only narrow the type a
    // consumer sees. [MetadataWriterImpl] likewise backs all three write facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade get() = this
    override val reviewConfig: ReviewConfigFacade get() = this
    override val maintenance: MaintenanceFacade get() = this
    override val meta: MetadataWriter get() = writer
    override val tagAdmin: TagAdmin get() = writer
    override val docLifecycle: DocLifecycle get() = writer

    private val _lastScanReport = MutableStateFlow<ScanReport?>(null)
    override val lastScanReport: Flow<ScanReport?> = _lastScanReport.asStateFlow()

    override val scanProgress: Flow<ScanProgressUi?> =
        indexer.scanState.map { p ->
            p?.let { ScanProgressUi(it.phase.name, it.hashed, it.total) }
        }

    override val ocrStatus: Flow<OcrStatus> =
        db.ocrQueueDao().countActive().map { active -> OcrStatus(pending = active, running = active > 0) }

    override val reviewMode: Flow<ReviewMode> =
        coordinator.settingFlow(ReviewMode.WIRE_KEY).map(ReviewMode::fromWire)

    override suspend fun setReviewMode(mode: ReviewMode) {
        coordinator.commitAndApply(listOf(PendingOp.settingSet(ReviewMode.WIRE_KEY, mode.wire)))
    }

    override val standardFields: Flow<Set<StandardField>> =
        coordinator.settingFlow(StandardField.WIRE_KEY).map(StandardField::fromWire)

    override suspend fun setStandardFields(fields: Set<StandardField>) {
        coordinator.commitAndApply(
            listOf(PendingOp.settingSet(StandardField.WIRE_KEY, StandardField.toWire(fields))),
        )
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) {
        val dao = db.documentDao()
        val live =
            dao
                .allPathsAndMeta()
                .mapNotNull { dao.getByDocId(it.docId) }
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
                PendingOp.propSet(it.docId, dev.npn.core.model.DocProperty.REVIEWED, "true")
            }
        if (ops.isNotEmpty()) coordinator.commitAndApply(ops)
    }

    private var jobs: MutableList<Job> = ArrayList()
    private var started = false

    override suspend fun rescan(): ScanReport {
        val report = coordinator.rescan()
        _lastScanReport.value = report
        // Adopt/re-select OCR sidecars picked up by the rescan, then queue/kick + purge trash.
        ocrPipeline.reconcileSidecars()
        queuePendingOcr()
        return report
    }

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome = map(importService.importPdf(source, suggestedName)).also { afterImport(it) }

    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome = map(importService.importBytes(bytes, suggestedName)).also { afterImport(it) }

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
    ) {
        maintenanceService.resolveDuplicate(docId, extraRelPath, keepCanonical)
    }

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) {
        // commitAndRescan already refreshes the coordinator's folded state under the lock.
        _lastScanReport.value = relinkService.applyRelink(oldDocId, newDocId)
    }

    /** Internal auto-purge, run after watch-triggered rescans; not part of any public facet. */
    private suspend fun purgeTrash(olderThanDays: Int) {
        val cutoff = deps.clock.nowMillis() - olderThanDays.toLong() * MILLIS_PER_DAY
        trashOps().purgeOlderThan(cutoff)
    }

    override suspend fun reOcr(docId: String) {
        if (db.documentDao().getByDocId(docId) == null) return
        enqueuePendingOcr(docId)
        ocrWorker.kick()
    }

    override suspend fun ocrVersions(docId: String): List<OcrVersion> {
        val active = db.documentDao().getByDocId(docId)?.ocrEngine
        return ocrPipeline.listVersions(docId).map {
            OcrVersion(
                engine = it.engine,
                deviceId = it.deviceId,
                lang = it.lang,
                createdAt = it.createdAt,
                active = it.engine == active,
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

    override suspend fun splitPdf(
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

    /** Carry tags and single-value properties of the split source onto its parts. */
    private suspend fun copyMetadataToParts(
        docId: String,
        partIds: List<String>,
    ) {
        val doc = db.documentDao().getByDocId(docId) ?: return
        for (tag in db
            .documentDao()
            .tagsForDocs(listOf(docId))
            .map { it.tag }
            .distinct()) {
            writer.addTag(partIds, tag)
        }
        doc.correspondent?.let { writer.setCorrespondent(partIds, it) }
        doc.docType?.let { writer.setDocType(partIds, it) }
        doc.docDate?.let { date -> partIds.forEach { writer.setDocDate(it, date) } }
    }

    override fun start(scope: CoroutineScope) {
        if (started) return
        started = true

        // OCR worker drain loop: kick on each queue change.
        jobs.add(
            scope.launch {
                db.ocrQueueDao().countActive().collect { active ->
                    if (active > 0) ocrWorker.kick()
                }
            },
        )

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

            NpnPaths.JournalPath.Staging, null -> {
                Unit
            }

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
        started = false
        // DB ownership stays with the environment; it may close the DB after this shutdown barrier.
    }

    // --- helpers ---

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
        for (pm in db.documentDao().allPathsAndMeta()) {
            val doc = db.documentDao().getByDocId(pm.docId) ?: continue
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
     */
    private suspend fun enqueuePendingOcr(docId: String) {
        db.ocrQueueDao().upsert(
            OcrQueueEntity(
                docId = docId,
                state = OcrPipeline.STATE_PENDING,
                updatedAt = deps.clock.nowMillis(),
            ),
        )
        db.documentDao().updateOcrState(docId, OcrPipeline.OCR_PENDING, null, null)
    }

    companion object {
        private const val METADATA_RECONCILIATION_INTERVAL_MS = 15L * 60L * 1000L
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

            val pathRuleApplicator = StoragePathRuleApplicator(fs, db, ftsWriteGate, deps.storagePathRule)
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

            val ocrPipeline =
                OcrPipeline(
                    fs = fs,
                    sidecars = OcrSidecarFiles(fs),
                    pdfEngine = deps.pdfEngine,
                    ocrEngine = deps.ocrEngine,
                    documentDao = db.documentDao(),
                    ftsDao = db.ftsDao(),
                    ocrQueueDao = db.ocrQueueDao(),
                    tagDao = db.tagDao(),
                    deviceId = effectiveDeviceId,
                    config = OcrConfig(lang = deps.ocrLanguage()),
                    ftsWriteGate = ftsWriteGate,
                )
            val ocrWorker = OcrWorker(ocrPipeline, deps.scope)

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
            val tags = TagRepositoryImpl(db.tagDao())
            val savedViews = SavedViewRepositoryImpl(db.savedViewDao())
            val content =
                DocContentFacadeImpl(db, fs, deps.pdfEngine, thumbnailService, bakeService, coordinator, renderGate)

            val ruleApplier =
                RuleApplier(
                    ruleDao = db.autoMatchRuleDao(),
                    ftsDao = db.ftsDao(),
                    documentDao = db.documentDao(),
                    coordinator = coordinator,
                    onMetadataApplied = pathRuleApplicator::applyFor,
                )
            db.autoMatchRuleDao().clearAll()
            runCatching {
                Json.decodeFromString<List<dev.npn.core.rules.AutoMatchRule>>(deps.autoMatchRulesJson())
            }.getOrDefault(emptyList()).forEach { db.autoMatchRuleDao().upsert(it.toEntity()) }
            val rules =
                AutoMatchRuleRepositoryImpl(
                    dao = db.autoMatchRuleDao(),
                    applier = ruleApplier,
                    persist = deps.persistAutoMatchRulesJson,
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
            ocrPipeline.onTextIndexed = { docId, text ->
                ruleApplier.applyToDoc(docId, text)
            }
            val ai = AiFacadeImpl(db, deps.aiConfig)

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
                ocrWorker = ocrWorker,
                documents = documents,
                tags = tags,
                savedViews = savedViews,
                rules = rules,
                writer = meta,
                content = content,
                ai = ai,
            )
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
    val storagePathRule: suspend () -> StoragePathRule? = { null },
    val autoMatchRulesJson: suspend () -> String = { "[]" },
    val persistAutoMatchRulesJson: suspend (String) -> Unit = {},
    val aiConfig: suspend () -> AiConfig = { AiConfig.DISABLED },
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
