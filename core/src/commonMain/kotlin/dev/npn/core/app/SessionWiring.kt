package dev.npn.core.app

import dev.npn.core.ai.AiFacadeImpl
import dev.npn.core.ai.RoomChatHistoryProvider
import dev.npn.core.ai.UnattendedAiPolicy
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.fieldTypes
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.fs.TrashOps
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.MaintenanceService
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.RelinkService
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
import dev.npn.core.work.DrainedQueue
import dev.npn.core.work.QueueDrainer
import dev.npn.core.work.drainedQueue
import dev.npn.core.xmp.BakeService
import kotlinx.coroutines.flow.MutableStateFlow

// The builders [LibrarySessionImpl.openSession] wires a session from, one per part, in the order it
// calls them. [Library] is what the first of them hands the rest.

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
 * (v1 segments are still read). A filesystem that does not report verified publication is refused
 * here rather than written in a weaker format.
 */
private suspend fun createObjectWriter(deps: SessionDeps): ContentAddressedJournalWriter {
    val state = deps.db.metadataProjectionDao().state()
    val writer =
        ContentAddressedJournalWriter(
            fs = deps.fs,
            installationId = deps.installationId,
            allocator =
                HlcWatermarkAllocator(
                    clock = deps.clock,
                    initialPhysicalMillis = state?.allocatorPhysical ?: 0L,
                    initialCounter = state?.allocatorCounter ?: 0,
                ),
        )
    if (!writer.supported) {
        throw VerifiedPublicationUnavailableException("This storage cannot publish journal objects safely")
    }
    return writer
}

internal suspend fun createCoordinator(
    deps: SessionDeps,
    indexer: Indexer,
): JournalCoordinator {
    val snapshots = SnapshotSupport(deps.fs, deps.db.metadataProjectionDao())
    return JournalCoordinator(
        writer = createObjectWriter(deps),
        indexer = indexer,
        accumulator = MetadataAccumulator(deps.fs, deps.db, deps.installationId, deps.clock, snapshots),
        compactor = SnapshotCompactor(deps.fs, deps.installationId),
        snapshots = snapshots,
        clock = deps.clock,
        log = deps.log,
    )
}

/**
 * Fold the journal, so the documents are there before the caller's initial rescan, and bring the
 * library's own data up to what this build expects: the seeded fields, and the library settings
 * this device kept before they became library data.
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

/** The library's facets. [relinkBake] ends a bake in place ([BakeRelink.relink]). */
internal suspend fun buildLibrary(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    relinkBake: suspend (original: String, baked: String) -> Unit,
): Library {
    val db = deps.db
    val storagePath = JournalStoragePath(coordinator) { lifetime.owned(it) }
    val pathRules =
        StoragePathRuleApplicator(deps.fs, db, storagePath::rule) { docId, path ->
            coordinator.commitAndApply(listOf(PendingOp.propSet(docId, SystemProperty.STORAGE_PATH, path)))
        }
    val savedViews = SavedViewRepositoryImpl(coordinator) { lifetime.owned(it) }
    deps.upgradeAtOpen("Moving saved views into the journal") { savedViews.adoptIndexViews(db) }
    lateinit var rules: AutoMatchRuleRepositoryImpl
    val ruleApplier =
        RuleApplier(
            rules = { rules.current() },
            ftsDao = db.ftsDao(),
            documentDao = db.documentDao(),
            coordinator = coordinator,
            onMetadataApplied = pathRules::applyFor,
        )
    rules =
        AutoMatchRuleRepositoryImpl(db.autoMatchRuleDao(), ruleApplier, coordinator, deps.log) {
            lifetime.owned(it)
        }
    rules.mirror()
    val bakes = BakeLock()
    val writer =
        MetadataWriterImpl(
            coordinator,
            db,
            deps.fs,
            TrashOps(deps.fs),
            pathRules,
            deps.log,
            bakes,
            applyAutoMatch = { ruleApplier.applyToDoc(it, organize = false) },
        )
    return Library(
        documents = DocumentRepositoryImpl(db),
        tags = TagRepositoryImpl(db.tagDao()),
        savedViews = savedViews,
        fieldRegistry = FieldRegistryImpl(db, coordinator) { lifetime.owned(it) },
        storagePath = storagePath,
        rules = rules,
        ruleApplier = ruleApplier,
        writer = writer,
        ownedWriter = OwnedWriter(writer, lifetime),
        reviewConfig = JournalReviewConfig(coordinator, db.documentDao()) { lifetime.owned(it) },
        content = buildContent(deps, lifetime, coordinator, relinkBake, bakes),
        imports =
            ImportService(
                fs = deps.fs,
                db = db,
                ocrQueueDao = deps.deviceDb.ocrQueueDao(),
                clock = deps.clock,
                ruleProvider = storagePath::rule,
                fieldTypes = { db.fieldDefDao().fieldTypes() },
                serialize = { coordinator.serialized(it) },
                log = deps.log,
            ),
    )
}

private fun buildContent(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    relinkBake: suspend (original: String, baked: String) -> Unit,
    bakes: BakeLock,
): DocContentFacadeImpl {
    // Single shared gate so thumbnail + page rendering share one bounded concurrency budget and a
    // first-load burst (e.g. 200 PDFs) cannot flood the dispatchers.
    val renderGate = RenderGate(deps.renderParallelism)
    val thumbnails =
        ThumbnailService(
            engine = deps.pdfEngine,
            fs = deps.fs,
            store = deps.thumbnailStore,
            thumbnailDao = deps.db.thumbnailDao(),
            documentDao = deps.db.documentDao(),
            renderGate = renderGate,
            clock = deps.clock,
        )
    return DocContentFacadeImpl(
        lifetime,
        deps.db,
        deps.fs,
        deps.pdfEngine,
        thumbnails,
        BakeService(deps.fs, deps.xmpIO, deps.log),
        coordinator,
        relinkBake,
        renderGate,
        bakes,
        deps.log,
    )
}

