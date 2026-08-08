package dev.npn.core.app

import kotlinx.coroutines.flow.Flow

// ---------- OCR ops: text-recognition management per document ----------

/** Per-document OCR management: re-runs, stored versions, and the engine pin. */
interface OcrFacade {
    val ocrStatus: Flow<OcrStatus>

    /** Re-queue a single document for OCR (resets its state to pending and kicks the worker). */
    suspend fun reOcr(docId: String)

    /** All stored OCR versions of [docId], best-ranked first. */
    suspend fun ocrVersions(docId: String): List<OcrVersion>

    /**
     * Pin (or clear, when [engine] is null) the OCR engine used for search on [docId], then
     * immediately re-select and re-index the matching stored version on this device. The pin
     * itself syncs via the journal; other devices re-select on ingesting the event.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    )
}

data class OcrStatus(
    val pending: Int,
    val running: Boolean,
)

/**
 * One stored OCR version of a document (an OCR sidecar). [active] marks the version whose
 * text is currently indexed for search on this device.
 */
data class OcrVersion(
    val engine: String,
    val deviceId: String,
    val lang: String,
    val createdAt: Long,
    val active: Boolean,
)
