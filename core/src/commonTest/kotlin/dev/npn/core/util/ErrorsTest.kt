package dev.npn.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorsTest {
    @Test
    fun aSuccessAndAnOrdinaryFailureAreCapturedLikeRunCatching() {
        assertEquals(Result.success(3), runCatchingCancellable { 1 + 2 })
        val failure = IllegalStateException("broken")
        assertEquals(failure, runCatchingCancellable { throw failure }.exceptionOrNull())
    }

    @Test
    fun cancellationIsRethrownRatherThanCaptured() {
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }
    }

    /** The point of it: a cancelled coroutine stays cancelled instead of carrying on as if it had failed. */
    @Test
    fun aCancelledCoroutineDoesNotCarryOnPastTheBlock() =
        runTest {
            val started = CompletableDeferred<Unit>()
            var carriedOn = false
            val work =
                async {
                    runCatchingCancellable {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    carriedOn = true
                }
            started.await()
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertFalse(carriedOn)
        }

    @Test
    fun aReasonIsTheMessageOrElseTheClassName() {
        assertEquals("disk full", IllegalStateException("disk full").reason())
        assertEquals("IllegalStateException", IllegalStateException().reason())
        assertEquals("IllegalArgumentException", IllegalArgumentException("  ").reason())
    }
}