/** Automatic AI enrichment: unattended, so it writes through [Library.writer] without review. */
internal fun buildEnrichment(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    library: Library,
    ai: AiFacadeImpl,
): DrainedQueue<AiAutoEnricher> =
    drainedQueue(lifetime.scope, "AI enrichment queue", deps.log, build = { onEnqueued ->
        AiAutoEnricher(
            documentDao = deps.db.documentDao(),
            queueDao = deps.deviceDb.aiEnrichQueueDao(),
            ai = ai,
            meta = library.writer,
            fieldRegistry = library.fieldRegistry,
            policy = UnattendedAiPolicy(deps.settings.aiConfig),
            clock = deps.clock,
            health = ai.endpointHealth,
            checkWritable = coordinator::checkWritable,
            onEnqueued = onEnqueued,
        )
    }) { it.processNext() }

/**
 * Bulk AI edits handed to the background; their writes wait for a human review, which a caller
 * applies, so they go through [Library.ownedWriter].
 */
internal fun buildEditQueue(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    library: Library,
    ai: AiFacadeImpl,
): DrainedQueue<AiEditQueue> =
    drainedQueue(lifetime.scope, "AI edit queue", deps.log, build = { onEnqueued ->
        AiEditQueue(
            dao = deps.deviceDb.aiEditJobDao(),
            documents = library.documents,
            content = library.content,
            meta = library.ownedWriter,
            ai = ai,
            fieldRegistry = library.fieldRegistry,
            tags = library.tags,
            readConfig = deps.settings.aiConfig,
            clock = deps.clock,
            health = ai.endpointHealth,
            onEnqueued = onEnqueued,
            ownWrite = { lifetime.owned(it) },
        )
    }) { it.processNext() }

/**
 * This device's OCR pipeline and queue. Indexed text runs the library's auto-match rules first;
 * [onOcrProduced] then fires only for text this device just produced, so rule-applied tags and
 * fields are part of what a paid follow-up sees.
 */
internal fun buildOcr(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    deviceId: String,
    library: Library,
    ai: AiFacadeImpl,
    onOcrProduced: suspend (docId: String) -> Unit,
    onStaleFile: suspend (docId: String) -> Unit,
): OcrFacadeImpl {
    val engines =
        OcrEngineSelector(
            local = deps.ocrEngine,
            ai = ai.ocrEngine,
            aiAuto = UnattendedAiPolicy(deps.settings.aiConfig)::autoOcr,
        )
    val pipeline =
        OcrPipeline(
            fs = deps.fs,
            sidecars = OcrSidecarFiles(deps.fs),
            pdfEngine = deps.pdfEngine,
            engines = engines,
            documentDao = deps.db.documentDao(),
            ftsDao = deps.db.ftsDao(),
            ocrQueueDao = deps.deviceDb.ocrQueueDao(),
            tagDao = deps.db.tagDao(),
            fieldDao = deps.db.documentFieldDao(),
            deviceId = deviceId,
            language = deps.settings.ocrLanguage,
            clock = deps.clock,
            // Deterministic local rules run on any newly indexed text, including a re-index.
            onTextIndexed = { docId, text -> library.ruleApplier.applyToDoc(docId, text) },
            onOcrProduced = onOcrProduced,
            onStaleFile = onStaleFile,
        )
    return OcrFacadeImpl(
        lifetime = lifetime,
        documentDao = deps.db.documentDao(),
        queue = deps.deviceDb.ocrQueueDao(),
        pipeline = pipeline,
        engines = engines,
        writer = library.writer,
        content = library.content,
        worker = QueueDrainer(lifetime.scope, "OCR queue", deps.log) { pipeline.processNext() },
        clock = deps.clock,
    )
}

internal fun buildMaintenance(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    library: Library,
    relink: RelinkService,
    ocr: OcrFacadeImpl,
    scanReports: MutableStateFlow<ScanReport?>,
): MaintenanceFacadeImpl =
    MaintenanceFacadeImpl(
        lifetime = lifetime,
        db = deps.db,
        maintenance =
            MaintenanceService(
                fs = deps.fs,
                documentDao = deps.db.documentDao(),
                duplicateDao = deps.db.duplicateDao(),
                importService = library.imports,
                serialize = { step -> coordinator.serialized { step() } },
            ),
        relink = relink,
        splitter = SplitService(deps.fs, deps.pdfEngine, library.imports, deps.db.documentDao(), deps.log),
        writer = library.writer,
        ocr = ocr,
        scanReports = scanReports,
        log = deps.log,
    )

/** The three drained queues behind [LibrarySession.work]. */
internal fun buildWork(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    ocr: OcrFacadeImpl,
    enrichment: QueueDrainer,
    edits: DrainedQueue<AiEditQueue>,
): SessionWork =
    SessionWork(
        lifetime = lifetime,
        ocr = ocr.worker,
        enrichment = enrichment,
        edits = edits.worker,
        reader =
            WorkQueueReader(
                deps.db.documentDao(),
                deps.deviceDb.ocrQueueDao(),
                deps.deviceDb.aiEnrichQueueDao(),
                deps.deviceDb.aiEditJobDao(),
                ocr.inFlight,
                edits.queue.inFlight,
                deps.db.invalidationTracker.createFlow("documents"),
            ),
    )
