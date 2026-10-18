package dev.npn.core.app

import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.RoomChatHistoryProvider
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.app.aiedit.AiAutoEnricher
import dev.npn.core.app.aiedit.AiEditQueue
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.defs
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.Compaction
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.DuplicateResolver
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SnapshotSupport
import dev.npn.core.index.SplitService
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.HlcWatermarkAllocator
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.SystemProperty
import dev.npn.core.ocr.OcrEngineSelector
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.pathrules.StoragePathRuleApplicator
import dev.npn.core.pdf.RenderGate
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.rules.RuleApplier
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import dev.npn.core.work.QueueDrainer
import dev.npn.core.work.drainedQueue
import dev.npn.core.xmp.BakeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Every part of one open session, each built once, in dependency order, over the journal
 * [coordinator] of this installation's [deviceId]: the one place to look for where a facet comes
 * from and what it is given. Construction only wires; what opening has to do to the library's data
 * before the session starts is [prepare]'s.
 *
 * [LibrarySessionImpl.open] builds it once the journal is folded ([foldJournal]); the session then
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
    val ai =
        AiFacadeImpl(
            db,
            chats = deps.deviceDb.keyValueDao(),
            ownWrite = { lifetime.owned(it) },
            clock = deps.clock,
            configProvider = deps.settings.aiConfig,
        )

    /** What this device may spend on the endpoint unattended, for enrichment and OCR alike. */
    private val policy = UnattendedAiPolicy(deps.settings.aiConfig)

    val documents: DocumentRepository = DocumentRepositoryImpl(db)
    val tags: TagRepository = TagRepositoryImpl(db.tagDao())
    val savedViews = SavedViewRepositoryImpl(coordinator, lifetime)
    val fieldRegistry: FieldRegistry = FieldRegistryImpl(db, coordinator, lifetime)
    val reviewConfig: ReviewConfigFacade = JournalReviewConfig(coordinator, db.documentDao(), lifetime)
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

    /** Runs the auto-match rules on a document: after a field write, and on newly indexed text. */
    private val ruleApplier =
        RuleApplier(
            rules = ruleSource::current,
            ftsDao = db.ftsDao(),
            documentDao = db.documentDao(),
            coordinator = coordinator,
            onMetadataApplied = pathRules::applyFor,
        )
    val rules: AutoMatchRuleRepository =
        AutoMatchRuleRepositoryImpl(db.autoMatchRuleDao(), ruleSource, ruleApplier, coordinator, lifetime)

    private val bakes = BakeLock()
    private val trash = TrashOps(deps.fs)
    val trashRetention = JournalTrashRetention(coordinator, lifetime)

    /** The trash auto-purge by [trashRetention], run after each watch-triggered rescan. */
    val trashPurge = TrashPurge(coordinator, db, trash, trashRetention, deps.clock, log)

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
        )

    // Single shared gate so thumbnail + page rendering share one bounded concurrency budget and a
    // first-load burst (e.g. 200 PDFs) cannot flood the dispatchers.
    private val renderGate = RenderGate(deps.renderParallelism)

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
                thumbnailDao = db.thumbnailDao(),
                documentDao = db.documentDao(),
                renderGate = renderGate,
                clock = deps.clock,
            ),
            BakeService(deps.fs, deps.xmpIO, log),
            coordinator,
            // Built below, from the OCR pipeline; no bake runs before the session is open.
            relinkBake = { original, baked -> bakeRelink.relink(original, baked) },
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
                health = ai.endpointHealth,
                canWrite = coordinator::canWrite,
                onEnqueued = onEnqueued,
            )
        }, step = AiAutoEnricher::processNext)

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
                health = ai.endpointHealth,
                onEnqueued = onEnqueued,
                ownWrite = { lifetime.owned(it) },
            )
        }, step = AiEditQueue::processNext)

    /**
     * A rescan the OCR pipeline asks for once a file it read no longer hashes to its document
     * (replaced, or still being written). Conflated, since one rescan sorts out every such file; the
     * session consumes it once started.
     */
    val staleFiles = Channel<Unit>(Channel.CONFLATED)

    private val ocrEngines = OcrEngineSelector(local = deps.ocrEngine, ai = ai.ocrEngine, aiAuto = policy::autoOcr)

    /**
     * This device's OCR pipeline. Indexed text runs the library's auto-match rules first; the
     * enrichment is queued only for text this device just produced, so rule-applied tags and fields
     * are part of what a paid follow-up sees.
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
            // Deterministic local rules run on any newly indexed text, including a re-index.
            onTextIndexed = { docId, text -> ruleApplier.applyToDoc(docId, text) },
            // Only the *intent* is recorded here: this runs on the OCR queue-drain thread, where a
            // network call would stall OCR of every remaining document.
            onOcrProduced = { docId -> enrichment.queue.enqueue(docId) },
            onStaleFile = { staleFiles.trySend(Unit) },
        )

    val ocr =
        OcrFacadeImpl(
            lifetime = lifetime,
            documentDao = db.documentDao(),
            queue = deps.deviceDb.ocrQueueDao(),
            pipeline = ocrPipeline,
            engines = ocrEngines,
            writer = writer,
            content = content,
            worker =
                QueueDrainer(lifetime.scope, "OCR queue", log, ready = rescanned, step = ocrPipeline::processNext),
            clock = deps.clock,
        )

    /** Ends a bake in place, handing the document's text on through [ocrPipeline]. */
    private val bakeRelink = BakeRelink(coordinator, ocrPipeline, deps.deviceDb.aiEnrichQueueDao(), log)

    val scanReports = MutableStateFlow<ScanReport?>(null)

    val maintenance =
        MaintenanceFacadeImpl(
            lifetime = lifetime,
            db = db,
            duplicates =
                DuplicateResolver(
                    fs = deps.fs,
                    documentDao = db.documentDao(),
                    duplicateDao = db.duplicateDao(),
                    serialize = { step -> coordinator.serialized { step() } },
                ),
            coordinator = coordinator,
            splitter = SplitService(deps.fs, deps.pdfEngine, imports, db.documentDao(), log),
            writer = writer,
            lifecycle = lifecycle,
            scanReports = scanReports,
            log = log,
        )

    /** The three drained queues behind [LibrarySession.work], which wait for [rescanned]. */
    val work =
        SessionWork(
            lifetime = lifetime,
            ocr = ocr.worker,
            enrichment = enrichment.worker,
            edits = edits.worker,
            firstRescan = rescanned,
            reader =
                WorkQueueReader(
                    db.documentDao(),
                    deps.deviceDb.ocrQueueDao(),
                    deps.deviceDb.aiEnrichQueueDao(),
                    deps.deviceDb.aiEditJobDao(),
                    ocr.inFlight,
                    edits.queue.inFlight,
                    db.invalidationTracker.createFlow("documents"),
                ),
        )

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

