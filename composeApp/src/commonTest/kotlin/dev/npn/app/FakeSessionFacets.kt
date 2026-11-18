package dev.npn.app

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.app.AiOcrOffer
import dev.npn.core.app.AiOcrOutcome
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.MaintenanceFacade
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.QueuedEditRun
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.RebuildOutcome
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.StoragePathFacade
import dev.npn.core.app.StoragePathSetting
import dev.npn.core.app.SyncFacade
import dev.npn.core.app.TrashRetentionFacade
import dev.npn.core.app.TrashSettings
import dev.npn.core.app.WorkFacade
import dev.npn.core.app.aiedit.AiEditQueueFacade
import dev.npn.core.app.aiedit.EditRunReview
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.IndexProgress
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.sync.CloudSetup
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.FolderContents
import dev.npn.core.sync.OAuthStatus
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupFailure
import dev.npn.core.sync.SetupStep
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

// Test doubles for the session's own facets: ingest, OCR ops, review config, storage path,
// maintenance and work.
// Flows are public MutableStateFlows so tests can push emissions; calls are recorded in lists.

/** Imports and rescans. [FakeIngestFacade.importOutcomes] is consumed in order, repeating the last. */
class FakeIngestFacade : IngestFacade {
    val scanProgressFlow = MutableStateFlow<IndexProgress?>(null)
    val lastScanReportFlow = MutableStateFlow<ScanReport?>(null)

    override val scanProgress: Flow<IndexProgress?> get() = scanProgressFlow
    override val lastScanReport: Flow<ScanReport?> get() = lastScanReportFlow

    var rescanReport: ScanReport = emptyScanReport()
    var failRescan: (() -> Nothing)? = null

    /** How many rescans were asked for, failed ones included. */
    var rescans = 0
        private set

    /** Import outcomes, consumed in order per call; falls back to the last one when exhausted. */
    var importOutcomes: List<ImportOutcome> = listOf(ImportOutcome.Imported("x", "x.pdf"))
    private var importIdx = 0
    var failImport: (() -> Nothing)? = null

    override suspend fun rescan(): ScanReport {
        rescans++
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

    override suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun holdsDocument(docId: String): Boolean = true
}

/** Per-document OCR management. [ocrVersionsList] backs the version picker. */
class FakeOcrFacade : OcrFacade {
    val ocrStatusFlow = MutableStateFlow(OcrStatus(0, false))
    override val ocrStatus: Flow<OcrStatus> get() = ocrStatusFlow

    val reOcrIds = mutableListOf<String>()
    var failReOcr: (() -> Nothing)? = null

    /** Backing list returned by [ocrVersions]; tests mutate it to drive the picker. */
    var ocrVersionsList: List<OcrVersion> = emptyList()
    val ocrEngineSets = mutableListOf<Pair<String, OcrEngineRef?>>()
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
        engine: OcrEngineRef?,
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

    /** If set, [setStoragePathTemplate] throws this. */
    var failTemplate: (() -> Nothing)? = null

    override suspend fun setStoragePathTemplate(template: String?) {
        failTemplate?.invoke()
        templateWrites += template
        storagePathFlow.update { it.copy(template = template?.takeIf(String::isNotBlank)) }
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        storagePathFlow.update { it.copy(enabled = enabled) }
    }
}

/** The library's trash retention. [writes] records every retention saved. */
class FakeTrashRetention : TrashRetentionFacade {
    val days = MutableStateFlow(TrashSettings.DEFAULT_TRASH_RETENTION_DAYS)
    override val trashRetentionDays: Flow<Int> get() = days

    val writes = mutableListOf<Int>()

    /** If set, [setTrashRetentionDays] throws this. */
    var fail: (() -> Nothing)? = null

    override suspend fun setTrashRetentionDays(days: Int) {
        fail?.invoke()
        writes += days
        this.days.value = days
    }
}

/** Duplicate resolution, relinks and splitting. */
class FakeMaintenanceFacade : MaintenanceFacade {
    val resolvedDuplicates = mutableListOf<Pair<String, String>>()
    val relinks = mutableListOf<Pair<String, String>>()
    val splits = mutableListOf<Pair<String, List<IntRange>>>()
    var splitOutcome: SplitOutcome = SplitOutcome.Done(emptyList())
    var rebuilds = 0
    var rebuildOutcome: RebuildOutcome = RebuildOutcome.Rebuilt
    var failRebuild: (() -> Nothing)? = null

    override suspend fun rebuildIndex(): RebuildOutcome {
        failRebuild?.invoke()
        rebuilds++
        return rebuildOutcome
    }

    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
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

    /** Thrown by [discardEditRun] when set. */
    var discardFailure: Throwable? = null

    override suspend fun discardEditRun(jobId: String) {
        discardFailure?.let { throw it }
        discarded += jobId
    }
}

/**
 * Cloud sync. Records how often a pass was asked for and serves a scripted outcome and availability.
 * The failure hooks drive the viewmodel's error paths; [availabilityGate] holds a probe open so a
 * test can race two of them.
 */
class FakeSyncFacade : SyncFacade {
    val statusFlow = MutableStateFlow(SyncStatus())
    override val status: Flow<SyncStatus> get() = statusFlow

