package dev.npn.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.app.AiEditQueueFacade
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.EditRunReview
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.MaintenanceFacade
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.StoragePathFacade
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.WorkFacade
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// Test doubles for the session's own facets: ingest, OCR ops, review config, storage path,
// maintenance and work.
// Flows are public MutableStateFlows so tests can push emissions; calls are recorded in lists.

/** Imports and rescans. [FakeIngestFacade.importOutcomes] is consumed in order, repeating the last. */
class FakeIngestFacade : IngestFacade {
    val scanProgressFlow = MutableStateFlow<ScanProgressUi?>(null)
    val lastScanReportFlow = MutableStateFlow<ScanReport?>(null)

    override val scanProgress: Flow<ScanProgressUi?> get() = scanProgressFlow
    override val lastScanReport: Flow<ScanReport?> get() = lastScanReportFlow

    var rescanReport: ScanReport = emptyScanReport()
    var failRescan: (() -> Nothing)? = null

    /** Import outcomes, consumed in order per call; falls back to the last one when exhausted. */
    var importOutcomes: List<ImportOutcome> = listOf(ImportOutcome.Imported("x", "x.pdf"))
    private var importIdx = 0
    var failImport: (() -> Nothing)? = null

    override suspend fun rescan(): ScanReport {
        failRescan?.invoke()
        return rescanReport
    }

    private fun nextOutcome(): ImportOutcome {
        failImport?.invoke()
        val o = importOutcomes.getOrElse(importIdx) { importOutcomes.last() }
        importIdx++
        return o
    }

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()
}

/** Per-document OCR management. [ocrVersionsList] backs the version picker. */
class FakeOcrFacade : OcrFacade {
    val ocrStatusFlow = MutableStateFlow(OcrStatus(0, false))
    override val ocrStatus: Flow<OcrStatus> get() = ocrStatusFlow

    val reOcrIds = mutableListOf<String>()
    var failReOcr: (() -> Nothing)? = null

    /** Backing list returned by [ocrVersions]; tests mutate it to drive the picker. */
    var ocrVersionsList: List<OcrVersion> = emptyList()
    val ocrEngineSets = mutableListOf<Pair<String, String?>>()
    var failSetOcrEngine: (() -> Nothing)? = null

    /** What the "read text with assistant" action is offered as; drives whether it appears at all. */
    var aiOcr: AiOcrOffer = AiOcrOffer.Unavailable
    val aiOcrIds = mutableListOf<String>()
    var failAiOcr: (() -> Nothing)? = null

    override suspend fun reOcr(docId: String) {
        failReOcr?.invoke()
        reOcrIds += docId
    }

    /** If set, [aiOcrOffer] throws this. */
    var failAiOcrOffer: (() -> Nothing)? = null

    override suspend fun aiOcrOffer(docId: String): AiOcrOffer {
        failAiOcrOffer?.invoke()
        return aiOcr
    }

    /** What [ocrWithAi] answers once it has recorded the request. */
    var aiOcrOutcome: AiOcrOutcome = AiOcrOutcome.QUEUED

    override suspend fun ocrWithAi(docId: String): AiOcrOutcome {
        failAiOcr?.invoke()
        aiOcrIds += docId
        return aiOcrOutcome
    }

    /** If set, [ocrVersions] throws this. */
    var failOcrVersions: (() -> Nothing)? = null

    override suspend fun ocrVersions(docId: String): List<OcrVersion> {
        failOcrVersions?.invoke()
        return ocrVersionsList
    }

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        failSetOcrEngine?.invoke()
        ocrEngineSets += docId to engine
    }
}

/** Library-wide review workflow. */
class FakeReviewConfigFacade : ReviewConfigFacade {
    val reviewModeFlow = MutableStateFlow(ReviewMode.DEFAULT)
    override val reviewMode: Flow<ReviewMode> get() = reviewModeFlow

    val reviewModeSets = mutableListOf<ReviewMode>()
    val migrations = mutableListOf<Boolean>()

