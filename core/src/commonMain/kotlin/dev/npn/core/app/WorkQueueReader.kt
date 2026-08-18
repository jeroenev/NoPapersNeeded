package dev.npn.core.app

import dev.npn.core.db.AiEnrichQueueDao
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.ocr.OcrPipeline
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Read seam over the two persisted work queues, joining their rows to document names so the UI can
 * show what the app still owes. Read-only by design: the queues are driven by their own pipelines
 * and nothing here writes.
 *
 * This is where the two queues' unlike shapes are reconciled into one [QueuedWork] vocabulary — the
 * OCR queue's explicit state column and attempt cap, and the enrichment queue's existence-as-state
 * plus backoff. Doing it once here is what keeps [WorkState] meaningful to a caller that should not
 * have to know either table.
 */
internal class WorkQueueReader(
    private val documentDao: DocumentDao,
    private val ocrQueueDao: OcrQueueDao,
    private val enrichQueueDao: AiEnrichQueueDao,
) {
    val queue: Flow<List<QueuedWork>> =
        combine(
            ocrQueueDao.observeOutstanding(),
            enrichQueueDao.observeQueue(),
        ) { ocr, enrich -> ocr to enrich }
            .map { (ocr, enrich) -> join(ocr, enrich) }

    /**
     * Resolve both row sets against the document table in one pass. A queue row outlives the
     * document it points at, so any row whose document has been trashed or deleted simply drops
     * out — the work is moot and showing it would be noise.
     */
    private suspend fun join(
        ocr: List<OcrQueueEntity>,
        enrich: List<AiEnrichQueueEntity>,
    ): List<QueuedWork> {
        if (ocr.isEmpty() && enrich.isEmpty()) return emptyList()
        val names = displayTitles(ocr.map { it.docId } + enrich.map { it.docId })
        // OCR first: it is upstream of enrichment, so this reads as the pipeline runs. Within each
        // stage the rows already arrive in the order their drain will take them.
        return ocr.mapNotNull { row -> names[row.docId]?.let { row.toQueuedWork(it) } } +
            enrich.mapNotNull { row -> names[row.docId]?.let { row.toQueuedWork(it) } }
    }

    private suspend fun displayTitles(docIds: List<String>): Map<String, String> =
        docIds
            .distinct()
            .chunked(TITLE_LOOKUP_CHUNK)
            .flatMap { documentDao.titlesFor(it) }
            .associate { it.docId to displayTitleOf(it.title, it.fileName) }

    private fun OcrQueueEntity.toQueuedWork(title: String) =
        QueuedWork(
            docId = docId,
            displayTitle = title,
            stage = WorkStage.OCR,
            state =
                when {
                    state == OcrPipeline.STATE_RUNNING -> WorkState.RUNNING

                    state == OcrPipeline.STATE_FAILED -> WorkState.FAILED

                    // A pending row that has already burned attempts is a retry, not fresh work.
                    attempts > 0 -> WorkState.RETRYING

                    else -> WorkState.WAITING
                },
            attempts = attempts,
            updatedAt = updatedAt,
            // The OCR queue has no scheduled retry: a failed attempt leaves the row pending and the
            // next drain picks it up.
            nextAttemptAt = null,
            lastError = lastError,
        )

    private fun AiEnrichQueueEntity.toQueuedWork(title: String) =
        QueuedWork(
            docId = docId,
            displayTitle = title,
            stage = WorkStage.AI_ENRICHMENT,
            // No RUNNING: the drain is single-flight in-process and leaves the row untouched while
            // working it, precisely so a crash mid-call leaves it due rather than stranded. No
            // FAILED either — the row is retried until it succeeds or stops being enrichable.
            state = if (attempts > 0) WorkState.RETRYING else WorkState.WAITING,
            attempts = attempts,
            updatedAt = updatedAt,
            nextAttemptAt = nextAttemptAt,
            lastError = lastError,
        )

    private companion object {
        /** Keeps the expanded `IN (...)` list clear of SQLite's bound-variable ceiling. */
        const val TITLE_LOOKUP_CHUNK = 400
    }
}
