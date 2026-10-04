package dev.npn.core.fs

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** On virtual time: the max wait is measured on the test scheduler's time source. */
class DebouncedTest {
    private fun events(
        vararg paths: String?,
        gapMillis: Long = 20,
    ) = flow {
        for (path in paths) {
            emit(FsEvent(path))
            delay(gapMillis)
        }
    }

    private fun TestScope.debounce(
        events: Flow<FsEvent>,
        maxWaitMillis: Long = 10_000,
    ) = events.debounced(quietMillis = QUIET, maxWaitMillis = maxWaitMillis, timeSource = testScheduler.timeSource)

    @Test
    fun burstEmitsOnceAfterItGoesQuiet() =
        runTest {
            // A watcher never completes; completing would flush the burst early.
            val first =
                debounce(
                    flow {
                        events("a.pdf", "a.pdf", "a.pdf", "a.pdf").collect { emit(it) }
                        delay(10_000)
                    },
                ).first()

            first shouldBe FsEvent("a.pdf")
            // Four events 20 ms apart, then the quiet window: never on the first event.
            testScheduler.currentTime shouldBe 3 * 20 + QUIET
        }

    @Test
    fun burstOverSeveralPathsEmitsAPathlessChange() =
        runTest {
            debounce(events("a.pdf", ".npn/ocr/x.json")).toList() shouldContainExactly listOf(FsEvent(null))
        }

    @Test
    fun continuousChurnStillEmitsEveryMaxWait() =
        runTest {
            val churn = Array<String?>(40) { "a.pdf" }
            val out = debounce(events(*churn, gapMillis = 20), maxWaitMillis = 250).toList()

            // 800 ms of churn: one emission per 250 ms of it, then the rest when it ends.
            out.size shouldBeGreaterThanOrEqual 3
        }

    @Test
    fun separateBurstsEmitSeparately() =
        runTest {
            val out =
                debounce(
                    flow {
                        emit(FsEvent("a.pdf"))
                        delay(QUIET * 3)
                        emit(FsEvent("b.pdf"))
                    },
                ).toList()

            out shouldContainExactly listOf(FsEvent("a.pdf"), FsEvent("b.pdf"))
        }

    private companion object {
        const val QUIET = 100L
    }
}
