package dev.npn.core.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where a library filesystem decorator holds an operation at its storage boundary: the call that
 * [pause]s says so on [reached], then waits until [release]. A test asks whether another operation
 * finishes meanwhile with [finishesWithin], which waits on [realTime], the wall clock, not the
 * test's virtual time.
 */
open class FsGate(
    private val realTime: CoroutineDispatcher = Dispatchers.Default,
) {
    val reached = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    fun release() = gate.complete(Unit)

    /** Say the call got here, then hold it until [release]. */
    suspend fun pause() {
        reached.complete(Unit)
        gate.await()
    }

    /** Whether [job] finishes within [millis] of real time, not the test's virtual time. */
    suspend fun finishesWithin(
        job: Job,
        millis: Long,
    ): Boolean = withContext(realTime) { withTimeoutOrNull(millis) { job.join() } != null }
}
