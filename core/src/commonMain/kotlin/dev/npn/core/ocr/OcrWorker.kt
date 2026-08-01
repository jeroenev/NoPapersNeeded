package dev.npn.core.ocr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background OCR queue drainer. [kick] drains one item at a time and uses the mutex to prevent
 * concurrent sidecar and queue writes.
 */
class OcrWorker(
    private val pipeline: OcrPipeline,
    parentScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val workerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + workerJob)

    fun kick(): Job =
        scope.launch {
            // Single-flight: if a drain is already running, this launch will simply wait for the
            // lock and then find the queue empty, returning immediately.
            mutex.withLock {
                @Suppress("ControlFlowWithEmptyBody")
                while (pipeline.processNext()) {
                    // processNext performs each unit of work.
                }
            }
        }

    /** Drain synchronously (mainly for tests). Returns the number of items processed. */
    suspend fun drain(): Int {
        var count = 0
        mutex.withLock {
            while (pipeline.processNext()) {
                count++
            }
        }
        return count
    }

    /** Cancel and await every OCR drain without cancelling the parent application scope. */
    suspend fun close() {
        workerJob.cancelAndJoin()
    }
}
