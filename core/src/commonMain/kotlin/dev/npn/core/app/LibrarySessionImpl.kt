package dev.npn.core.app

import dev.npn.core.ai.AiChat
import dev.npn.core.ai.AiSuggest
import dev.npn.core.app.aiedit.AiEditQueueFacade
import dev.npn.core.db.isLive
import dev.npn.core.einvoice.EInvoiceDetector
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.readDocument
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.EmptyImportException
import dev.npn.core.index.ImportResult
import dev.npn.core.index.IndexProgress
import dev.npn.core.index.RemovedImportException
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SupersededImportException
import dev.npn.core.index.WriteBlock
import dev.npn.core.index.WriteSettle
import dev.npn.core.index.holdsDocument
import dev.npn.core.mail.DeviceMailCursors
import dev.npn.core.mail.MailDocumentSink
import dev.npn.core.mail.MailImportResult
import dev.npn.core.mail.MailService
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.NpnLog
import dev.npn.core.util.Sensitive.Kind.DOCUMENT
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.sensitive
import dev.npn.core.util.warn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    /** Internal, not private, for the library simulation, which writes as a released build would. */
    internal val coordinator = graph.coordinator
    private val ocrFacade = graph.ocr
    private val ocrPipeline = graph.ocrPipeline
    private val staleFiles = graph.staleFiles
    private val otherRescans = graph.rescans
    private val queues = graph.work
    private val syncService = graph.sync

    /** The drains' gate, opened here by the first rescan that succeeds ([SessionGraph.rescanned]). */
    private val drainsReady = graph.rescanned
    private val creationDates = graph.creationDates

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
    private val fieldRegistryImpl = graph.fieldRegistry
    override val fieldRegistry: FieldRegistry = fieldRegistryImpl
    override val content: DocContentFacade = graph.content
    override val reviewConfig: ReviewConfigFacade = graph.reviewConfig
    override val trashRetention: TrashRetentionFacade = graph.trashRetention
    override val devices: DevicesFacade = graph.devices
    override val reminders: RemindersFacade = graph.reminders
    override val maintenance: MaintenanceFacade = graph.maintenance
    private val editQueue = graph.edits.queue
    override val aiEditQueue: AiEditQueueFacade = editQueue
    override val aiSpend: AiSpendFacade = graph.aiSpend

    /** The trash auto-purge, run after each [backgroundRescan]. */
    internal val trashPurge: TrashPurge = graph.trashPurge
    private val ackRecords = graph.ackRecords

    // The facet properties only narrow the type a consumer sees: the session is the ingest facet,
    // [MetadataWriterImpl] backs two write facets, and [AiFacadeImpl] both AI facets.
    override val ingest: IngestFacade get() = this
    override val ocr: OcrFacade = ocrFacade
    override val work: WorkFacade = queues
    private val writer = graph.writer
    override val meta: MetadataWriter = writer
    override val tagAdmin: TagAdmin = graph.writer
    override val docLifecycle: DocLifecycle = graph.lifecycle
    override val aiChat: AiChat = graph.ai
    override val aiSuggest: AiSuggest = graph.ai
    override val sync: SyncFacade = syncService ?: NoSyncFacade

    /**
     * Email's way into the library: the import path every picked file takes, except that bytes of a
     * document the user trashed or deleted stay out. Mail is read again on its own (a start point
     * moved back, a renumbered folder, another device setting the account up), and that must not
     * undo what the user removed.
     */
    internal val mailSink: MailDocumentSink =
        object : MailDocumentSink {
            override suspend fun importPdf(
                bytes: ByteArray,
                suggestedName: String,
            ): MailImportResult = owned { importFromMail(suggestedName, bytes).first }

            override suspend fun emailPdfOf(messageId: String): MailImportResult? =
                owned {
                    val known = deps.db.documentFieldDao().documentsWith(FieldKeys.EMAIL_MESSAGE_ID, messageId)
                    when {
                        known.any { it.isLive && fs.holdsDocument(it) } -> MailImportResult.Duplicate

                        known.any { it.trashed || it.deleted } -> MailImportResult.Removed

                        // None, or only one whose file is gone: made again, as any import brings it back.
                        else -> null
                    }
                }

            override suspend fun importEmailPdf(
                bytes: ByteArray,
                suggestedName: String,
                messageId: String?,
            ): MailImportResult =
                owned {
                    val (result, docId) = importFromMail(suggestedName, bytes)
                    if (docId != null && messageId != null) {
                        log.runLogged("Recording the Message-ID of an email PDF") {
                            writer.fillExtracted(docId, mapOf(FieldKeys.EMAIL_MESSAGE_ID to messageId))
                        }
                    }
                    result
                }

            override suspend fun importImage(
                bytes: ByteArray,
                suggestedName: String,
            ): MailImportResult =
                owned {
                    val pdf =
                        runCatchingCancellable { deps.pdfEngine.imagesToPdf(listOf(bytes)) }.getOrElse {
                            log.warn("Making a PDF of an email image failed", it)
                            return@owned MailImportResult.Failed
                        }
                    importFromMail(suggestedName, pdf).first
                }
        }

    /**
     * What importing [bytes] from an email came to, and the document they are when they are one the
     * library shows. [name] comes from the email (a subject, an attachment's name): no log line may
     * carry it.
     */
    private suspend fun importFromMail(
        name: String,
        bytes: ByteArray,
    ): Pair<MailImportResult, String?> {
        val result = runCatchingCancellable { importService.importBytes(bytes, name, reviveRemoved = false) }
        if (result.exceptionOrNull() is RemovedImportException) return MailImportResult.Removed to null
        val outcome = fillEInvoiceFields(importing { result.getOrThrow() }, holdForReview = true)
        val docId =
            when (outcome) {
                is ImportOutcome.Imported -> outcome.docId
                is ImportOutcome.Duplicate -> outcome.existingDocId
                is ImportOutcome.Failed -> null
            }
        return outcome.toMailResult() to docId
    }

    private val mailService =
        MailService(
            store = JournalMailAccount(coordinator, lifetime),
            credentials = deps.mailCredentials,
            // This installation's id in this library: one per library on the desktop, so two
            // libraries' keyring entries stay apart.
            keyScope = deviceId,
            cursors = DeviceMailCursors(deps.deviceDb.mailCursorDao(), deps.deviceDb.keyValueDao()),
            transport = deps.mailTransport,
            sink = mailSink,
            pdfRenderer = deps.mailPdfRenderer,
            clock = deps.clock,
            log = deps.log,
            scope = lifetime.scope,
        )
    override val mail: MailFacade =
        object : MailFacade {
            override val state = mailService.state

            override suspend fun saveAccount(
                account: dev.npn.core.mail.MailAccount,
                password: String?,
                device: dev.npn.core.mail.MailDeviceChoices,
            ) = lifetime.owned { mailService.saveAccount(account, password, device) }

            override suspend fun clearPassword() = lifetime.owned { mailService.clearPassword() }

            override suspend fun lookUpPassword() = lifetime.owned { mailService.lookUpPassword() }

            override suspend fun testConnection(
                account: dev.npn.core.mail.MailAccount,
                password: String?,
                allowPlain: Boolean,
            ) = lifetime.owned { mailService.testConnection(account, password, allowPlain) }

            override suspend fun checkNow() = lifetime.owned { mailService.checkNow() }

            override suspend fun retryPassedOver() = lifetime.owned { mailService.retryPassedOver() }

            override fun cancelCheck() = mailService.cancelCheck()

            override val importedDocuments = mailService.importedDocuments
        }

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
    // can finish without another watch event.
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
    ): ImportOutcome =
        owned {
            importing { importService.importPdf(source, suggestedName) }
                .let { fillEInvoiceFields(it) }
        }

    override suspend fun holdsDocument(docId: String): Boolean =
        owned {
            val row = deps.db.documentDao().getByDocId(docId)
            row != null && row.isLive && fs.holdsDocument(row)
        }

    /** Import a PDF held in memory; see [IngestFacade.importPdfBytes]. Email has its own way in, [mailSink]. */
    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome =
        owned {
            importing { importService.importBytes(bytes, suggestedName) }
                .let { fillEInvoiceFields(it) }
        }

    /**
     * Fill a freshly imported document's blank fields from an embedded structured e-invoice, while
     * the library's E-invoice fields setting is on. Only a compatible blank field is written, so a
     * value somebody else already wrote wins, and only an import does this: a rescan never re-applies
     * it. The [FieldKeys.EINVOICE] marker is written only with a value it filled or held.
     *
     * With [holdForReview] (an email's attachment, which anybody can send) nothing is filled: the
     * values wait on the processing queue for the user's review ([AiEditQueue.holdForReview]).
     *
     * A failure is logged; the import stands either way. The outcome's path is re-read because
     * filling fields can trigger the storage-path rule, which may have moved the file after the
     * import placed it.
     */
    private suspend fun fillEInvoiceFields(
        outcome: ImportOutcome,
        holdForReview: Boolean = false,
    ): ImportOutcome {
        val imported = outcome as? ImportOutcome.Imported ?: return outcome
        if (!fieldRegistryImpl.eInvoiceFieldsOn()) return outcome
        // By its id: the path can hold an email's subject or a document's title.
        log.runLogged("Reading the e-invoice of document ${imported.docId}") {
            val bytes = fs.readDocument(imported.relPath, imported.docId) ?: return@runLogged
            val invoice = EInvoiceDetector.detect(deps.pdfEngine, bytes) ?: return@runLogged
            val correspondents =
                fieldRegistryImpl.distinctValues(FieldKeys.CORRESPONDENT, KNOWN_CORRESPONDENTS_LIMIT).first()
            val values = invoice.documentFields(correspondents)
            val marker = FieldKeys.EINVOICE to invoice.format.wire
            if (holdForReview) {
                val held = writer.fillableExtracted(imported.docId, values)
                if (held.isEmpty()) return@runLogged
                editQueue.holdForReview(imported.docId, held)
                writer.fillExtracted(imported.docId, mapOf(marker))
            } else {
                writer.fillExtracted(imported.docId, values, marker)
            }
        }
        val currentPath =
            deps.db
                .documentDao()
                .getByDocId(imported.docId)
                ?.relPath ?: return imported
        return imported.copy(relPath = currentPath)
    }

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
            importing { importService.importBytes(pdf, suggestedName) }
        }

    /**
     * Run one import, turning what can go wrong with the file into an [ImportFailure]. The failure is
     * logged without the file's name, which can be an email's subject or a document's title.
     */
    private inline fun importing(import: () -> ImportResult): ImportOutcome =
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
                        log.warn("Importing a file failed", failure)
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

        // Email import: follows the account setting and polls while this session is open. The
        // first check runs at once; closing the library cancels the loop, so nothing imports from
        // a closed app.
        mailService.start()

        // Journal changes are routed apart from PDFs, so journal sync never scans or hashes PDFs. An
        // event that cannot be read is logged and passed over: one event never ends the watch. A
        // watch the platform cannot give (the JVM's watch service out of inotify instances) is
        // logged and stays off; the periodic reconcile below still runs.
        scope.launch {
            log.runLogged("Watching the library") {
                fs
                    .watch()
                    .mapNotNull { event ->
                        log.runLogged(
                            "Reading the change at ${sensitive(event.relPath, DOCUMENT)}",
                        ) { watchAction(event, deviceId) }
                    }.settled(timeSource = deps.timeSource)
                    .collect { act(it) }
            }
        }

        // Initial rescan. Ended either way, a session closed under it included, for awaitInitialRescan.
        scope
            .launch { log.runLogged("Initial rescan") { backgroundRescan() } }
            .invokeOnCompletion { initialRescanEnded.complete(Unit) }

        // The documents' creation dates, read from their PDFs once the initial rescan has listed them.
        scope.launch {
            drainsReady.await()
            log.runLogged("Reading the documents' creation dates") { creationDates.follow() }
        }

        // Another device's rule list arrives as a journal setting; the rules table only caches it.
        scope.launch {
            coordinator.settingFlow(LibrarySettingKeys.AUTO_MATCH_RULES).collect {
                log.runLogged("Caching the auto-match rules") { ruleSource.mirror() }
            }
        }

        // Cloud sync's own schedule: a cheap due-check per minute against the configured interval,
        // plus the debounced "something changed" requests the watch below files.
        syncService?.start(scope)

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
            // A new journal object (this device's own edit, or one a folder syncer dropped in) is
            // exactly what the cloud remote is waiting for.
            is WatchAction.IngestObject -> {
                log.runLogged("Journal object ingest") { coordinator.ingestObject(action.relPath) }
                syncService?.requestSoon()
            }

            WatchAction.ReconcileJournal -> {
                log.runLogged("Metadata reconcile") { coordinator.reconcileMetadata() }
                log.runLogged("OCR sidecar reconcile") { ocrPipeline.reconcileSidecars() }
                syncService?.requestSoon()
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
     * or settled a file an OCR run waited for, a cloud sync pass is asked for soon, this device's
     * acknowledgment record is written when it has something new to say, and the trash is purged.
     */
    private suspend fun backgroundRescan() {
        rescan()
        queues.kickAll()
        syncService?.requestSoon()
        log.runLogged("Writing this device's acknowledgment record") {
            coordinator.serialized { ackRecords.publish() }
        }
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

        /** Most correspondents an e-invoice's parties are matched against: more than a library names. */
        private const val KNOWN_CORRESPONDENTS_LIMIT = 10_000
    }
}
