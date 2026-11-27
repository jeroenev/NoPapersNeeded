package dev.npn.core.app

import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.AiPriceBook
import dev.npn.core.ai.AiPrices
import dev.npn.core.ai.RoomAiLedger
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.app.aiedit.AiAutoEnricher
import dev.npn.core.app.aiedit.AiEditQueue
import dev.npn.core.fs.DocumentFacts
import dev.npn.core.fs.KeepStore
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.holds
import dev.npn.core.index.AckRecords
import dev.npn.core.index.AuthoredObjects
import dev.npn.core.index.DuplicateResolver
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.PendingIntents
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SplitService
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrEngineSelector
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.CreationDates
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.rules.RuleApplier
import dev.npn.core.sync.JournalDocView
import dev.npn.core.sync.SyncEngine
import dev.npn.core.sync.SyncHooks
import dev.npn.core.sync.SyncJournal
import dev.npn.core.sync.SyncService
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.QueueDrainer
import dev.npn.core.work.drainedQueue
import dev.npn.core.xmp.BakeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first

/**
 * Every part of one open session, each built once, in dependency order, over the journal
 * [coordinator] of this installation's [deviceId]: the one place to look for where a facet comes
 * from and what it is given. Construction only wires; what opening has to do to the library's data
 * before the session starts is [prepare]'s.
 *
 * [openSession] builds it once the journal is folded ([foldJournal]); the session then
 * hands its parts out as facets.
 */
