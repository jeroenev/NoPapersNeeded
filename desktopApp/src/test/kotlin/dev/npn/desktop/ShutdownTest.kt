package dev.npn.desktop

import dev.npn.core.util.NpnLog
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Closing the window exits the app whatever closing the library does; a process left behind keeps the lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShutdownTest {
    private val warnings = mutableListOf<String>()
    private val log = NpnLog { level, message, _ -> if (level != NpnLog.Level.INFO) warnings += message }
    private var exits = 0

    @Test
    fun aCloseThatThrowsStillExitsAndIsLogged() =
        runTest {
            closeThenExit(close = { error("database busy") }, timeout = TIMEOUT, log = log, exit = { exits++ })

            assertEquals(1, exits)
            assertEquals(1, warnings.size)
        }

    @Test
    fun aCloseThatHangsExitsAfterTheTimeout() =
        runTest {
            closeThenExit(close = { awaitCancellation() }, timeout = TIMEOUT, log = log, exit = { exits++ })

            assertEquals(TIMEOUT.inWholeMilliseconds, currentTime)
            assertEquals(1, exits)
            assertEquals(1, warnings.size)
        }

    @Test
    fun aCloseThatFinishesExitsOnce() =
        runTest {
            var closed = false
            closeThenExit(close = { closed = true }, timeout = TIMEOUT, log = log, exit = { exits++ })

            assertEquals(true, closed)
            assertEquals(1, exits)
            assertEquals(emptyList(), warnings)
        }

    private companion object {
        val TIMEOUT = 10.seconds
    }
}
