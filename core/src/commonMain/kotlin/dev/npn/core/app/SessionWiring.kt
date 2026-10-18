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
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.index.MaintenanceService
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
import dev.npn.core.work.DrainedQueue
import dev.npn.core.work.QueueDrainer
import dev.npn.core.work.drainedQueue
import dev.npn.core.xmp.BakeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
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

/** The library's facets. [relinkBake] ends a bake in place ([BakeRelink.relink]). */
internal suspend fun buildLibrary(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    relinkBake: suspend (original: String, baked: String) -> Unit,
): Library {
    val db = deps.db
    val storagePath = JournalStoragePath(coordinator, lifetime)
    val pathRules =
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
    val savedViews = SavedViewRepositoryImpl(coordinator, lifetime)
    deps.upgradeAtOpen("Moving saved views into the journal") { savedViews.adoptIndexViews(db) }
    val ruleSource = AutoMatchRuleSource(db.autoMatchRuleDao(), coordinator, deps.log)
    val ruleApplier =
        RuleApplier(
            rules = ruleSource::current,
            ftsDao = db.ftsDao(),
            documentDao = db.documentDao(),
            coordinator = coordinator,
            onMetadataApplied = pathRules::applyFor,
        )
    ruleSource.mirror()
    val bakes = BakeLock()
    val writer =
        MetadataWriterImpl(
            coordinator,
            db,
            pathRules,
            deps.log,
            applyAutoMatch = { ruleApplier.applyToDoc(it, organize = false) },
        )
    val lifecycle =
        DocLifecycleImpl(
            coordinator,
            db,
            deps.fs,
            TrashOps(deps.fs),
            deps.log,
            bakes,
            clock = deps.clock,
            followUp = writer::followUpRelocation,
        )
    return Library(
        documents = DocumentRepositoryImpl(db),
        tags = TagRepositoryImpl(db.tagDao()),
        savedViews = savedViews,
        fieldRegistry = FieldRegistryImpl(db, coordinator, lifetime),
        storagePath = storagePath,
        rules = AutoMatchRuleRepositoryImpl(db.autoMatchRuleDao(), ruleSource, ruleApplier, coordinator, lifetime),
        ruleSource = ruleSource,
        ruleApplier = ruleApplier,
        ownedWriter = OwnedWriter(writer, lifecycle, lifetime),
        reviewConfig = JournalReviewConfig(coordinator, db.documentDao(), lifetime),
        content = buildContent(deps, lifetime, coordinator, relinkBake, bakes),
        imports =
            ImportService(
                fs = deps.fs,
                db = db,
                ocrQueueDao = deps.deviceDb.ocrQueueDao(),
                clock = deps.clock,
                ruleProvider = storagePath::rule,
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

/**
 * Automatic AI enrichment: unattended, so it writes through [Library.writer] without review. Its
 * drain waits for [rescanned], as every queue drain does ([LibrarySessionImpl.openSession]).
 */
internal fun buildEnrichment(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    library: Library,
    ai: AiFacadeImpl,
    rescanned: Deferred<Unit>,
): DrainedQueue<AiAutoEnricher> =
    drainedQueue(lifetime.scope, "AI enrichment queue", deps.log, build = { onEnqueued ->
        AiAutoEnricher(
            documentDao = deps.db.documentDao(),
            ftsDao = deps.db.ftsDao(),
            queueDao = deps.deviceDb.aiEnrichQueueDao(),
            ai = ai,
            meta = library.writer,
            fieldRegistry = library.fieldRegistry,
            policy = UnattendedAiPolicy(deps.settings.aiConfig),
            clock = deps.clock,
            health = ai.endpointHealth,
            canWrite = coordinator::canWrite,
            onEnqueued = onEnqueued,
        )
    }) {
        rescanned.await()
        it.processNext()
    }

/**
 * Bulk AI edits handed to the background; their writes wait for a human review, which a caller
 * applies, so they go through [Library.ownedWriter]. Drained once [rescanned].
 */
internal fun buildEditQueue(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    library: Library,
    ai: AiFacadeImpl,
    rescanned: Deferred<Unit>,
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
    }) {
        rescanned.await()
        it.processNext()
    }

/**
 * This device's OCR pipeline and queue, drained once [rescanned]. Indexed text runs the library's
 * auto-match rules first; [onOcrProduced] then fires only for text this device just produced, so
 * rule-applied tags and fields are part of what a paid follow-up sees.
 */
internal fun buildOcr(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    deviceId: String,
    library: Library,
    ai: AiFacadeImpl,
    rescanned: Deferred<Unit>,
    onOcrProduced: suspend (docId: String) -> Unit,
): OcrFacadeImpl {
    val staleFiles = Channel<Unit>(Channel.CONFLATED)
    val engines =
        OcrEngineSelector(
            local = deps.ocrEngine,
            ai = ai.ocrEngine,
            aiAuto = UnattendedAiPolicy(deps.settings.aiConfig)::autoOcr,
        )
    val pipeline =
        OcrPipeline(
            fs = deps.fs,
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
            onStaleFile = { staleFiles.trySend(Unit) },
        )
    return OcrFacadeImpl(
        lifetime = lifetime,
        documentDao = deps.db.documentDao(),
        queue = deps.deviceDb.ocrQueueDao(),
        pipeline = pipeline,
        engines = engines,
        writer = library.writer,
        content = library.content,
        worker =
            QueueDrainer(lifetime.scope, "OCR queue", deps.log) {
                rescanned.await()
                pipeline.processNext()
            },
        clock = deps.clock,
        staleFiles = staleFiles,
    )
}

internal fun buildMaintenance(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    coordinator: JournalCoordinator,
    library: Library,
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
                serialize = { step -> coordinator.serialized { step() } },
            ),
        coordinator = coordinator,
        splitter = SplitService(deps.fs, deps.pdfEngine, library.imports, deps.db.documentDao(), deps.log),
        writer = library.writer,
        lifecycle = library.lifecycle,
        scanReports = scanReports,
        log = deps.log,
    )

/** The three drained queues behind [LibrarySession.work], and the [rescanned] gate their drains wait at. */
internal fun buildWork(
    deps: SessionDeps,
    lifetime: SessionLifetime,
    ocr: OcrFacadeImpl,
    enrichment: DrainedQueue<AiAutoEnricher>,
    edits: DrainedQueue<AiEditQueue>,
    rescanned: CompletableDeferred<Unit>,
): SessionWork =
    SessionWork(
        lifetime = lifetime,
        ocr = ocr.worker,
        enrichment = enrichment.worker,
        edits = edits.worker,
        firstRescan = rescanned,
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
