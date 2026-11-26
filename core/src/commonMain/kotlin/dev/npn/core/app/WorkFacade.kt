package dev.npn.core.app

import dev.npn.core.ai.EditRunSource
import dev.npn.core.app.aiedit.AiEditQueueFacade
import kotlinx.coroutines.flow.Flow

/**
 * The session's persisted background queues, for a caller that must keep a process alive while
 * they run — Android's maintenance job holds a wakelock and needs to know when to let go.
 *
 * Each method drains its queue and returns as soon as no further progress is possible right now:
 * queue empty, feature off, endpoint unconfigured, or a backlog waiting out a retry backoff. It
 * deliberately does not wait out a backoff, because nothing is gained by holding the process open
 * for one — the queues are persistent, so whatever is left is picked up by the next drain (a queue
 * change, a rescan, or the next maintenance tick). Before the session's first successful rescan no
 * drain starts, so a call waits for that rescan.
 *
 * Drains are single-flight per queue and run on the session's own scope, so closing the session
 * cancels them rather than letting work touch a closed database.
 */
interface WorkFacade {
    /** Drain the OCR queue until nothing more can be recognised right now. */
    suspend fun drainOcr()

    /** Drain the automatic post-OCR AI enrichment queue until it can make no further progress. */
    suspend fun drainEnrichment()

    /** Drain the queue of AI edit runs handed to the background, suggestion chunk by chunk. */
    suspend fun drainEditRuns()

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

    /**
     * The bulk AI edits handed to the background, for the same view. A run is a whole batch rather
     * than a document, and unlike [queue] it does not clear itself: a finished run is owed a human
     * review, so it stays until the user opens or discards it.
     *
     * Ordered [WorkState.READY] first (newest first), then the runs still being suggested in the
     * order the drain will take them — results the user can act on are the point of the list, and
     * a run that just finished is the one they were waiting for.
     */
    val editRuns: Flow<List<QueuedEditRun>>
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

    /**
     * Being worked on right now: the OCR document or the edit run this device's drain is on, as
     * the live queue reports it. Enrichment entries never show it; their drain leaves the row as
     * it is while working it.
     */
    RUNNING,

    /** Attempted and failed, and will be attempted again after a backoff. */
    RETRYING,

    /** Attempted and given up on. Only the OCR queue is terminal; enrichment retries forever. */
    FAILED,

    /**
     * Finished, and waiting for the user rather than for the app. Only an edit run reaches this:
     * the per-document queues write what they produce, so they have nothing to hand back.
     */
    READY,
}

/**
 * One bulk AI edit handed to the background — see [AiEditQueueFacade.enqueueEditRun].
 *
 * A run is reported at batch grain, not per document, because that is the grain the user acts on:
 * they queued one edit and will review it in one sitting. [done] of [total] documents have had
 * their model call made (or been given up on, which is [failures] of them), and the run becomes
 * [WorkState.READY] when none are left.
 */
data class QueuedEditRun(
    val jobId: String,
    val total: Int,
    val done: Int,
    val failures: Int,
    val state: WorkState,
    /** Epoch millis the run was queued; also its identity to the user ("the one I started then"). */
    val createdAt: Long,
    /** Epoch millis the run last made progress or failed. */
    val updatedAt: Long,
    /** Failed whole-chunk attempts so far; 0 until the endpoint refuses one. */
    val attempts: Int,
    /** Epoch millis this run may next be worked on, or null when nothing is holding it back. */
    val nextAttemptAt: Long? = null,
    /** Message from the most recent failure, or null while the run has never failed. */
    val lastError: String? = null,
    /** Where its suggestions come from: a model, or an emailed e-invoice held for review. */
    val source: EditRunSource = EditRunSource.AI,
)

/**
 * Something this device cannot get past before it can make progress on queued work. A blocked entry
 * is not waiting for its turn: it is waiting for a fix, so Activity shows it as a problem with a way
 * out, never as work in motion.
 */
enum class WorkBlock {
    /** Auto-organize is owed, but no assistant endpoint is configured on this device. */
    AI_ENDPOINT_UNCONFIGURED,
}

/**
 * What this device can currently do with the background queues, as the conditions [WorkBlock] names.
 * Endpoint configuration is followed while Activity is open.
 */
data class WorkCapabilities(
    val aiEndpointConfigured: Boolean,
)

/**
 * Why [entry] cannot make progress right now, or null when it can. A row that failed at its own
 * document's expense (an unreadable file) is not blocked: it has a retry or a retry time, which the
 * row itself says.
 */
fun QueuedWork.blockedBy(capabilities: WorkCapabilities): WorkBlock? =
    when (stage) {
        // An OCR queue row may only need embedded text extraction, even without an engine.
        WorkStage.OCR -> {
            null
        }

        WorkStage.AI_ENRICHMENT -> {
            if (capabilities.aiEndpointConfigured) null else WorkBlock.AI_ENDPOINT_UNCONFIGURED
        }
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
    /** Epoch millis this entry may next be attempted, or null when nothing is holding it back. */
    val nextAttemptAt: Long? = null,
    /** Message from the most recent failure, or null while the entry has never failed. */
    val lastError: String? = null,
)