    /** This library's settings on the device; [configure] writes them as the service does, normalised. */
    val configFlow = MutableStateFlow(CloudSyncConfig.DISABLED)
    override val config: Flow<CloudSyncConfig> get() = configFlow

    /** Thrown by [configure] when set. */
    var configureFailure: Throwable? = null

    override suspend fun configure(change: (CloudSyncConfig) -> CloudSyncConfig) {
        configureFailure?.let { throw it }
        val next = change(configFlow.value)
        configFlow.value = next.copy(enabled = next.enabled && !next.remote.isNullOrBlank())
    }

    var manualSyncs = 0
    var scheduledSyncs = 0
    var probes = 0
    var outcome: SyncOutcome = SyncOutcome.NotConfigured
    var availability: TransportAvailability = TransportAvailability.NotInstalled("rclone")

    /** When set, [transportAvailability] waits here before answering with the [availability] of that moment. */
    var availabilityGate: CompletableDeferred<Unit>? = null

    /** Thrown by [syncNow] when set: the facade is not supposed to throw, which a test wants to see survive. */
    var syncNowFailure: Throwable? = null

    override suspend fun syncNow(): SyncOutcome {
        manualSyncs++
        syncNowFailure?.let { throw it }
        return outcome
    }

    override suspend fun syncScheduled(): SyncOutcome {
        scheduledSyncs++
        return outcome
    }

    override suspend fun transportAvailability(): TransportAvailability {
        probes++
        availabilityGate?.await()
        return availability
    }

    /** In-app setup, when a test wires one. */
    override var setup: CloudSetup? = null
}

/**
 * In-app cloud setup. Scripts each protocol step and records the calls, so a view-model test can
 * drive the wizard without an engine. [answerGate] holds the (normally blocking) sign-in answer so
 * a test can observe the status poll that runs while it is in flight.
 */
class FakeCloudSetup : CloudSetup {
    var providersResult: List<RcloneProvider> = emptyList()
    var providersFailure: Throwable? = null
    var providersCalls = 0

    data class BeginCall(
        val name: String,
        val type: String,
        val params: Map<String, String>,
    )

    data class AnswerCall(
        val name: String,
        val state: String,
        val result: String,
    )

    data class FolderCall(
        val remote: String,
        val path: String,
    )

    val begins = mutableListOf<BeginCall>()
    val answers = mutableListOf<AnswerCall>()
    val folderLists = mutableListOf<FolderCall>()
    val madeFolders = mutableListOf<FolderCall>()

    /** What [begin] answers; the default is a failure so a test cannot silently pass through. */
    var beginResult: (BeginCall) -> SetupStep = { SetupStep.Failed(SetupFailure.ENGINE, "no begin scripted") }

    /** What [answer] answers. */
    var answerResult: (AnswerCall) -> SetupStep = { SetupStep.Failed(SetupFailure.ENGINE, "no answer scripted") }

    /** When set, [answer] waits here before returning, simulating the blocking sign-in. */
    var answerGate: CompletableDeferred<Unit>? = null

    var oauthStatusResult: OAuthStatus? = null
    var oauthStatusCalls = 0
    var stopOauthCalls = 0

    var foldersResult: (FolderCall) -> List<String> = { emptyList() }
    var foldersFailure: Throwable? = null
    var makeFolderFailure: Throwable? = null

    /** What [folderContents] answers for a folder. */
    var contentsResult: (FolderCall) -> FolderContents = { FolderContents.EMPTY }
    val deletedRemotes = mutableListOf<String>()

    override suspend fun providers(): List<RcloneProvider> {
        providersCalls++
        providersFailure?.let { throw it }
        return providersResult
    }

    override suspend fun begin(
        name: String,
        type: String,
        params: Map<String, String>,
    ): SetupStep {
        val call = BeginCall(name, type, params)
        begins += call
        return beginResult(call)
    }

    override suspend fun answer(
        name: String,
        state: String,
        result: String,
    ): SetupStep {
        val call = AnswerCall(name, state, result)
        answers += call
        answerGate?.await()
        return answerResult(call)
    }

    override suspend fun oauthStatus(): OAuthStatus? {
        oauthStatusCalls++
        return oauthStatusResult
    }

    override suspend fun stopOauth() {
        stopOauthCalls++
    }

    override suspend fun folders(
        remote: String,
        path: String,
    ): List<String> {
        val call = FolderCall(remote, path)
        folderLists += call
        foldersFailure?.let { throw it }
        return foldersResult(call)
    }

    override suspend fun makeFolder(
        remote: String,
        path: String,
    ) {
        madeFolders += FolderCall(remote, path)
        makeFolderFailure?.let { throw it }
    }

    override suspend fun folderContents(
        remote: String,
        path: String,
    ): FolderContents = contentsResult(FolderCall(remote, path))

    override suspend fun deleteRemote(name: String) {
        deletedRemotes += name
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
        relinkCandidates = emptyList(),
        hashedCount = 0,
        durationMillis = 0L,
    )
