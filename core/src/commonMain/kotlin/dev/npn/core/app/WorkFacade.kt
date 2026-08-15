package dev.npn.core.app

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
}
