package dev.npn.app

import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.MaintenanceFacade
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SplitOutcome
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Test doubles for the session's own facets: ingest, OCR ops, review config and maintenance.
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

    override suspend fun reOcr(docId: String) {
        failReOcr?.invoke()
        reOcrIds += docId
    }

    override suspend fun ocrVersions(docId: String): List<OcrVersion> = ocrVersionsList

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        failSetOcrEngine?.invoke()
        ocrEngineSets += docId to engine
    }
}

/** Library-wide review workflow and standard-field visibility. */
class FakeReviewConfigFacade : ReviewConfigFacade {
    val reviewModeFlow = MutableStateFlow(ReviewMode.DEFAULT)
    override val reviewMode: Flow<ReviewMode> get() = reviewModeFlow

    val standardFieldsFlow = MutableStateFlow(StandardField.DEFAULT)
    override val standardFields: Flow<Set<StandardField>> get() = standardFieldsFlow

    val reviewModeSets = mutableListOf<ReviewMode>()
    val standardFieldsSets = mutableListOf<Set<StandardField>>()
    val migrations = mutableListOf<Boolean>()

    override suspend fun setReviewMode(mode: ReviewMode) {
        reviewModeSets += mode
        reviewModeFlow.value = mode
    }

    override suspend fun setStandardFields(fields: Set<StandardField>) {
        standardFieldsSets += fields
        standardFieldsFlow.value = fields
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) {
        migrations += onlyTagged
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
