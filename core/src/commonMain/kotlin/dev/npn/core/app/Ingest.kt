package dev.npn.core.app

import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import kotlinx.coroutines.flow.Flow

/** Getting documents into the library: imports, rescans, and their progress/reporting. */
interface IngestFacade {
    val scanProgress: Flow<ScanProgressUi?> // null when idle

    val lastScanReport: Flow<ScanReport?>

    suspend fun rescan(): ScanReport

    suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome

    suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome

    suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome
}

data class ScanProgressUi(
    val phase: String,
    val done: Int,
    val total: Int,
)

sealed interface ImportOutcome {
    data class Imported(
        val docId: String,
        val relPath: String,
    ) : ImportOutcome

    data class Duplicate(
        val existingDocId: String,
        val existingPath: String,
    ) : ImportOutcome

    data class Failed(
        val message: String,
    ) : ImportOutcome
}
