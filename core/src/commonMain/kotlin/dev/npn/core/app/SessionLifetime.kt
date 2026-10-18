package dev.npn.core.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The one lifetime of an open library session. Its background loops, its queue drains and every
 * caller-driven operation run as children of a single [Job] under the host's scope, so [close]
 * cancels and awaits all of it before the host closes the database. Closing is final: nothing
 * starts on a closed lifetime, and asking it to fails at once with [CLOSED].
 */
internal class SessionLifetime(
    parent: CoroutineScope,
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])

    /** Where the session's own background work runs. */
    val scope = CoroutineScope(parent.coroutineContext + job)

    fun checkOpen() = check(job.isActive) { CLOSED }

    /**
     * Run [block] as work the session owns: on [scope] rather than on the caller's coroutine, so
     * [close] cancels and awaits it. Callers start work from scopes that outlive the session — the
     * activity rescans on the app scope whenever it resumes, including on the way back from the
     * folder picker that is about to swap the library — and such work must not be left writing to
     * a database the host is about to close.
     *
     * A caller cancelled meanwhile sees its own cancellation. A session closed under the call is a
     * failure the caller should see, not a cancellation, so that becomes an [IllegalStateException].
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
            throw IllegalStateException(CLOSED, e)
        }
    }

    /** Cancel and await everything this lifetime owns, for good. */
    suspend fun close() = job.cancelAndJoin()

    companion object {
        const val CLOSED = "Library session is closed"
    }
}
