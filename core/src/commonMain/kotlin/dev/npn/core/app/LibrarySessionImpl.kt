package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.aiedit.AiEditQueueFacade
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.EmptyImportException
import dev.npn.core.index.ImportResult
import dev.npn.core.index.ImportService
import dev.npn.core.index.IndexProgress
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SupersededImportException
import dev.npn.core.index.WriteSettle
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.rules.RuleApplier
import dev.npn.core.settings.SessionSettings
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
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
    private val log get() = deps.log
    private val importService = library.imports
    private val writer = library.ownedWriter

    override val documents: DocumentRepository = library.documents
    override val tags: TagRepository = library.tags
    override val savedViews: SavedViewRepository = library.savedViews
    override val rules: AutoMatchRuleRepository = library.rules

    /** The rules' cache in the index, mirrored from the journal when another device's list arrives. */
    internal val ruleSource: AutoMatchRuleSource = library.ruleSource
    override val storagePath: StoragePathFacade = library.storagePath
    override val fieldRegistry: FieldRegistry = library.fieldRegistry
    override val content: DocContentFacade = library.content
    override val reviewConfig: ReviewConfigFacade = library.reviewConfig
    private val retention = JournalTrashRetention(coordinator, lifetime)
    override val trashRetention: TrashRetentionFacade get() = retention

    /** The trash auto-purge, run after each watch-triggered rescan. */
    internal val trashPurge = TrashPurge(coordinator, deps.db, TrashOps(deps.fs), retention, deps.clock, deps.log)

    // The facet properties only narrow the type a consumer sees: the session is the ingest facet,
    // [OwnedWriter] backs all three write facets, and [AiFacadeImpl] both AI facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade get() = ocrFacade
    override val work: WorkFacade get() = queues
    override val meta: MetadataWriter get() = writer
    override val tagAdmin: TagAdmin get() = writer
    override val docLifecycle: DocLifecycle get() = writer
    override val aiChat: AiChat get() = aiFacade
    override val aiSuggest: AiSuggest get() = aiFacade

    override val lastScanReport: Flow<ScanReport?> = scanReports.asStateFlow()

    override val scanProgress: Flow<IndexProgress?> = indexer.scanState

    private var started = false

    // Every facet write runs here rather than on the caller's coroutine; see SessionLifetime.owned.
    private suspend fun <T> owned(block: suspend () -> T): T = lifetime.owned(block)

    override suspend fun rescan(): ScanReport =
        owned {
            val report = coordinator.rescan()
            scanReports.value = report
            report.retryAfterMillis?.let { scheduleSettleRescan(it) }
            ocrFacade.reconcileAfterRescan()
            // The index knows the library's documents and has their stored text: from here on a
            // drain may take a document without a row for one that is gone.
            queues.rescanned()
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
            onSuccess = ::map,
            onFailure = { failure ->
                when (failure) {
                    is EmptyImportException -> {
                        ImportOutcome.Failed(ImportFailure.EMPTY_FILE)
                    }

                    is SupersededImportException -> {
                        ImportOutcome.Failed(ImportFailure.SUPERSEDED)
                    }

                    else -> {
                        log.warn("Importing $name failed", failure)
                        ImportOutcome.Failed(ImportFailure.COPY_FAILED)
                    }
                }
            },
        )

    override fun start() {
        lifetime.checkOpen()
        if (started) return
        started = true
        val scope = lifetime.scope

        ocrFacade.watchQueue(scope)
        // A file the OCR drain read no longer matches its hash: rescan once it has settled.
        scope.launch { ocrFacade.staleFiles.consumeEach { scheduleSettleRescan(WriteSettle.QUIET_MILLIS) } }

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
        // was down, a run left half-suggested. Each costs a single query when its queue is empty,
        // once the initial rescan below has let the drains start (see openSession).
        queues.kickAll()

        // Journal changes are routed apart from PDFs, so journal sync never scans or hashes PDFs. An
        // event that cannot be read is logged and passed over: one event never ends the watch.
        scope.launch {
            fs
                .watch()
                .mapNotNull { event ->
                    log.runLogged("Reading the change at ${event.relPath}") { watchAction(event, deviceId) }
                }.settled()
                .collect { act(it) }
        }

        // Initial rescan.
        scope.launch { log.runLogged("Initial rescan") { doWatchTriggeredRescan() } }

        // Another device's rule list arrives as a journal setting; the rules table only caches it.
        scope.launch {
            coordinator.settingFlow(LibrarySettingKeys.AUTO_MATCH_RULES).collect {
                log.runLogged("Caching the auto-match rules") { ruleSource.mirror() }
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

    /** Carry out one [WatchAction] of the folder watch; a failure is logged, never thrown. */
    private suspend fun act(action: WatchAction) {
        when (action) {
            WatchAction.Ignore -> {}

            is WatchAction.IngestObject -> {
                log.runLogged("Journal object ingest") { coordinator.ingestObject(action.relPath) }
                // A remote OCR-engine override may have just folded in; re-select promptly
                // instead of waiting for the next rescan.
                log.runLogged("OCR sidecar reconcile") { ocrFacade.pipeline.reconcileSidecars() }
            }

            WatchAction.ReconcileJournal -> {
                log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                log.runLogged("OCR sidecar reconcile") { ocrFacade.pipeline.reconcileSidecars() }
            }

            WatchAction.ReconcileOcr -> {
                log.runLogged("OCR sidecar reconcile") { ocrFacade.pipeline.reconcileSidecars() }
            }

            WatchAction.Rescan -> {
                log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                log.runLogged("Watch-triggered rescan") { doWatchTriggeredRescan() }
            }
        }
    }

    private suspend fun doWatchTriggeredRescan() {
        rescan()
        queues.kickAll()
        trashPurge.purgeExpired()
    }

    override suspend fun close() {
        // Background loops, drains and every owned operation, all in one: see SessionLifetime.
        lifetime.close()
        // Waits out a coordinator write a caller started outside the lifetime, then refuses more.
        coordinator.close()
        aiFacade.close()
        // DB ownership stays with the environment; it may close the DB after this shutdown barrier.
    }

    private fun map(result: ImportResult): ImportOutcome =
        when (result) {
            is ImportResult.Imported -> ImportOutcome.Imported(result.docId, result.relPath)
            is ImportResult.Duplicate -> ImportOutcome.Duplicate(result.existingDocId, result.existingPath)
        }

    companion object {
        private const val METADATA_RECONCILIATION_INTERVAL_MS = 15L * 60L * 1000L

        /** Slack past a deferred file's settle time, so the follow-up does not land just short. */
        private const val SETTLE_RESCAN_MARGIN_MS = 500L

        /**
         * Boot a session over [deps]: move device state an older index still holds into the device
         * database, settle the device id ([DeviceIdentity]: a fresh one for a restored or cloned
         * installation), wire the journal engine, repositories, pipelines, then do the initial fold
         * so [documents] is populated before the caller's initial rescan.
         *
         * Refuses, with [VerifiedPublicationUnavailableException], a library on a filesystem that
         * cannot publish without replacement: there is no other journal format to write. Every open
         * publishes through the same primitive before it writes anything ([DeviceIdentity]: this
         * installation's marker, or a throwaway object once that is there), so each one finds out.
         */
        suspend fun openSession(deps: SessionDeps): LibrarySessionImpl =
            openSession(deps, JournalOp.DocUndelete.WRITTEN)

        /** [openSession], recording a deleted document's return as a `doc-undelete` when [writeUndelete]. */
        internal suspend fun openSession(
            deps: SessionDeps,
            writeUndelete: Boolean,
        ): LibrarySessionImpl {
            val lifetime = SessionLifetime(deps.scope)
            // Before anything reads the device id: an index from before device.db holds it.
            moveDeviceState(deps)
            val indexer = Indexer(deps.fs, deps.db, deps.clock, deps.log)
            val coordinator = createCoordinator(deps, indexer, writeUndelete)
            val deviceId = DeviceIdentity(deps.deviceDb.keyValueDao(), deps.fs, deps.installationId, deps.log).resolve()
            foldJournal(deps, coordinator)

            // A bake's relink hands the document's text on through the OCR pipeline, built after the
            // library it belongs to; no bake runs before the session is open.
            lateinit var bakeRelink: BakeRelink
            val library =
                buildLibrary(deps, lifetime, coordinator) { original, baked -> bakeRelink.relink(original, baked) }
            // Built before the OCR pipeline because it is also the pipeline's AI engine: one
            // facade, so the vision calls share the cached endpoint client with chat and edits.
            val ai =
                AiFacadeImpl(
                    deps.db,
                    chats = deps.deviceDb.keyValueDao(),
                    ownWrite = { lifetime.owned(it) },
                    clock = deps.clock,
                    configProvider = deps.settings.aiConfig,
                )
            // Each drain treats a document without a row as gone and drops this device's work for it,
            // but until the first rescan an index may not know the library's documents (a rebuilt one
            // has no rows at all), so no drain starts before then.
            val rescanned = CompletableDeferred<Unit>()
            val enrichment = buildEnrichment(deps, lifetime, coordinator, library, ai, rescanned)
            val edits = buildEditQueue(deps, lifetime, library, ai, rescanned)
            val ocr =
                buildOcr(
                    deps,
                    lifetime,
                    deviceId,
                    library,
                    ai,
                    rescanned,
                    // Only the *intent* is recorded here: this runs on the OCR queue-drain thread,
                    // where a network call would stall OCR of every remaining document.
                    onOcrProduced = { docId -> enrichment.queue.enqueue(docId) },
                )
            bakeRelink = BakeRelink(coordinator, ocr.pipeline, deps.deviceDb.aiEnrichQueueDao(), deps.log)
            val scanReports = MutableStateFlow<ScanReport?>(null)
            return LibrarySessionImpl(
                deviceId = deviceId,
                deps = deps,
                lifetime = lifetime,
                coordinator = coordinator,
                indexer = indexer,
                library = library,
                scanReports = scanReports,
                ocrFacade = ocr,
                maintenance = buildMaintenance(deps, lifetime, coordinator, library, scanReports),
                aiEditQueue = edits.queue,
                queues = buildWork(deps, lifetime, ocr, enrichment, edits, rescanned),
                aiFacade = ai,
            )
        }
    }
}

/**
 * Bundle of everything a [LibrarySessionImpl] needs, assembled per platform: `jvmSessionDeps` on a
 * plain JVM, `androidSessionDeps` on Android.
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
    /** How many pages render at once, thumbnails and page views together; the platform's pick. */
    val renderParallelism: Int,
    val scope: CoroutineScope,
    val settings: SessionSettings,
    /** Where the session reports what fails in the background, with no caller to tell. */
    val log: NpnLog,
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
    val rules: AutoMatchRuleRepository,
    val ruleSource: AutoMatchRuleSource,
    val ruleApplier: RuleApplier,
    /** The writer itself, for work that already runs as the session's: the drains, owned facet calls. */
    val writer: MetadataWriterImpl,
    /** The document lifecycle itself, for work that already runs as the session's (a split's trash). */
    val lifecycle: DocLifecycleImpl,
    /** [writer] and [lifecycle] for a caller outside the session: each call owned by its lifetime. */
    val ownedWriter: OwnedWriter,
    val reviewConfig: ReviewConfigFacade,
    val content: DocContentFacade,
    val imports: ImportService,
)
