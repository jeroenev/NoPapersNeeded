package dev.npn.core.work

import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Background drainer for a persisted work queue: repeatedly runs [step] until it reports there is
 * nothing left to do. Used by the OCR queue, the automatic AI enrichment queue and queued AI edit
 * runs.
 *
 * [step] must process at most one unit of work and return whether it did, leaving the queue row
 * updated so the next call makes progress. Draining is single-flight, which is what keeps a queue
 * whose step writes shared state (sidecars, the journal) free of concurrent writers.
 *
 * One worker loop does the draining, fed by a conflated channel: a [kick] while a drain runs asks
 * for exactly one more drain after it, however many kicks arrive meanwhile. A step that throws is
 * logged to [log] and ends that drain; the loop carries on, and the next kick drains again.
 */
class QueueDrainer(
    parentScope: CoroutineScope,
    private val name: String = "queue",
    private val log: NpnLog = NpnLog.None,
    private val step: suspend () -> Boolean,
) {
    private val mutex = Mutex()
    private val workerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + workerJob)
    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** How many kicks have been asked for, and the latest of them a finished drain started after. */
    private val requested = MutableStateFlow(0L)
    private val drained = MutableStateFlow(0L)

    // Started by the first kick, so a drainer only ever driven through [drain] runs no loop at all.
    private val worker =
        scope.launch(start = CoroutineStart.LAZY) {
            kicks.consumeEach {
                // Every kick counted so far was made before this drain started, so it covers them.
                val covers = requested.value
                // Its own child, so a stray CancellationException out of a step (a request on an HTTP
                // client closed under it) ends that drain rather than this loop.
                launch { drain() }.join()
                drained.value = covers
            }
        }

    /** Ask for a drain. Returns at once; the drain runs on this drainer's own scope. */
    fun kick() {
        request()
    }

    private fun request(): Long {
        val generation = requested.updateAndGet { it + 1 }
        worker.start()
        kicks.trySend(Unit)
        return generation
    }

    /**
     * Kick, then wait until a drain that started after that kick has finished — for callers that
     * must not return until the work is done, such as Android's WorkManager holding a wakelock.
     *
     * Deliberately not just `drain()` from the caller's own coroutine: the work then runs on this
     * drainer's scope, so [close] still cancels it and a drain cannot outlive the session and touch
     * a closed database. After [close] this returns at once, and a cancelled drain simply returns
     * here rather than throwing at the caller.
     */
    suspend fun drainAndAwait() {
        val generation = request()
        val done = scope.launch { drained.first { it >= generation } }
        try {
            done.join()
        } finally {
            done.cancel()
        }
    }

    /**
     * Drain on the calling coroutine and return the items processed. A step that throws is logged
     * and ends the drain; cancellation is rethrown.
     */
    internal suspend fun drain(): Int {
        var count = 0
        mutex.withLock {
            runCatchingCancellable {
                while (step()) count++
            }.onFailure { log.warn("$name drain stopped after $count item(s)", it) }
        }
        return count
    }

    /**
     * Cancel and await the worker loop and every [drainAndAwait], without cancelling the parent
     * scope. A direct [drain] call is the caller's own to cancel.
     */
    suspend fun close() {
        kicks.close()
        workerJob.cancelAndJoin()
    }
}

/** A persisted queue and the [worker] that drains it; see [drainedQueue]. */
class DrainedQueue<Q>(
    val queue: Q,
    val worker: QueueDrainer,
)

/**
 * Build a queue whose trigger kicks its own drainer: [build] receives the `onEnqueued` callback to
 * call whenever it records new work, and the drainer runs [step] over that same queue. Built this
 * way, a queue cannot record work that waits for a periodic tick, whoever holds it afterwards.
 */
fun <Q> drainedQueue(
    scope: CoroutineScope,
    name: String,
    log: NpnLog,
    build: (onEnqueued: () -> Unit) -> Q,
    step: suspend (Q) -> Boolean,
): DrainedQueue<Q> {
    // The callback only runs once something is queued, which needs the queue, so after this is set.
    lateinit var worker: QueueDrainer
    val queue = build { worker.kick() }
    worker = QueueDrainer(scope, name, log) { step(queue) }
    return DrainedQueue(queue, worker)
}