// The steps [LibrarySessionImpl.open] takes before there is a graph, in the order it takes them.

/**
 * The `kv` keys an index from before [DeviceDatabase] kept for this device. The v1 writer's
 * bookkeeping moves too, so the index sheds it; [DeviceIdentity] then drops it.
 */
private val DEVICE_KEYS = listOf(DeviceIdentity.KEY_DEVICE_ID) + DeviceIdentity.LEGACY_JOURNAL_KEYS

/** Move what an index from before [DeviceDatabase] kept for this device into the device database. */
internal suspend fun moveDeviceState(deps: SessionDeps) =
    LegacyIndexTables.moveDeviceState(deps.db, deps.deviceDb, DEVICE_KEYS, RoomChatHistoryProvider.KEY_PREFIX)

/**
 * The journal writer: storage-v2 content-addressed objects are the only format this build writes
 * (v1 segments are still read). A filesystem that cannot publish without replacement throws
 * [VerifiedPublicationUnavailableException] at its first publication, which [DeviceIdentity] makes
 * before anything else is written, rather than be written in a weaker format.
 */
private suspend fun createObjectWriter(deps: SessionDeps): ContentAddressedJournalWriter {
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
    )
}

internal suspend fun createCoordinator(
    deps: SessionDeps,
    indexer: Indexer,
    writeUndelete: Boolean,
): JournalCoordinator {
    val snapshots = SnapshotSupport(deps.fs, deps.db.metadataProjectionDao())
    return JournalCoordinator(
        writer = createObjectWriter(deps),
        indexer = indexer,
        accumulator = MetadataAccumulator(deps.fs, deps.db, deps.installationId, deps.clock, snapshots),
        compaction = Compaction(SnapshotCompactor(deps.fs, deps.installationId), snapshots),
        clock = deps.clock,
        log = deps.log,
        writeUndelete = writeUndelete,
    )
}

/**
 * Fold the journal, so the documents are there before the caller's initial rescan, and bring the
 * library's own data up to what this build expects: the seeded fields (with the per-document flag
 * an older build may have dropped since), and the library settings this device kept before they
 * became library data.
 *
 * A new library's first seeding is part of opening it: without it the library has no fields. Every
 * other step is an [upgradeAtOpen].
 */
internal suspend fun foldJournal(
    deps: SessionDeps,
    coordinator: JournalCoordinator,
) {
    coordinator.refreshState()
    val seeds = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) }
    if (coordinator.setting(FieldKeys.SEEDED_SETTING) == null) {
        coordinator.seedFieldRegistryIfNeeded(seeds)
    } else {
        deps.upgradeAtOpen("Upgrading the seeded field definitions") { coordinator.seedFieldRegistryIfNeeded(seeds) }
    }
    deps.upgradeAtOpen("Restoring the seeded fields' per-document flag") {
        coordinator.restoreSeededPerDocument(seeds, projected = deps.db.fieldDefDao().defs())
    }
    deps.upgradeAtOpen("Adopting this device's library settings") {
        coordinator.adoptSettings(deps.settings.legacyLibrarySettings().journalSettings())
    }
}

/**
 * Bring the library's data up to this build at open, once per library, as best effort: a failure
 * (read-only or flaky storage) is logged and the next open tries again, rather than refusing this
 * one. Storage that cannot publish without replacement at all still refuses the library, as it
 * does wherever an open finds that out.
 */
private suspend fun SessionDeps.upgradeAtOpen(
    what: String,
    upgrade: suspend () -> Unit,
) {
    runCatchingCancellable { upgrade() }.onFailure { failure ->
        if (failure is VerifiedPublicationUnavailableException) throw failure
        log.warn("$what failed; trying again at the next open", failure)
    }
}
