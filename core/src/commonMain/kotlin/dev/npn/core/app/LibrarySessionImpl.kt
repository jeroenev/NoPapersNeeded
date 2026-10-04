package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiSuggest
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FsEvent
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.fs.debounced
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.EmptyImportException
import dev.npn.core.index.ImportResult
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.RelinkService
import dev.npn.core.index.ScanReport
import dev.npn.core.index.WriteSettle
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.rules.RuleApplier
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything wired for one opened library folder. Constructed by [openSession] (a suspend factory
 * that settles the device identity and runs the initial fold), then [start]ed.
 *
 * The session itself is the library's lifecycle and its ingest: imports, rescans, the folder watch
 * and the periodic reconcile. Every other facet is its own object, wired by [openSession] from the
 * builders in `SessionWiring.kt`.
 *
 * Everything the session runs — background loops, queue drains, caller-driven operations — belongs
 * to one [SessionLifetime] under [SessionDeps.scope], so [close] stops all of it at once, for good.
 */
class LibrarySessionImpl private constructor(
    val deviceId: String,
    private val deps: SessionDeps,
    private val lifetime: SessionLifetime,
    private val coordinator: JournalCoordinator,
    indexer: Indexer,
    library: Library,
    private val scanReports: MutableStateFlow<ScanReport?>,
    private val ocrFacade: OcrFacadeImpl,
    override val maintenance: MaintenanceFacade,
    override val aiEditQueue: AiEditQueueFacade,
    private val queues: SessionWork,
    private val aiFacade: AiFacadeImpl,
) : LibrarySession,
    IngestFacade {
    private val fs get() = deps.fs
    private val db get() = deps.db
    private val log get() = deps.log
    private val importService = library.imports
    private val writer = library.writer

    override val documents: DocumentRepository = library.documents
    override val tags: TagRepository = library.tags
    override val savedViews: SavedViewRepository = library.savedViews
    override val rules: AutoMatchRuleRepositoryImpl = library.rules
    override val storagePath: StoragePathFacade = library.storagePath
    override val fieldRegistry: FieldRegistry = library.fieldRegistry
    override val content: DocContentFacade = library.content
    override val reviewConfig: ReviewConfigFacade = library.reviewConfig

    // The facet properties only narrow the type a consumer sees: the session is the ingest facet,
    // [MetadataWriterImpl] backs all three write facets, and [AiFacadeImpl] both AI facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade get() = ocrFacade
    override val work: WorkFacade get() = queues
    override val meta: MetadataWriter get() = writer
    override val tagAdmin: TagAdmin get() = writer
    override val docLifecycle: DocLifecycle get() = writer
    override val aiChat: AiChat get() = aiFacade
    override val aiSuggest: AiSuggest get() = aiFacade

    override val lastScanReport: Flow<ScanReport?> = scanReports.asStateFlow()

    override val scanProgress: Flow<ScanProgressUi?> =
        indexer.scanState.map { p ->
            p?.let { ScanProgressUi(it.phase.name, it.hashed, it.total) }
        }

    private var started = false

    // Every facet write runs here rather than on the caller's coroutine; see SessionLifetime.owned.
    private suspend fun <T> owned(block: suspend () -> T): T = lifetime.owned(block)

    override suspend fun rescan(): ScanReport =
        owned {
            val report = coordinator.rescan()
            scanReports.value = report
            report.retryAfterMillis?.let { scheduleSettleRescan(it) }
            ocrFacade.reconcileAfterRescan()
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
                lifetime.scope.launch {
                    delay(afterMillis + SETTLE_RESCAN_MARGIN_MS)
                    // Cleared first: the rescan below may schedule the next one.
                    settleRescanLock.withLock { settleRescan = null }
                    log.runLogged("Follow-up rescan") { doWatchTriggeredRescan() }
                }
        }
    }

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome = owned { importing(suggestedName) { importService.importPdf(source, suggestedName) } }

    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome = owned { importing(suggestedName) { importService.importBytes(bytes, suggestedName) } }

    override suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome =
        owned {
            val pdf =
                runCatchingCancellable { deps.pdfEngine.imagesToPdf(images) }.getOrElse {
                    log.warn("Making a PDF of ${images.size} images failed", it)
                    return@owned ImportOutcome.Failed(ImportFailure.UNREADABLE_IMAGES)
                }
            importing(suggestedName) { importService.importBytes(pdf, suggestedName) }
        }

    /** Run one import, turning what can go wrong with the file into an [ImportFailure]. */
    private inline fun importing(
        name: String,
        import: () -> ImportResult,
    ): ImportOutcome =
        runCatchingCancellable(import).fold(
            onSuccess = { map(it).also(::afterImport) },
            onFailure = { failure ->
                if (failure is EmptyImportException) {
                    ImportOutcome.Failed(ImportFailure.EMPTY_FILE)
                } else {
                    log.warn("Importing $name failed", failure)
                    ImportOutcome.Failed(ImportFailure.COPY_FAILED)
                }
            },
        )

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

    override fun start() {
        lifetime.checkOpen()
        if (started) return
        started = true
        val scope = lifetime.scope

        ocrFacade.watchQueue(scope)

        // Automatic AI enrichment and queued edit runs are NOT driven by an observer of their
        // queue tables. Such an observer cannot distinguish new work from a failed row being
        // rescheduled, or a run's document getting its suggestion — the rows change without the
        // row count moving — so it would either loop the drain back into an endpoint that just
        // failed, or, once filtered to changes only, silently drop a kick whenever a delete and an
        // insert coalesce into one refresh at the same count. Instead each queue kicks its own
        // drain when it records new work (see drainedQueue), and time-based retries come from the
        // maintenance tick below.
        //
        // These kicks resume whatever survived the last run: a killed process, an endpoint that
        // was down, a run left half-suggested. Each costs a single query when its queue is empty.
        queues.kickAll()

        // Metadata paths are routed separately so journal sync never scans or hashes PDFs.
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
                        log.runLogged("OCR sidecar reconcile") { ocrFacade.reconcileSidecars() }
                    } else {
                        log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                        log.runLogged("Watch-triggered rescan") { doWatchTriggeredRescan() }
                    }
                }
        }

        // Initial rescan.
        scope.launch { log.runLogged("Initial rescan") { doWatchTriggeredRescan() } }

        // Another device's rule list arrives as a journal setting; the rules table only caches it.
        scope.launch {
            coordinator.settingFlow(LibrarySettingKeys.AUTO_MATCH_RULES).collect {
                log.runLogged("Caching the auto-match rules") { rules.mirror() }
            }
        }

        // Watch services and sync providers may omit events. This reconciliation reads only the
        // metadata directory and skips unchanged source fingerprints; it never walks PDFs.
        scope.launch {
            while (isActive) {
                delay(METADATA_RECONCILIATION_INTERVAL_MS)
                log.runLogged("Periodic metadata reconcile") { coordinator.reconcileMetadata() }
                // Compaction runs under the same single-writer mutex; it only fires when the stable
                // object count exceeds the threshold, so most reconciles are a cheap no-op.
                log.runLogged("Journal compaction") { coordinator.compact() }
                // OCR retries, enrichment rows and edit runs become due by the passage of time,
                // which no Room flow can observe; this tick is what actually retries a backed-off
                // one.
                queues.kickAll()
            }
        }
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
                log.runLogged("Journal object ingest") { coordinator.ingestObject(rel) }
                // A remote OCR-engine override may have just folded in; re-select promptly
                // instead of waiting for the next rescan.
                log.runLogged("OCR sidecar reconcile") { ocrFacade.reconcileSidecars() }
            }

            // A staging file is a write in progress, and a null path means the event was not a
            // change under `.npn/` at all. Neither has anything to ingest.
            NpnPaths.JournalPath.Staging, null -> {}

            else -> {
                log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                log.runLogged("OCR sidecar reconcile") { ocrFacade.reconcileSidecars() }
            }
        }
        return route
    }

    private suspend fun doWatchTriggeredRescan() {
        rescan()
        queues.kickAll()
        // Respect the configured retention at call time.
        val days =
            log.runLogged("Reading the trash retention") { deps.trashRetentionDays() }
                ?: TrashSettings.DEFAULT_TRASH_RETENTION_DAYS
        log.runLogged("Trash auto-purge") { purgeTrash(days) }
    }

    override suspend fun close() {
        // Background loops, drains and every owned operation, all in one: see SessionLifetime.
        lifetime.close()
        // Waits out a coordinator write a caller started outside the lifetime, then refuses more.
        coordinator.close()
        aiFacade.close()
        // DB ownership stays with the environment; it may close the DB after this shutdown barrier.
    }

    private fun trashOps() = TrashOps(fs)

    private fun map(result: ImportResult): ImportOutcome =
        when (result) {
            is ImportResult.Imported -> ImportOutcome.Imported(result.docId, result.relPath)
            is ImportResult.Duplicate -> ImportOutcome.Duplicate(result.existingDocId, result.existingPath)
        }

    private fun afterImport(outcome: ImportOutcome) {
        if (outcome is ImportOutcome.Imported) {
            ocrFacade.kick()
        }
    }

    companion object {
        private const val METADATA_RECONCILIATION_INTERVAL_MS = 15L * 60L * 1000L

        /** Slack past a deferred file's settle time, so the follow-up does not land just short. */
        private const val SETTLE_RESCAN_MARGIN_MS = 500L
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L

        /**
         * Boot a session over [deps]: move device state an older index still holds into the device
         * database, settle the device id ([DeviceIdentity]: a fresh one for a restored or cloned
         * installation), wire the journal engine, repositories, pipelines, then do the initial fold
         * so [documents] is populated before the caller's initial rescan.
         *
         * Refuses, with [VerifiedPublicationUnavailableException], a library on a filesystem that
         * cannot publish without replacement: there is no other journal format to write. Recording
         * this installation in the library publishes through the same primitive, so the first open
         * finds out.
         */
        suspend fun openSession(deps: SessionDeps): LibrarySessionImpl {
            val lifetime = SessionLifetime(deps.scope)
            // Before anything reads the device id: an index from before device.db holds it.
            moveDeviceState(deps)
            val indexer = Indexer(deps.fs, deps.db, deps.clock, deps.log)
            val coordinator = createCoordinator(deps, indexer)
            val deviceId = DeviceIdentity(deps.deviceDb.keyValueDao(), deps.fs, deps.installationId, deps.log).resolve()
            foldJournal(deps, coordinator)

            val relink = RelinkService(coordinator)
            val library = buildLibrary(deps, lifetime, coordinator, relink)
            // Built before the OCR pipeline because it is also the pipeline's AI engine: one
            // facade, so the vision calls share the cached endpoint client with chat and edits.
            val ai =
                AiFacadeImpl(
                    deps.db,
                    chats = deps.deviceDb.keyValueDao(),
                    ownWrite = { lifetime.owned(it) },
                    configProvider = deps.aiConfig,
                )
            val enrichment = buildEnrichment(deps, lifetime, library, ai)
            val edits = buildEditQueue(deps, lifetime, library, ai)
            // The pipeline's stale-file hook asks the session for a follow-up rescan; the session is
            // built last, and the hook only runs on a drain.
            lateinit var session: LibrarySessionImpl
            val ocr =
                buildOcr(
                    deps,
                    lifetime,
                    deviceId,
                    library,
                    ai,
                    // Only the *intent* is recorded here: this runs on the OCR queue-drain thread,
                    // where a network call would stall OCR of every remaining document.
                    onOcrProduced = { docId -> enrichment.queue.enqueue(docId) },
                    // The file no longer matches its hash: rescan once it has settled.
                    onStaleFile = { session.scheduleSettleRescan(WriteSettle.QUIET_MILLIS) },
                )
            val scanReports = MutableStateFlow<ScanReport?>(null)
            session =
                LibrarySessionImpl(
                    deviceId = deviceId,
                    deps = deps,
                    lifetime = lifetime,
                    coordinator = coordinator,
                    indexer = indexer,
                    library = library,
                    scanReports = scanReports,
                    ocrFacade = ocr,
                    maintenance = buildMaintenance(deps, lifetime, library, relink, ocr, scanReports),
                    aiEditQueue = edits.queue,
                    queues = buildWork(deps, lifetime, ocr, enrichment.worker, edits.worker),
                    aiFacade = ai,
                )
            return session
        }
    }
}

