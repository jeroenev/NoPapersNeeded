package dev.npn.core.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
}
