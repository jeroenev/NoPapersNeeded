package dev.npn.core.work

import dev.npn.core.util.NpnLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The drainers run on [kotlinx.coroutines.test.TestScope.backgroundScope], which `runCurrent` drives. */
class QueueDrainerTest {
    /** Steps that throw: on Android an uncaught one in a background coroutine kills the process. */
    @Test
    fun aThrowingStepIsLoggedAndLaterKicksStillDrain() =
        runTest {
            val logged = mutableListOf<String>()
            val log = NpnLog { _, message, _ -> logged += message }
            var calls = 0
            val drainer =
                QueueDrainer(backgroundScope, "test", log) {
                    calls++
                    if (calls == 1) error("database is locked")
                    false
                }

            drainer.kick()
            runCurrent()
            assertEquals(listOf("test drain stopped after 0 item(s)"), logged)

            drainer.drainAndAwait()
            assertEquals(2, calls)
        }

    @Test
    fun kicksDuringADrainAskForOneMoreDrainNotOneEach() =
        runTest {
            var drains = 0
            val inFirstDrain = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val drainer =
                QueueDrainer(backgroundScope) {
                    drains++
                    if (drains == 1) {
                        inFirstDrain.complete(Unit)
                        release.await()
                    }
                    false
                }

            drainer.kick()
            inFirstDrain.await()
            repeat(100) { drainer.kick() }
            release.complete(Unit)
            runCurrent()

            assertEquals(2, drains)
        }

    @Test
    fun drainAndAwaitWaitsForADrainThatStartedAfterIt() =
        runTest {
            var queued = 0
            var processed = 0
            val drainer =
                QueueDrainer(backgroundScope) {
                    (processed < queued).also { if (it) processed++ }
                }

            queued = 3
            drainer.drainAndAwait()
            assertEquals(3, processed)
        }

    @Test
    fun drainAndAwaitReturnsOnceTheDrainerIsClosed() =
        runTest {
            val never = CompletableDeferred<Unit>()
            val drainer =
                QueueDrainer(backgroundScope) {
                    never.await()
                    false
                }
            drainer.kick()
            runCurrent()

            drainer.close()
            drainer.drainAndAwait()
            assertTrue(never.isActive)
        }
}
