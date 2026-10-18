package dev.npn.core.app

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SessionLifetimeTest {
    @Test
    fun closeCancelsAndAwaitsOwnedWorkAndTheCallerSeesAFailure() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            val started = CompletableDeferred<Unit>()
            var cleanedUp = false
            supervisorScope {
                val caller =
                    async {
                        lifetime.owned {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                cleanedUp = true
                            }
                        }
                    }
                started.await()

                lifetime.close()

                assertTrue(cleanedUp)
                val failure = assertFailsWith<IllegalStateException> { caller.await() }
                assertEquals(SessionLifetime.CLOSED, failure.message)
            }
        }

    @Test
    fun closingIsFinal() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            lifetime.close()

            assertFailsWith<IllegalStateException> { lifetime.owned { } }
            assertFailsWith<IllegalStateException> { lifetime.checkOpen() }
        }

    /** A cancellation the block raises itself is not the session closing. */
    @Test
    fun aTimeoutInsideOwnedWorkReachesTheCallerAsItIs() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)

            assertFailsWith<TimeoutCancellationException> {
                lifetime.owned { withTimeout(TIMEOUT_MS) { awaitCancellation() } }
            }
            lifetime.checkOpen()
        }

    @Test
    fun aCancelledCallerSeesItsOwnCancellationAndTheWorkStops() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            val started = CompletableDeferred<Unit>()
            var outcome: Throwable? = null
            var workStopped = false
            val caller =
                launch {
                    lifetime.owned {
                        try {
                            started.complete(Unit)
                            awaitCancellation()
                        } finally {
                            workStopped = true
                        }
                    }
                }
            caller.invokeOnCompletion { outcome = it }
            started.await()

            caller.cancelAndJoin()
            runCurrent()

            assertTrue(outcome is CancellationException, "was $outcome")
            assertTrue(workStopped)
            lifetime.checkOpen()
        }

    /** What the session holds open beside its work is released once that work has stopped, in order. */
    @Test
    fun closeReleasesInOrderOnceTheOwnedWorkHasStopped() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            val started = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            lifetime.releaseOnClose { events += "coordinator" }
            lifetime.releaseOnClose { events += "endpoint client" }
            val work =
                launch {
                    runCatchingCancellable {
                        lifetime.owned {
                            try {
                                started.complete(Unit)
                                awaitCancellation()
                            } finally {
                                events += "work stopped"
                            }
                        }
                    }
                }
            started.await()

            lifetime.close()
            work.join()

            assertEquals(listOf("work stopped", "coordinator", "endpoint client"), events)
        }

    @Test
    fun aSecondCloseReleasesNothingAgain() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            var released = 0
            lifetime.releaseOnClose { released++ }

            lifetime.close()
            lifetime.close()

            assertEquals(1, released)
        }

    /** One release that fails leaves the others to run, and the close then reports it. */
    @Test
    fun aReleaseThatFailsStillLetsTheNextOneRun() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            var released = false
            lifetime.releaseOnClose { error("coordinator would not close") }
            lifetime.releaseOnClose { released = true }

            val failure = assertFailsWith<IllegalStateException> { lifetime.close() }

            assertEquals("coordinator would not close", failure.message)
            assertTrue(released)
        }

    /** A caller cancelled while the close waits on a release still has the whole close done. */
    @Test
    fun aCloseWhoseCallerIsCancelledStillRunsEveryRelease() =
        runTest {
            val lifetime = SessionLifetime(backgroundScope)
            val releasing = CompletableDeferred<Unit>()
            val proceed = CompletableDeferred<Unit>()
            var released = false
            lifetime.releaseOnClose {
                releasing.complete(Unit)
                proceed.await()
            }
            lifetime.releaseOnClose { released = true }
            val closing = launch { lifetime.close() }
            releasing.await()

            closing.cancel()
            proceed.complete(Unit)
            closing.join()

            assertTrue(released)
        }

    private companion object {
        const val TIMEOUT_MS = 10L
    }
}
