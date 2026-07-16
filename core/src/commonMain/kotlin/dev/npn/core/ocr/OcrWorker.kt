package dev.npn.core.ocr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background OCR queue drainer (PLAN.md sec. 5: coroutine worker pool on desktop, WorkManager
 * on Android drives [OcrPipeline.processNext] there). Small and testable: [kick] wakes it,
 * it drains the queue until empty, one item at a time, kept single-flight via the mutex to
 * avoid concurrent sidecar/queue writes.
 */
class OcrWorker(
    private val pipeline: OcrPipeline,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Wake the worker; drains the queue until [OcrPipeline.processNext] returns false. */
    fun kick(): Job = scope.launch {
        // Single-flight: if a drain is already running, this launch will simply wait for the
        // lock and then find the queue empty, returning immediately.
        mutex.withLock {
            @Suppress("ControlFlowWithEmptyBody")
            while (pipeline.processNext()) {
                // keep draining
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
}
