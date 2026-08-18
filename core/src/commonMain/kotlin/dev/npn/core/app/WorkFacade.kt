package dev.npn.core.app

import kotlinx.coroutines.flow.Flow

// ---------- background queues: run them to a standstill on demand ----------

/**
 * The session's persisted background queues, for a caller that must keep a process alive while
 * they run — Android's maintenance job holds a wakelock and needs to know when to let go.
 *
 * Each method drains its queue and returns as soon as no further progress is possible right now:
 * queue empty, feature off, endpoint unconfigured, or a backlog waiting out a retry backoff. It
 * deliberately does not wait out a backoff, because nothing is gained by holding the process open
 * for one — the queues are persistent, so whatever is left is picked up by the next drain (a queue
 * change, a rescan, or the next maintenance tick).
 *
 * Drains are single-flight per queue and run on the session's own scope, so closing the session
 * cancels them rather than letting work touch a closed database.
 */
interface WorkFacade {
    /** Drain the OCR queue until nothing more can be recognised right now. */
    suspend fun drainOcr()

    /** Drain the automatic post-OCR AI enrichment queue until it can make no further progress. */
    suspend fun drainEnrichment()

    /**
     * Everything both queues still owe, for a read-only "what is the app still working on" view.
     * Emits on every queue change. Ordered OCR entries first, then enrichment entries — the order
     * the pipeline runs in — each in the order its own drain will take them.
     *
     * Outstanding work only: OCR rows already `done` are history and are left out, as are rows
     * whose document has since been trashed or deleted. One document can legitimately appear
     * twice, once per stage, so entries are keyed by [QueuedWork.docId] *and* [QueuedWork.stage].
     */
    val queue: Flow<List<QueuedWork>>
}

/** Which background pass a [QueuedWork] entry is waiting on. */
enum class WorkStage {
    OCR,
    AI_ENRICHMENT,
}

/**
 * How a [QueuedWork] entry is faring. Deliberately clock-free: [WorkState.RETRYING] means "has
 * failed at least once and will be tried again", not "is due now", so the value does not silently
 * go stale between emissions. Read [QueuedWork.nextAttemptAt] for the timing.
 */
enum class WorkState {
    /** Queued and never attempted. */
    WAITING,

    /** Being worked on right now. Only the OCR queue tracks this; enrichment drains single-flight. */
    RUNNING,

    /** Attempted and failed, and will be attempted again after a backoff. */
    RETRYING,

    /** Attempted and given up on. Only the OCR queue is terminal; enrichment retries forever. */
    FAILED,
}

/** One document waiting on one background pass. */
data class QueuedWork(
    val docId: String,
    /** The document's title when it has one, else its file name. */
    val displayTitle: String,
    val stage: WorkStage,
    val state: WorkState,
    /** Failed attempts so far; 0 until something goes wrong. */
    val attempts: Int,
    /** Epoch millis the queue row last changed. */
    val updatedAt: Long,
    /**
     * Epoch millis this entry may next be attempted, or null when nothing is holding it back.
     * Only the enrichment queue schedules retries this way; OCR retries on the next drain.
     */
    val nextAttemptAt: Long? = null,
    /** Message from the most recent failure, or null while the entry has never failed. */
    val lastError: String? = null,
)
