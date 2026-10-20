package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.aiedit.AiEditQueueFacade
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.EmptyImportException
import dev.npn.core.index.ImportResult
import dev.npn.core.index.IndexProgress
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SupersededImportException
import dev.npn.core.index.WriteBlock
import dev.npn.core.index.WriteSettle
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything wired for one opened library folder. Constructed by [openSession] (which settles the
 * device identity and runs the initial fold), then [start]ed.
 *
 * The session itself is the library's lifecycle and its ingest: imports, rescans, the folder watch
 * and the periodic reconcile. Every other facet is its own object, built by its [SessionGraph].
 *
 * Everything the session runs — background loops, queue drains, caller-driven operations — belongs
 * to one [SessionLifetime] under [SessionDeps.scope], so [close] stops all of it at once, for good.
 */
internal class LibrarySessionImpl(
    private val deps: SessionDeps,
    graph: SessionGraph,
) : LibrarySession,
    IngestFacade {
    /** This installation's device id in the library's journal ([DeviceIdentity]). */
    internal val deviceId: String = graph.deviceId
    private val lifetime = graph.lifetime
    private val coordinator = graph.coordinator
    private val ocrFacade = graph.ocr
    private val ocrPipeline = graph.ocrPipeline
    private val staleFiles = graph.staleFiles
    private val otherRescans = graph.rescans
    private val queues = graph.work

    /** The drains' gate, opened here by the first rescan that succeeds ([SessionGraph.rescanned]). */
    private val drainsReady = graph.rescanned

    /** Completed once the initial rescan [start] launched has ended, however it ended. */
    private val initialRescanEnded = CompletableDeferred<Unit>()
    private val importService = graph.imports
    private val fs get() = deps.fs
    override val log: NpnLog get() = deps.log
    override val writeBlock: StateFlow<WriteBlock?> get() = coordinator.writeBlock

    override val documents: DocumentRepository = graph.documents
    override val tags: TagRepository = graph.tags
    override val savedViews: SavedViewRepository = graph.savedViews
    override val rules: AutoMatchRuleRepository = graph.rules

    /** The rules' cache in the index, mirrored from the journal when another device's list arrives. */
    internal val ruleSource: AutoMatchRuleSource = graph.ruleSource
    override val storagePath: StoragePathFacade = graph.storagePath
    override val fieldRegistry: FieldRegistry = graph.fieldRegistry
    override val content: DocContentFacade = graph.content
    override val reviewConfig: ReviewConfigFacade = graph.reviewConfig
    override val trashRetention: TrashRetentionFacade = graph.trashRetention
    override val maintenance: MaintenanceFacade = graph.maintenance
    override val aiEditQueue: AiEditQueueFacade = graph.edits.queue

    /** The trash auto-purge, run after each [backgroundRescan]. */
    internal val trashPurge: TrashPurge = graph.trashPurge

    // The facet properties only narrow the type a consumer sees: the session is the ingest facet,
    // [MetadataWriterImpl] backs two write facets, and [AiFacadeImpl] both AI facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade = ocrFacade
    override val work: WorkFacade = queues
    override val meta: MetadataWriter = graph.writer
    override val tagAdmin: TagAdmin = graph.writer
    override val docLifecycle: DocLifecycle = graph.lifecycle
    override val aiChat: AiChat = graph.ai
    override val aiSuggest: AiSuggest = graph.ai

    private val scanReports = MutableStateFlow<ScanReport?>(null)
    override val lastScanReport: Flow<ScanReport?> = scanReports.asStateFlow()

    override val scanProgress: Flow<IndexProgress?> = graph.indexer.scanState

    private var started = false

    // Every facet write runs here rather than on the caller's coroutine; see SessionLifetime.owned.
    private suspend fun <T> owned(block: suspend () -> T): T = lifetime.owned(block)

    override suspend fun rescan(): ScanReport = owned { coordinator.rescan().also { afterRescan(it) } }

    /**
     * What follows every rescan that succeeded, this session's own and the one a relink or a bake
     * runs alike ([SessionGraph.rescans]): its report is the last, a file it deferred as still being
     * written gets a follow-up rescan, the documents it found without OCR are queued for it, and
     * the drains may start.
     */
    private suspend fun afterRescan(report: ScanReport) {
        scanReports.value = report
        report.retryAfterMillis?.let { scheduleSettleRescan(it) }
        ocrFacade.reconcileAfterRescan()
        // The index knows the library's documents and has their stored text: from here on a drain
        // may take a document without a row for one that is gone, so the drains may start.
        drainsReady.complete(Unit)
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
                    log.runLogged("Follow-up rescan") { backgroundRescan() }
                }
        }
    }

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome = owned { importing(suggestedName) { importService.importPdf(source, suggestedName) } }

    /** Import a PDF held in memory: for tests, which have no [ExternalSource] to hand. */
    internal suspend fun importPdfBytes(
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

        // Each collector below is logged when it fails rather than left to the scope, which has no
        // handler: on Android an uncaught failure ends the process.
        scope.launch { log.runLogged("Watching the OCR queue") { ocrFacade.watchQueue() } }
        // A file the OCR drain read no longer matches its hash: rescan once it has settled.
        scope.launch { staleFiles.consumeEach { scheduleSettleRescan(WriteSettle.QUIET_MILLIS) } }
        // A relink or a bake rescans under the journal's lock itself: what follows is the same.
        scope.launch {
            otherRescans.consumeEach { report -> log.runLogged("Following up a rescan") { afterRescan(report) } }
        }

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
        // event that cannot be read is logged and passed over: one event never ends the watch. A
        // watch the platform cannot give (the JVM's watch service out of inotify instances) is
        // logged and stays off; the periodic reconcile below still runs.
        scope.launch {
            log.runLogged("Watching the library") {
                fs
                    .watch()
                    .mapNotNull { event ->
                        log.runLogged("Reading the change at ${event.relPath}") { watchAction(event, deviceId) }
                    }.settled(timeSource = deps.timeSource)
                    .collect { act(it) }
            }
        }

        // Initial rescan. Ended either way, a session closed under it included, for awaitInitialRescan.
        scope
            .launch { log.runLogged("Initial rescan") { backgroundRescan() } }
            .invokeOnCompletion { initialRescanEnded.complete(Unit) }

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
                // An initial rescan that failed keeps every drain at the gate, and on desktop nothing
                // else rescans until the library folder changes (Android rescans on every resume).
                if (!drainsReady.isCompleted) {
                    log.runLogged("Retrying the initial rescan") { backgroundRescan() }
                }
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

            // A remote OCR-engine override it folds in is re-selected by the ReconcileOcr that
            // settled() holds for it, once per burst.
            is WatchAction.IngestObject -> {
                log.runLogged("Journal object ingest") { coordinator.ingestObject(action.relPath) }
            }

            WatchAction.ReconcileJournal -> {
                log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                log.runLogged("OCR sidecar reconcile") { ocrPipeline.reconcileSidecars() }
            }

            WatchAction.ReconcileOcr -> {
                log.runLogged("OCR sidecar reconcile") { ocrPipeline.reconcileSidecars() }
            }

            WatchAction.Rescan -> {
                // The rescan reconciles the journal first itself (JournalCoordinator.rescan).
                log.runLogged("Watch-triggered rescan") { backgroundRescan() }
            }
        }
    }

    override suspend fun awaitInitialRescan(): Boolean {
        initialRescanEnded.await()
        return drainsReady.isCompleted
    }

    /**
     * A rescan the session runs itself: at start, for a change in the folder, after a file settled.
     * Then every queue is kicked, as the rescan may have lifted a write block the AI drains wait out
     * or settled a file an OCR run waited for, and the trash is purged.
     */
    private suspend fun backgroundRescan() {
        rescan()
        queues.kickAll()
        trashPurge.purgeExpired()
    }

    /**
     * Stop everything the session runs and release what it holds open (its coordinator, its
     * endpoint client), for good: see [SessionLifetime.close], which is not cancellable either. The
     * databases stay the host's, to close once this returns.
     */
    override suspend fun close() = lifetime.close()

    private fun map(result: ImportResult): ImportOutcome =
        when (result) {
            is ImportResult.Imported -> ImportOutcome.Imported(result.docId, result.relPath)
            is ImportResult.Duplicate -> ImportOutcome.Duplicate(result.existingDocId, result.existingPath)
        }

    companion object {
        private const val METADATA_RECONCILIATION_INTERVAL_MS = 15L * 60L * 1000L

        /** Slack past a deferred file's settle time, so the follow-up does not land just short. */
        private const val SETTLE_RESCAN_MARGIN_MS = 500L
    }
}
