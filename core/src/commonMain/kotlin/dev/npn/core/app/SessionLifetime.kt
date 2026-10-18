package dev.npn.core.app

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The one lifetime of an open library session. Its background loops, its queue drains and every
 * caller-driven operation run as children of a single [Job] under the host's scope, so [close]
 * cancels and awaits all of it before the host closes the database, and then releases what the
 * session holds open beside them ([releaseOnClose]). Closing is final: nothing starts on a closed
 * lifetime, and asking it to fails at once with [CLOSED].
 */
internal class SessionLifetime(
    parent: CoroutineScope,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])

    /** Where the session's own background work runs. */
    val scope = CoroutineScope(parent.coroutineContext + job)

    fun checkOpen() = check(job.isActive) { CLOSED }

    /** What [close] releases, in the order registered; guarded by [closing]. */
    private val releases = ArrayList<suspend () -> Unit>()
    private val closing = Mutex()

    /**
     * Have [close] run [release] once everything this lifetime owns has stopped, after the releases
     * registered before it: for what the session holds open beside its work (the journal
     * coordinator, the endpoint client), registered where each is made, while the session opens.
     */
    fun releaseOnClose(release: suspend () -> Unit) {
        releases += release
    }

    /**
     * Run [block] as work the session owns: on [scope] rather than on the caller's coroutine, so
     * [close] cancels and awaits it. Callers start work from scopes that outlive the session — the
     * activity rescans on the app scope whenever it resumes, including on the way back from the
     * folder picker that is about to swap the library — and such work must not be left writing to
     * a database the host is about to close.
     *
     * A caller cancelled meanwhile sees its own cancellation. A session closed under the call is a
     * failure the caller should see, not a cancellation, so that becomes an [IllegalStateException].
     * Any other cancellation is the block's own (a `withTimeout` inside it) and reaches the caller
     * as it is.
     *
     * Owned work may call owned work (a drain writing through the writer facet): the inner call is a
     * sibling on [scope] that the outer one awaits, so [close] cancels and awaits both. A caller
     * that is itself being cancelled (a drain the close just cancelled) gets its own cancellation,
     * not [CLOSED], so it unwinds rather than records a failure.
     */
    @Suppress("SuspendFunSwallowedCancellation") // Deliberate: see above; ensureActive rethrows the caller's own.
    suspend fun <T> owned(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkOpen()
        val work = scope.async { block() }
        return try {
            work.await()
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive()
            if (job.isActive) throw e
            throw IllegalStateException(CLOSED, e)
        }
    }

    /**
     * Cancel and await everything this lifetime owns, then run its releases, for good. Not
     * cancellable: a caller cancelled while it waits still has the whole close done, or the session
     * would be left half closed under a host that goes on to close the databases. Each release runs
     * once, even after one before it threw; the first failure is rethrown once all have run. A
     * second close has nothing left to do.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            closing.withLock {
                job.cancelAndJoin()
                val pending = releases.toList()
                releases.clear()
                val failures = pending.mapNotNull { release -> runCatchingCancellable { release() }.exceptionOrNull() }
                failures.firstOrNull()?.let { first ->
                    failures.drop(1).forEach(first::addSuppressed)
                    throw first
                }
            }
        }
    }

    companion object {
        const val CLOSED = "Library session is closed"
    }
}