internal class SessionGraph(
    private val deps: SessionDeps,
    val lifetime: SessionLifetime,
    val coordinator: JournalCoordinator,
    val indexer: Indexer,
    val deviceId: String,
) {
    private val db = deps.db
    private val log = deps.log

    /** The journal actors this installation published, which history labels "This device". */
    private val localActors = LocalJournalActors(deps.deviceDb.keyValueDao())

    /** Every paid AI request this device makes for this library is recorded here; never synced. */
    private val aiLedger = RoomAiLedger(deps.deviceDb.aiUsageDao())

    /**
     * What a model costs: catalog prices this device keeps fresh where the host fetches them
     * ([SessionDeps.priceSource]), else the build's snapshot.
     */
    private val aiPrices: AiPrices =
        deps.priceSource?.let { source ->
            AiPriceBook(
                store = deps.deviceDb.keyValueDao(),
                source = source,
                clock = deps.clock,
                scope = lifetime.scope,
                log = log,
                ownWrite = { lifetime.owned(it) },
            )
        } ?: AiPrices.Snapshot

    val aiSpend: AiSpendFacade =
        AiSpendFacadeImpl(
            fts = db.ftsDao(),
            dao = deps.deviceDb.aiUsageDao(),
            ledger = aiLedger,
            prices = aiPrices,
            configProvider = deps.settings.aiConfig,
            clock = deps.clock,
        )

    /**
     * Opened by the session's first rescan. Each drain treats a document without a row as gone and
     * drops this device's work for it, but until then an index may not know the library's documents
     * (a rebuilt one has no rows at all), so no drain starts before it.
     */
    val rescanned = CompletableDeferred<Unit>()

    /**
     * Chat, suggestions and the AI OCR engine: one facade, so the vision calls share the cached
     * endpoint client with chat and edits.
     */
    val reviewConfig: ReviewConfigFacade = JournalReviewConfig(coordinator, db.documentDao(), lifetime)

    val ai =
        AiFacadeImpl(
            db,
            chats = deps.deviceDb.keyValueDao(),
            ownWrite = { lifetime.owned(it) },
            clock = deps.clock,
            reviewModeProvider = { reviewConfig.reviewMode.first() },
            ledger = aiLedger,
            prices = aiPrices,
            log = log,
            configProvider = deps.settings.aiConfig,
        ).also { lifetime.releaseOnClose(it::close) }

    /** What this device may spend on the endpoint unattended, for enrichment and OCR alike. */
    private val policy =
        UnattendedAiPolicy(
            spentLast30Days = { aiLedger.totals(deps.clock.nowMillis() - AI_SPEND_WINDOW_MS) },
            readConfig = deps.settings.aiConfig,
        )

    /**
     * The one cooldown of [ai]'s endpoint, shared by every queue that calls it (automatic enrichment
     * and queued edit runs), so they do not each probe an endpoint that is down.
     */
    private val endpointHealth = EndpointHealth()

    val documents: DocumentRepository =
        DocumentRepositoryImpl(db, mapping = deps.computation, origins = localActors::origins)
    val tags: TagRepository = TagRepositoryImpl(db.tagDao())
    val savedViews = SavedViewRepositoryImpl(coordinator, lifetime)
    val fieldRegistry = FieldRegistryImpl(db, coordinator, lifetime)
    val storagePath = JournalStoragePath(coordinator, lifetime)

    private val pathRules =
        StoragePathRuleApplicator(deps.fs, db, storagePath::rule) { docId, path ->
            coordinator.commitBuilt { state ->
                // Once per change: a document that cannot follow its location (storage that cannot
                // move it, a move that keeps failing) would have it journaled again after every write.
                if (state.inheritedProp(docId, SystemProperty.STORAGE_PATH) == path) {
                    emptyList()
                } else {
                    listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, path))
                }
            }
        }

    /** The rules' cache in the index, mirrored from the journal when another device's list arrives. */
    val ruleSource = AutoMatchRuleSource(db.autoMatchRuleDao(), coordinator, log)

    /** Runs the auto-match rules on a document: after a field write, and on text new to the library. */
    private val ruleApplier =
        RuleApplier(
            rules = ruleSource::current,
            ftsDao = db.ftsDao(),
            documentDao = db.documentDao(),
            coordinator = coordinator,
            onMetadataApplied = pathRules::applyFor,
        )
    val rules: AutoMatchRuleRepository =
        AutoMatchRuleRepositoryImpl(db.autoMatchRuleDao(), ruleSource, ruleApplier, coordinator, lifetime, deps.ids)

    private val bakes = BakeLock()
    private val trash = TrashOps(deps.fs)
    val trashRetention = JournalTrashRetention(coordinator, lifetime)

    /** The library's reminder dates and the daily check built on them; the device's claims in `device.db`. */
    val reminders =
        JournalReminders(
            coordinator = coordinator,
            indexDb = db,
            notifications = deps.deviceDb.reminderNotificationDao(),
            deviceKv = deps.deviceDb.keyValueDao(),
            lifetime = lifetime,
            clock = deps.clock,
            log = log,
        )

    /**
     * This library's devices' acknowledgment records: this installation writes its own after each
     * background rescan, and the purge waits for every active device's.
     */
    val ackRecords =
        AckRecords(
            fs = deps.fs,
            db = db,
            authored = AuthoredObjects(deps.deviceDb.keyValueDao()),
            clock = deps.clock,
            deviceId = deviceId,
            installationId = deps.installationId,
            ids = deps.ids,
        )

    /** The keep store: copies of the documents the app took from their path or put back. */
    val keepStore = KeepStore(deps.fs, deps.installationId, deps.ids)

    /** Whether every device reads the keep store, so this one may write it; read after each background rescan. */
    val keepGate =
        KeepGate(ackRecords, coordinator::setting, deps.clock).also { gate ->
            // A build that lists keep-v1 reads doc-undelete too: once every device does, it is written.
            coordinator.undeleteRead = { gate.open }
        }

    /** This device's durable intents: what a crash cut short, settled before each background rescan. */
    val intents =
        PendingIntents(
            deps.deviceDb.pendingOpDao(),
            deps.fs,
            keepStore,
            keepOn = { keepGate.open },
            clock = deps.clock,
            log = log,
            ids = deps.ids,
        )

    /** The devices that share the library, and their retirement. */
    val devices = JournalDevices(coordinator, ackRecords, lifetime, deps.clock, deviceId, keepGate)

    /** The trash auto-purge by [trashRetention], run after each watch-triggered rescan. */
    val trashPurge =
        TrashPurge(
            coordinator,
            db,
            trash,
            trashRetention,
            deps.clock,
            log,
            CausalPurge(ackRecords, coordinator::setting, deps.clock),
            keepStore,
        )

    val writer =
        MetadataWriterImpl(
            lifetime,
            coordinator,
            db,
            pathRules,
            log,
            applyAutoMatch = { ruleApplier.applyToDoc(it, organize = false) },
        )
    val lifecycle =
        DocLifecycleImpl(
            lifetime,
            coordinator,
            db,
            deps.fs,
            trash,
            log,
            bakes,
            clock = deps.clock,
            followUp = writer::followUpRelocation,
            keep = keepStore,
            keepOn = { keepGate.open },
            operations = intents,
            ids = deps.ids,
        )

    /** Automatic AI enrichment: unattended, so it writes through [writer] without review. */
    val enrichment =
        drainedQueue(lifetime.scope, "AI enrichment queue", log, ready = rescanned, build = { onEnqueued ->
            AiAutoEnricher(
                documentDao = db.documentDao(),
                ftsDao = db.ftsDao(),
                queueDao = deps.deviceDb.aiEnrichQueueDao(),
                ai = ai,
                meta = writer,
                fieldRegistry = fieldRegistry,
                policy = policy,
                clock = deps.clock,
                health = endpointHealth,
                canWrite = coordinator::canWrite,
                onEnqueued = onEnqueued,
            )
        }, step = AiAutoEnricher::processNext)

    /**
     * A rescan the OCR pipeline asks for once a file it read no longer hashes to its document
     * (replaced, or still being written). Conflated, since one rescan sorts out every such file; the
     * session consumes it once started.
     */
    val staleFiles = Channel<Unit>(Channel.CONFLATED)

    /**
     * The report of a rescan a relink or a bake ran under the journal's lock, which the session then
     * follows up as it does its own. Conflated, since the latest rescan saw the library last; the
     * session consumes it once started.
     */
    val rescans = Channel<ScanReport>(Channel.CONFLATED)

    private val ocrEngines = OcrEngineSelector(local = deps.ocrEngine, ai = ai.ocrEngine, aiAuto = policy::autoOcr)

    /**
     * This device's OCR pipeline. Text this device just produced runs the library's auto-match rules,
     * then queues the enrichment, so rule-applied tags and fields are part of what a paid follow-up
     * sees. Text only re-indexed (a rebuild, a synced sidecar) runs neither.
     */
    val ocrPipeline =
        OcrPipeline(
            fs = deps.fs,
            pdfEngine = deps.pdfEngine,
            engines = ocrEngines,
            index = db,
            ocrQueueDao = deps.deviceDb.ocrQueueDao(),
            deviceId = deviceId,
            language = deps.settings.ocrLanguage,
            clock = deps.clock,
            onTextProduced = { docId, text -> ruleApplier.applyToDoc(docId, text) },
            // Only the *intent* is recorded here: this runs on the OCR queue-drain thread, where a
            // network call would stall OCR of every remaining document.
            onOcrProduced = { docId -> enrichment.queue.enqueue(docId) },
            onStaleFile = { staleFiles.trySend(Unit) },
        )

    /** Ends a bake in place, handing the document's text on through [ocrPipeline]. */
    private val bakeRelink =
        BakeRelink(
            coordinator,
            ocrPipeline,
            deps.deviceDb.aiEnrichQueueDao(),
            log,
            rescanned = { rescans.trySend(it) },
        )

    // Single shared gate so thumbnail + page rendering share one bounded concurrency budget and a
    // first-load burst (e.g. 200 PDFs) cannot flood the dispatchers.
    private val renderGate = RenderGate(deps.renderParallelism, deps.computation)

    val creationDates =
        CreationDates(
            deps.pdfEngine,
            deps.fs,
            db.documentDao(),
            renderGate,
            log,
            onStaleFile = { staleFiles.trySend(Unit) },
        )

    /**
     * What the journal says of a document, each answer read under the writer lock: for a delete a
     * step outside it makes by a fact that never goes back, such as a bake's original once its relink
     * stands ([dev.npn.core.fs.DeleteGate]).
     */
    private val journalFacts =
        object : DocumentFacts {
            override suspend fun isDeleted(docId: String): Boolean = coordinator.serialized { it.isDeleted(docId) }

            override suspend fun isTrashed(docId: String): Boolean = coordinator.serialized { it.isTrashed(docId) }

            override suspend fun successorOf(docId: String): String = coordinator.serialized { it.successorOf(docId) }
        }

    val content =
        DocContentFacadeImpl(
            lifetime,
            db,
            deps.fs,
            deps.pdfEngine,
            ThumbnailService(
                engine = deps.pdfEngine,
                fs = deps.fs,
                store = deps.thumbnailStore,
                documentDao = db.documentDao(),
                renderGate = renderGate,
            ),
            BakeService(
                deps.fs,
                deps.xmpIO,
                log,
                deps.ids,
                indexer.bakeAsides,
                ::shownWithoutItsFile,
                journalFacts,
                keep = keepStore,
                keepOn = { keepGate.open },
                operations = intents,
                clock = deps.clock,
            ),
            coordinator,
            relinkBake = bakeRelink::relink,
            renderGate,
            bakes,
            log,
        )

    val imports =
        ImportService(
            fs = deps.fs,
            db = db,
            ocrQueueDao = deps.deviceDb.ocrQueueDao(),
            clock = deps.clock,
            ruleProvider = storagePath::rule,
            serialize = { coordinator.serialized(it) },
            log = log,
            ids = deps.ids,
            keep = keepStore,
            keepOn = { keepGate.open },
        )

    /** Bulk AI edits handed to the background; their writes wait for a human review. */
    val edits =
        drainedQueue(lifetime.scope, "AI edit queue", log, ready = rescanned, build = { onEnqueued ->
            AiEditQueue(
                dao = deps.deviceDb.aiEditJobDao(),
                documents = documents,
                content = content,
                meta = writer,
                ai = ai,
                fieldRegistry = fieldRegistry,
                tags = tags,
                readConfig = deps.settings.aiConfig,
                clock = deps.clock,
                health = endpointHealth,
                onEnqueued = onEnqueued,
                ownWrite = { lifetime.owned(it) },
                newJobId = deps.ids::next,
            )
        }, step = AiEditQueue::processNext)

    /** Drains the OCR queue, for [ocr] and [work] alike. */
    private val ocrWorker =
        QueueDrainer(lifetime.scope, "OCR queue", log, ready = rescanned, step = ocrPipeline::processNext)

    val ocr =
        OcrFacadeImpl(
            lifetime = lifetime,
            documentDao = db.documentDao(),
            queue = deps.deviceDb.ocrQueueDao(),
            pipeline = ocrPipeline,
            engines = ocrEngines,
            writer = writer,
            content = content,
            worker = ocrWorker,
            spend = aiSpend,
            clock = deps.clock,
        )

    val maintenance =
        MaintenanceFacadeImpl(
            lifetime = lifetime,
            db = db,
            deviceDb = deps.deviceDb,
            fs = deps.fs,
            duplicates =
                DuplicateResolver(
                    fs = deps.fs,
                    documentDao = db.documentDao(),
                    duplicateDao = db.duplicateDao(),
                    serialize = { step -> coordinator.serialized { step() } },
                ),
            coordinator = coordinator,
            splitter = SplitService(deps.fs, deps.pdfEngine, imports, db.documentDao(), log),
            verifier = LibraryVerifier(deps.fs, deps.clock, log),
            writer = writer,
            lifecycle = lifecycle,
            rescanned = { rescans.trySend(it) },
            log = log,
        )

    /** The three drained queues behind [LibrarySession.work], which wait for [rescanned]. */
    val work =
        SessionWork(
            lifetime = lifetime,
            ocr = ocrWorker,
            enrichment = enrichment.worker,
            edits = edits.worker,
            reader =
                WorkQueueReader(
                    db.documentDao(),
                    deps.deviceDb.ocrQueueDao(),
                    deps.deviceDb.aiEnrichQueueDao(),
                    deps.deviceDb.aiEditJobDao(),
                    ocrPipeline.inFlight,
                    edits.queue.inFlight,
                    db.invalidationTracker.createFlow("documents"),
                ),
        )

    /**
     * Cloud sync through the host's transfer engine, null when the host wired none. A pass folds the
     * journal objects it brought in, and the rescan it runs for the PDFs it moved is followed up as a
     * relink's is ([rescans]). The engine a transport keeps running is stopped with the session.
     */
    val sync: SyncService? =
        deps.syncTransport?.let { transport ->
            lifetime.releaseOnClose(transport::close)
            deps.syncStaging?.let { staging ->
                SyncService(
                    transport = transport,
                    engine =
                        SyncEngine(
                            deps.fs,
                            db,
                            deps.deviceDb,
                            staging,
                            deps.installationId,
                            SessionSyncHooks(),
                            deps.clock,
                            deps.ids,
                            log,
                        ),
                    kv = deps.deviceDb.keyValueDao(),
                    clock = deps.clock,
                    setup = deps.syncSetup,
                    owned = { pass -> lifetime.owned(pass) },
                    log = log,
                )
            }
        }

    /**
     * Whether the library shows the document [docId] while no file at its row's path holds its bytes
     * (its row is missing, or names a path another file took): a bake's leftover temporary file
     * holding them may be its only copy ([BakeService]). A hash with no row is no document the
     * library knows (the journal calls any id it never saw current), such as a failed bake's output
     * that no relink names.
     */
    private suspend fun shownWithoutItsFile(docId: String): Boolean {
        val row = db.documentDao().getByDocId(docId)?.takeIf { !it.trashed && !it.deleted } ?: return false
        return coordinator.serialized { it.isCurrent(docId) } && !deps.fs.holds(row.relPath, docId)
    }

    /** What a sync pass asks of the session: the journal's answers, a fold, a rescan. */
    private inner class SessionSyncHooks : SyncHooks {
        override suspend fun journalReady(): Boolean = coordinator.canWrite()

        override suspend fun journalView(docIds: Collection<String>): Map<String, JournalDocView> =
            coordinator.syncView(docIds)

        override suspend fun metadataArrived() {
            coordinator.reconcileMetadata()
            ocrPipeline.reconcileSidecars()
        }

        override suspend fun documentsChanged() {
            rescans.trySend(coordinator.rescan())
        }

        override suspend fun trashRetentionDays(): Int? = trashRetention.current()

        override suspend fun trashTimes(): Map<String, Long?> = trashPurge.trashTimes()

        override suspend fun <T> serialized(block: suspend (SyncJournal) -> T): T =
            coordinator.serialized { journal ->
                block(
                    object : SyncJournal {
                        override suspend fun isDeleted(docId: String): Boolean = journal.isDeleted(docId)

                        override suspend fun isTrashed(docId: String): Boolean = journal.isTrashed(docId)

                        override suspend fun isCurrent(docId: String): Boolean = journal.isCurrent(docId)

                        override suspend fun successorOf(docId: String): String = journal.successorOf(docId)
                    },
                )
            }
    }

    /**
     * What opening does to the library's data once everything is wired, before the session starts:
     * saved views an older index kept move into the journal (best effort), and the rules' cache
     * catches up with the journal's list.
     */
    suspend fun prepare() {
        deps.upgradeAtOpen("Moving saved views into the journal") { savedViews.adoptIndexViews(db) }
        ruleSource.mirror()
    }
}