/**
 * Bundle of everything a [LibrarySessionImpl] needs, assembled by the platform [AppEnvironment].
 * Settings values are read as suspend accessors so the session picks up live changes at call time
 * (e.g. trash retention on each purge, ocr language when a pipeline is built).
 */
class SessionDeps(
    val fs: LibraryFileSystem,
    /** The disposable index: dropped and rebuilt from the library whenever it must be. */
    val db: NpnDatabase,
    /** This device's own state for the library, which nothing could rebuild ([DeviceDatabase]). */
    val deviceDb: DeviceDatabase,
    val pdfEngine: PdfEngine,
    val ocrEngine: OcrEngine,
    val xmpIO: PdfXmpIO,
    val thumbnailStore: ThumbnailStore,
    val scope: CoroutineScope,
    val ocrLanguage: suspend () -> String,
    val trashRetentionDays: suspend () -> Int,
    /** Where the session reports what fails in the background, with no caller to tell. */
    val log: NpnLog,
    /** What this device kept as settings for library behaviour, adopted by a library that has none. */
    val legacyLibrarySettings: suspend () -> LegacyLibrarySettings = { LegacyLibrarySettings() },
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
     * Close the index and device databases. Exposed here so the composeApp DI layer (which does not
     * depend on the androidx.room3 runtime directly) can free them on a library swap without
     * touching their room supertype.
     */
    fun closeDb() {
        db.close()
        deviceDb.close()
    }
}

/**
 * The library's metadata layer over its journal: the repositories and writers the session hands
 * out as facets, the auto-match rules OCR runs, and the import path.
 */
internal class Library(
    val documents: DocumentRepository,
    val tags: TagRepository,
    val savedViews: SavedViewRepository,
    val fieldRegistry: FieldRegistry,
    val storagePath: StoragePathFacade,
    val rules: AutoMatchRuleRepositoryImpl,
    val ruleApplier: RuleApplier,
    val writer: MetadataWriterImpl,
    val reviewConfig: ReviewConfigFacade,
    val content: DocContentFacade,
    val imports: ImportService,
)
