package dev.npn.core.app

import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.fs.FsSegmentStoreFiles
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.OcrSidecarFiles
import dev.npn.core.fs.TrashOps
import dev.npn.core.index.DbFoldedSegmentTracker
import dev.npn.core.index.DbLocalJournalState
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.index.FsSegmentStore
import dev.npn.core.index.ImportResult
import dev.npn.core.index.ImportService
import dev.npn.core.index.Indexer
import dev.npn.core.index.MaintenanceService
import dev.npn.core.index.RelinkService
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.HlcClock
import dev.npn.core.journal.JournalIndex
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.ocr.OcrConfig
import dev.npn.core.ocr.OcrEngine
import dev.npn.core.ocr.OcrPipeline
import dev.npn.core.ocr.OcrWorker
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.pdf.ThumbnailService
import dev.npn.core.pdf.ThumbnailStore
import dev.npn.core.xmp.BakeService
import dev.npn.core.xmp.PdfXmpIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Everything wired for one opened library folder. Constructed by [openSession] (a suspend factory
 * that runs the device-identity + fork check and initial fold), then [start]ed with a scope.
 */
class LibrarySessionImpl private constructor(
    override val deviceId: String,
    private val deps: SessionDeps,
    private val coordinator: JournalCoordinator,
    private val indexer: Indexer,
    private val importService: ImportService,
    private val maintenance: MaintenanceService,
    private val relinkService: RelinkService,
    private val ocrPipeline: OcrPipeline,
    private val ocrWorker: OcrWorker,
    override val documents: DocumentRepository,
    override val tags: TagRepository,
    override val savedViews: SavedViewRepository,
    override val meta: MetadataWriter,
    override val content: DocContentFacade,
) : LibrarySession {

    private val fs get() = deps.fs
    private val db get() = deps.db

    private val _lastScanReport = MutableStateFlow<ScanReport?>(null)
    override val lastScanReport: Flow<ScanReport?> = _lastScanReport.asStateFlow()

    override val scanProgress: Flow<ScanProgressUi?> =
        indexer.scanState.map { p ->
            p?.let { ScanProgressUi(it.phase.name, it.hashed, it.total) }
        }

    override val ocrStatus: Flow<OcrStatus> =
        db.ocrQueueDao().countActive().map { active -> OcrStatus(pending = active, running = active > 0) }

    private var jobs: MutableList<Job> = ArrayList()
    private var started = false

    override suspend fun rescan(): ScanReport {
        val report = indexer.rescan()
        _lastScanReport.value = report
        coordinator.refreshState()
        // Adopt any foreign OCR sidecars picked up by the rescan, then queue/kick + purge trash.
        ocrPipeline.adoptForeignSidecars()
        queuePendingOcr()
        return report
    }

    override suspend fun importPdf(source: ExternalSource, suggestedName: String): ImportOutcome =
        map(importService.importPdf(source, suggestedName)).also { afterImport(it) }

    override suspend fun importPdfBytes(bytes: ByteArray, suggestedName: String): ImportOutcome =
        map(importService.importBytes(bytes, suggestedName)).also { afterImport(it) }

    override suspend fun importImagesAsPdf(images: List<ByteArray>, suggestedName: String): ImportOutcome {
        return try {
            val pdf = deps.pdfEngine.imagesToPdf(images)
            importPdfBytes(pdf, suggestedName)
        } catch (t: Throwable) {
            ImportOutcome.Failed(t.message ?: "image conversion failed")
        }
    }

    override suspend fun resolveDuplicate(docId: String, extraRelPath: String, keepCanonical: Boolean) {
        maintenance.resolveDuplicate(docId, extraRelPath, keepCanonical)
    }

    override suspend fun applyRelink(oldDocId: String, newDocId: String) {
        val report = relinkService.applyRelink(oldDocId, newDocId)
        _lastScanReport.value = report
        coordinator.refreshState()
    }

    override suspend fun purgeTrash(olderThanDays: Int) {
        val cutoff = deps.clock.nowMillis() - olderThanDays.toLong() * 24L * 60L * 60L * 1000L
        trashOps().purgeOlderThan(cutoff)
    }

    override suspend fun reOcr(docId: String) {
        val doc = db.documentDao().getByDocId(docId) ?: return
        db.ocrQueueDao().upsert(
            OcrQueueEntity(
                docId = docId,
                state = OcrPipeline.STATE_PENDING,
                updatedAt = deps.clock.nowMillis(),
            ),
        )
        db.documentDao().upsert(doc.copy(ocrState = OcrPipeline.OCR_PENDING))
        ocrWorker.kick()
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

        // Desktop file watching -> debounced rescan. On SAF (android) watch() is empty, so this is
        // a no-op there and indexing stays rescan-driven (pull-to-refresh / periodic job).
        jobs.add(
            scope.launch {
                fs.watch().collect {
                    runCatching { doWatchTriggeredRescan() }
                }
            },
        )

        // Initial rescan.
        jobs.add(scope.launch { runCatching { doWatchTriggeredRescan() } })
    }

    private suspend fun doWatchTriggeredRescan() {
        rescan()
        ocrWorker.kick()
        // Respect the configured retention at call time.
        val days = runCatching { deps.trashRetentionDays() }.getOrDefault(30)
        runCatching { purgeTrash(days) }
    }

    override fun close() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        started = false
        // DB is closed by the environment when swapping libraries (it owns lifecycle). Leaving it
        // open here is documented: closing on session.close() would race the swap. See
        // AppEnvironment impls.
    }

    // --- helpers ---

    private fun trashOps() = TrashOps(fs)

    private fun map(result: ImportResult): ImportOutcome = when (result) {
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
                db.ocrQueueDao().upsert(
                    OcrQueueEntity(
                        docId = doc.docId,
                        state = OcrPipeline.STATE_PENDING,
                        updatedAt = deps.clock.nowMillis(),
                    ),
                )
                db.documentDao().upsert(doc.copy(ocrState = OcrPipeline.OCR_PENDING))
            }
        }
    }

    companion object {
        /**
         * Boot a session over [deps]: get-or-create the deviceId, run the single-writer fork check
         * (rebuilding the writer under a fresh id if a fork is detected), wire the journal engine,
         * repositories, pipelines, then do the initial fold so [documents] is populated before the
         * caller's initial rescan.
         */
        suspend fun openSession(deps: SessionDeps): LibrarySessionImpl {
            val db = deps.db
            val fs = deps.fs

            val segmentStore = FsSegmentStore(FsSegmentStoreFiles(fs))
            val journalIndex = JournalIndex(segmentStore)
            val foldedTracker = DbFoldedSegmentTracker(db.foldedSegmentDao())
            val indexer = Indexer(fs, db, journalIndex, foldedTracker, deps.clock)

            val identity = DeviceIdentity(db.keyValueDao())
            val initialDeviceId = identity.getOrCreate()
            val localState = DbLocalJournalState(db.keyValueDao())

            // Build a provisional writer to run the fork check.
            val provisional = SegmentWriter(
                store = segmentStore,
                deviceId = initialDeviceId,
                hlcClock = HlcClock(initialDeviceId, deps.clock),
                localState = localState,
            )
            val effectiveDeviceId = identity.ensureSingleWriter(provisional)
            val writer = if (effectiveDeviceId == initialDeviceId) {
                provisional
            } else {
                SegmentWriter(
                    store = segmentStore,
                    deviceId = effectiveDeviceId,
                    hlcClock = HlcClock(effectiveDeviceId, deps.clock),
                    localState = localState,
                )
            }

            val coordinator = JournalCoordinator(writer, journalIndex, indexer)
            coordinator.refreshState()

            val importService = ImportService(fs, db.documentDao(), db.ocrQueueDao(), deps.clock)
            val maintenance = MaintenanceService(fs, db.documentDao(), db.duplicateDao(), importService)
            val relinkService = RelinkService(writer, indexer)

            val ocrPipeline = OcrPipeline(
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
            )
            val ocrWorker = OcrWorker(ocrPipeline, deps.scope)

            val thumbnailService = ThumbnailService(
                engine = deps.pdfEngine,
                fs = fs,
                store = deps.thumbnailStore,
                thumbnailDao = db.thumbnailDao(),
                documentDao = db.documentDao(),
            )
            val bakeService = BakeService(fs, deps.xmpIO)

            val documents = DocumentRepositoryImpl(db)
            val tags = TagRepositoryImpl(db.tagDao())
            val savedViews = SavedViewRepositoryImpl(db.savedViewDao())
            val meta = MetadataWriterImpl(coordinator, db, fs, TrashOps(fs))
            val content = DocContentFacadeImpl(db, fs, deps.pdfEngine, thumbnailService, bakeService, coordinator)

            return LibrarySessionImpl(
                deviceId = effectiveDeviceId,
                deps = deps,
                coordinator = coordinator,
                indexer = indexer,
                importService = importService,
                maintenance = maintenance,
                relinkService = relinkService,
                ocrPipeline = ocrPipeline,
                ocrWorker = ocrWorker,
                documents = documents,
                tags = tags,
                savedViews = savedViews,
                meta = meta,
                content = content,
            )
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
    val db: NpnDatabase,
    val pdfEngine: PdfEngine,
    val ocrEngine: OcrEngine,
    val xmpIO: PdfXmpIO,
    val thumbnailStore: ThumbnailStore,
    val scope: CoroutineScope,
    val ocrLanguage: suspend () -> String,
    val trashRetentionDays: suspend () -> Int,
    val clock: EpochClock = SystemEpochClock,
) {
    /**
     * Close the underlying index database. Exposed here so the composeApp DI layer (which does not
     * depend on the androidx.room3 runtime directly) can free the cache on a library swap without
     * touching [NpnDatabase]'s room supertype.
     */
    fun closeDb() = db.close()
}