    /** If set, [setReviewMode] throws this. */
    var failSetReviewMode: (() -> Nothing)? = null

    override suspend fun setReviewMode(mode: ReviewMode) {
        failSetReviewMode?.invoke()
        reviewModeSets += mode
        reviewModeFlow.value = mode
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) {
        migrations += onlyTagged
    }
}

/** The library's storage-path rule. [templateWrites] records every template saved. */
class FakeStoragePathFacade : StoragePathFacade {
    val storagePathFlow = MutableStateFlow(StoragePathSetting())
    override val storagePath: Flow<StoragePathSetting> get() = storagePathFlow

    val templateWrites = mutableListOf<String?>()

    override suspend fun setStoragePathTemplate(template: String?) {
        templateWrites += template
        storagePathFlow.update { it.copy(template = template?.takeIf(String::isNotBlank)) }
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        storagePathFlow.update { it.copy(enabled = enabled) }
    }
}

/** Duplicate resolution, relinks and splitting. */
class FakeMaintenanceFacade : MaintenanceFacade {
    val resolvedDuplicates = mutableListOf<Pair<String, String>>()
    val relinks = mutableListOf<Pair<String, String>>()
    val splits = mutableListOf<Pair<String, List<IntRange>>>()
    var splitOutcome: SplitOutcome = SplitOutcome.Done(emptyList())

    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    ) {
        resolvedDuplicates += docId to extraRelPath
    }

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) {
        relinks += oldDocId to newDocId
    }

    override suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome {
        splits += docId to segments
        return splitOutcome
    }
}

/** Background queue drains; records how often each was asked to run, and serves a scripted queue. */
class FakeWorkFacade : WorkFacade {
    var ocrDrains = 0
    var enrichmentDrains = 0
    var editRunDrains = 0

    val queueEntries = MutableStateFlow<List<QueuedWork>>(emptyList())
    override val queue: Flow<List<QueuedWork>> get() = queueEntries

    val editRunEntries = MutableStateFlow<List<QueuedEditRun>>(emptyList())
    override val editRuns: Flow<List<QueuedEditRun>> get() = editRunEntries

    override suspend fun drainOcr() {
        ocrDrains++
    }

    override suspend fun drainEnrichment() {
        enrichmentDrains++
    }

    override suspend fun drainEditRuns() {
        editRunDrains++
    }
}

/**
 * Queued AI edit runs. Records what was queued and discarded, and serves whichever review a test
 * scripted for a job id.
 */
class FakeAiEditQueue : AiEditQueueFacade {
    val queued = mutableListOf<Pair<List<String>, AiEditRequest>>()
    val discarded = mutableListOf<String>()

    /** Job id handed back by the next [enqueueEditRun]. */
    var nextJobId = "job"

    /** Thrown by [enqueueEditRun] when set, to drive the failure path. */
    var enqueueFailure: Throwable? = null

    /** Reviews [openEditRun] serves, by job id; a missing id reads as a run that is gone. */
    val reviews = mutableMapOf<String, EditRunReview>()

    override suspend fun enqueueEditRun(
        docIds: List<String>,
        request: AiEditRequest,
    ): String {
        enqueueFailure?.let { throw it }
        queued += docIds to request
        return nextJobId
    }

    /** Thrown by [openEditRun] when set. */
    var openFailure: Throwable? = null

    override suspend fun openEditRun(jobId: String): EditRunReview? {
        openFailure?.let { throw it }
        return reviews[jobId]
    }

    override suspend fun discardEditRun(jobId: String) {
        discarded += jobId
    }
}

fun emptyScanReport(): ScanReport =
    ScanReport(
        added = 0,
        moved = 0,
        changed = 0,
        missingCount = 0,
        duplicates = emptyList(),
        conflictFiles = emptyList(),
        resurrected = emptyList(),
        relinkCandidates = emptyList(),
        hashedCount = 0,
        durationMillis = 0L,
    )
