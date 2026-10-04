package dev.npn.core.app

import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import kotlinx.coroutines.flow.Flow

/**
 * Getting documents into the library: imports, rescans, and their progress/reporting.
 *
 * An import that cannot add the file returns [ImportOutcome.Failed]; it throws only when the session
 * is closed.
 */
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

    /** The file did not become a document; see [reason]. */
    data class Failed(
        val reason: ImportFailure,
    ) : ImportOutcome
}

/** Why an import added nothing. The underlying cause, if any, is in the session log. */
enum class ImportFailure {
    /** The file has no bytes, so it has no document identity. */
    EMPTY_FILE,

    /** The images could not be made into a PDF. */
    UNREADABLE_IMAGES,

    /** The file could not be copied into the library: unreadable, gone, or the library refused it. */
    COPY_FAILED,
}
