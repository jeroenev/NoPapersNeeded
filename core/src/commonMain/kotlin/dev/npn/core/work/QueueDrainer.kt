package dev.npn.core.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background drainer for a persisted work queue: repeatedly runs [step] until it reports there is
 * nothing left to do. Used by the OCR queue and the automatic AI enrichment queue.
 *
 * [step] must process at most one unit of work and return whether it did, leaving the queue row
 * updated so the next call makes progress. Draining is single-flight — a [kick] while a drain is
 * running waits for the lock and then finds the queue empty — which is what keeps a queue whose
 * step writes shared state (sidecars, the journal) free of concurrent writers.
 */
class QueueDrainer(
    parentScope: CoroutineScope,
    private val step: suspend () -> Boolean,
) {
    private val mutex = Mutex()
    private val workerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + workerJob)

    fun kick(): Job = scope.launch { drain() }

    /**
     * Kick, then wait for that drain to finish — for callers that must not return until the work is
     * done, such as Android's WorkManager holding a wakelock.
     *
     * Deliberately not just `drain()` from the caller's own coroutine: the work then runs on this
     * drainer's scope, so [close] still cancels it and a drain cannot outlive the session and touch
     * a closed database. After [close] this is a no-op, and a cancelled drain simply returns here
     * rather than throwing at the caller.
     */
    suspend fun drainAndAwait() {
        kick().join()
    }

    /** Drain on the calling coroutine. Returns items processed. */
    suspend fun drain(): Int {
        var count = 0
        mutex.withLock {
            while (step()) {
                count++
            }
        }
        return count
    }

    /**
     * Cancel and await every drain started through [kick] or [drainAndAwait], without cancelling
     * the parent application scope. A direct [drain] call is the caller's own to cancel.
     */
    suspend fun close() {
        workerJob.cancelAndJoin()
    }
}
