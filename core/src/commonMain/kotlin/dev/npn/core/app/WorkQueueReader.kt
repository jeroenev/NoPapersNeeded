package dev.npn.core.app

import dev.npn.core.db.AiEditJobDao
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.db.AiEditJobProgress
import dev.npn.core.db.AiEnrichQueueDao
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DocumentDao
import dev.npn.core.db.OcrQueueDao
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.queueState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Read seam over the persisted work queues, joining their rows to document names so the UI can
 * show what the app still owes. Read-only by design: the queues are driven by their own pipelines
 * and nothing here writes.
 *
 * This is where the queues' unlike shapes are reconciled into one vocabulary — the OCR queue's
 * terminal-state column and attempt cap, the enrichment queue's existence-as-state plus backoff,
 * and the edit queue's progress derived from its document rows. Doing it once here is what keeps
 * [WorkState] meaningful to a caller that should not have to know any of those tables.
 *
 * No queue stores "running": that fact belongs to the process doing the work and would be a lie
 * the moment one died (see [dev.npn.core.db.OcrQueueEntity]). [ocrInFlight] and [editInFlight]
 * carry it in from the live OCR pipeline and edit queue instead, which is why [WorkState.RUNNING]
 * can never survive a restart here.
 *
 * Per-document work and whole-run work stay two flows rather than one list. They differ in grain
 * (a document owed a pass versus a batch owed a review), in lifetime (the first clears itself, the
 * second waits for a human) and in what the user can do with them, so flattening them would only
 * force every consumer to split them apart again.
 */
internal class WorkQueueReader(
    private val documentDao: DocumentDao,
    ocrQueueDao: OcrQueueDao,
    enrichQueueDao: AiEnrichQueueDao,
    editJobDao: AiEditJobDao,
    ocrInFlight: Flow<String?>,
    editInFlight: Flow<String?>,
) {
    val queue: Flow<List<QueuedWork>> =
        combine(
            ocrQueueDao.observeOutstanding(),
            enrichQueueDao.observeQueue(),
            ocrInFlight,
        ) { ocr, enrich, inFlight -> join(ocr, enrich, inFlight) }

    val editRuns: Flow<List<QueuedEditRun>> =
        combine(
            editJobDao.observeJobs(),
            editJobDao.observeProgress(),
            editInFlight,
        ) { jobs, progress, inFlight ->
            val byJob = progress.associateBy { it.jobId }
            jobs.mapNotNull { it.toQueuedEditRun(byJob[it.jobId], inFlight) }.readyFirst()
        }

    /**
     * Finished runs first, newest first, then the rest in the order the drain will take them. The
     * only entries the user can act on are the finished ones, and the newest is the one they were
     * most likely waiting for.
     */
    private fun List<QueuedEditRun>.readyFirst(): List<QueuedEditRun> {
        val (ready, working) = partition { it.state == WorkState.READY }
        return ready.sortedByDescending { it.updatedAt } + working
    }

    /**
     * A job's state is derived from its document rows rather than stored, so the two can never
     * disagree: nothing owed means the suggestions are complete and a human is the only thing
     * left; a failed attempt means retrying. Whether it is under way is asked of the live queue
     * ([inFlight]), never inferred from progress: a half-suggested run nobody is draining waits.
     *
     * A job whose document rows have vanished entirely is skipped — it can neither be finished nor
     * reviewed, and the only way to reach that state is a partially applied delete.
     */
    private fun AiEditJobEntity.toQueuedEditRun(
        progress: AiEditJobProgress?,
        inFlight: String?,
    ): QueuedEditRun? {
        val counted = progress?.takeIf { it.total > 0 } ?: return null
        return QueuedEditRun(
            jobId = jobId,
            total = counted.total,
            done = counted.done,
            failures = counted.failures,
            state =
                when {
                    counted.done >= counted.total -> WorkState.READY
                    jobId == inFlight -> WorkState.RUNNING
                    attempts > 0 -> WorkState.RETRYING
                    else -> WorkState.WAITING
                },
            createdAt = createdAt,
            updatedAt = updatedAt,
            attempts = attempts,
            nextAttemptAt = nextAttemptAt.takeIf { attempts > 0 },
            lastError = lastError,
        )
    }

    /**
     * Resolve both row sets against the document table in one pass. A queue row outlives the
     * document it points at, so any row whose document has been trashed or deleted simply drops
     * out — the work is moot and showing it would be noise.
     */
    private suspend fun join(
        ocr: List<OcrQueueEntity>,
        enrich: List<AiEnrichQueueEntity>,
        ocrInFlight: String?,
    ): List<QueuedWork> {
        if (ocr.isEmpty() && enrich.isEmpty()) return emptyList()
        val names = displayTitles(ocr.map { it.docId } + enrich.map { it.docId })
        // OCR first: it is upstream of enrichment, so this reads as the pipeline runs. Within each
        // stage the rows already arrive in the order their drain will take them.
        return ocr.mapNotNull { row -> names[row.docId]?.let { row.toQueuedWork(it, ocrInFlight) } } +
            enrich.mapNotNull { row -> names[row.docId]?.let { row.toQueuedWork(it) } }
    }

    private suspend fun displayTitles(docIds: List<String>): Map<String, String> =
        documentDao.titlesFor(docIds.distinct()).associate { it.docId to displayTitleOf(it.title, it.fileName) }

    private fun OcrQueueEntity.toQueuedWork(
        title: String,
        inFlight: String?,
    ) = QueuedWork(
        docId = docId,
        displayTitle = title,
        stage = WorkStage.OCR,
        state =
            when {
                // Asked of the live pipeline, not of the row: the row says only that this
                // document is owed a pass, which stays true while the pass is happening.
                docId == inFlight -> WorkState.RUNNING

                queueState == QueueState.FAILED -> WorkState.FAILED

                // A pending row that has already burned attempts is a retry, not fresh work.
                attempts > 0 -> WorkState.RETRYING

                else -> WorkState.WAITING
            },
        attempts = attempts,
        updatedAt = updatedAt,
        // Only a retry waits for a time; a row given up on is not coming back by itself.
        nextAttemptAt = nextAttemptAt.takeIf { attempts > 0 && queueState != QueueState.FAILED },
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
}
