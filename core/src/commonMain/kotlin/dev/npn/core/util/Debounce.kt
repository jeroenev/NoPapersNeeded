package dev.npn.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Debounce a flow of events: a burst becomes one emission once quiet for [quietMillis], so work is
 * not started while a change is still landing, or after [maxWaitMillis] of continuous churn, with
 * every event of the burst folded in by [merge].
 * [timeSource] measures the max wait; the quiet window is a coroutine delay, so a test's virtual
 * time drives both when it passes its scheduler's `timeSource`.
 */
@OptIn(ExperimentalCoroutinesApi::class) // select's onTimeout
fun <T : Any> Flow<T>.debounced(
    quietMillis: Long = 500L,
    maxWaitMillis: Long = 5_000L,
    timeSource: TimeSource = TimeSource.Monotonic,
    merge: (burst: T, next: T) -> T,
): Flow<T> =
    channelFlow {
        val events = Channel<T>(Channel.UNLIMITED)
        launch {
            collect { events.send(it) }
            events.close()
        }
        var burst = events.receiveCatching().getOrNull() ?: return@channelFlow
        var deadline = timeSource.markNow() + maxWaitMillis.milliseconds
        while (true) {
            val wait = minOf(quietMillis.milliseconds, -deadline.elapsedNow())
            // select, not withTimeout around a receive: a timeout racing a receive cannot drop
            // the event. Null means the burst is over: quiet, or at its deadline.
            val next: ChannelResult<T>? =
                if (wait.isPositive()) {
                    select {
                        events.onReceiveCatching { it }
                        onTimeout(wait) { null }
                    }
                } else {
                    null
                }
            when {
                next == null -> {
                    send(burst)
                    burst = events.receiveCatching().getOrNull() ?: return@channelFlow
                    deadline = timeSource.markNow() + maxWaitMillis.milliseconds
                }

                next.isClosed -> {
                    send(burst)
                    return@channelFlow
                }

                else -> {
                    burst = merge(burst, next.getOrThrow())
                }
            }
        }
    }
