package dev.npn.core.fs

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/** Real time, not virtual: [debounced] measures its max wait on the monotonic clock. */
class DebouncedTest {
    private fun events(
        vararg paths: String?,
        gapMillis: Long = 20,
    ) = flow {
        for (path in paths) {
            emit(FsEvent.Changed(path))
            delay(gapMillis)
        }
    }

    @Test
    fun burstEmitsOnceAfterItGoesQuiet() =
        runBlocking<Unit> {
            val start = TimeSource.Monotonic.markNow()
            // A watcher never completes; completing would flush the burst early.
            val first =
                flow {
                    events("a.pdf", "a.pdf", "a.pdf", "a.pdf").collect { emit(it) }
                    delay(10_000)
                }.debounced(quietMillis = QUIET, maxWaitMillis = 10_000).first()
            val emittedAt = start.elapsedNow().inWholeMilliseconds

            first shouldBe FsEvent.Changed("a.pdf")
            // Four events 20 ms apart, then the quiet window: never on the first event.
            emittedAt shouldBeGreaterThanOrEqual 3 * 20 + QUIET
        }

    @Test
    fun burstOverSeveralPathsEmitsAPathlessChange() =
        runBlocking<Unit> {
            events("a.pdf", ".npn/ocr/x.json")
                .debounced(quietMillis = QUIET, maxWaitMillis = 10_000)
                .toList() shouldContainExactly listOf(FsEvent.Changed(null))
        }

    @Test
    fun continuousChurnStillEmitsEveryMaxWait() =
        runBlocking<Unit> {
            val churn = Array<String?>(40) { "a.pdf" }
            val out =
                events(*churn, gapMillis = 20)
                    .debounced(quietMillis = QUIET, maxWaitMillis = 250)
                    .toList()

            out.size shouldBeGreaterThanOrEqual 2
        }

    @Test
    fun separateBurstsEmitSeparately() =
        runBlocking<Unit> {
            val out =
                flow {
                    emit(FsEvent.Changed("a.pdf"))
                    delay(QUIET * 3)
                    emit(FsEvent.Changed("b.pdf"))
                }.debounced(quietMillis = QUIET, maxWaitMillis = 10_000).toList()

            out shouldContainExactly listOf(FsEvent.Changed("a.pdf"), FsEvent.Changed("b.pdf"))
        }

    private companion object {
        const val QUIET = 100L
    }
}
