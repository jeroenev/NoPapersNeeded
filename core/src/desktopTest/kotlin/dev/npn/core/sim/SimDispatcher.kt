package dev.npn.core.sim

import dev.npn.core.util.IdSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * The life of one process in the simulation: a session's scope carries it, and so does every
 * coroutine that scope starts, Room's queries and the computation dispatcher's work included (an
 * element of a context stays through `withContext`). [SimDispatcher] runs a coroutine only while its
 * life is [State.ALIVE]: a [State.PAUSED] one has its work held until it is let go again, and a
 * [State.DEAD] one never runs again, not even its `finally` blocks, as in a killed process.
 */
internal class SimLife(
    val owner: String,
) : AbstractCoroutineContextElement(SimLife) {
    enum class State { ALIVE, PAUSED, DEAD }

    var state = State.ALIVE
        private set

    private val held = ArrayList<() -> Unit>()

    /** Run [work] now, hold it while paused, or drop it once dead. */
    fun run(work: () -> Unit) {
        when (state) {
            State.ALIVE -> work()
            State.PAUSED -> held += work
            State.DEAD -> Unit
        }
    }

    fun pause() {
        check(state == State.ALIVE) { "$owner is $state" }
        state = State.PAUSED
    }

    /** Let a paused process go on: what it was about to run is run, in the order it came. */
    fun resume() {
        check(state == State.PAUSED) { "$owner is $state" }
        state = State.ALIVE
        val pending = held.toList()
        held.clear()
        pending.forEach { it() }
    }

    /** End the process for good: nothing it holds or starts runs again. */
    fun kill() {
        state = State.DEAD
        held.clear()
    }

    companion object Key : CoroutineContext.Key<SimLife>
}

/**
 * The simulation's one dispatcher: [base], a test dispatcher on the run's scheduler, behind a check
 * of the [SimLife] of what it is asked to run. Work with no life (the simulation's own operations,
 * the sync tool, Room's housekeeping) always runs. A delay is the scheduler's, so virtual time
 * passes as it does for [base]; the wake-up is checked against the life as dispatched work is.
 */
@OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)
internal class SimDispatcher(
    private val base: TestDispatcher,
) : CoroutineDispatcher(),
    Delay {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = base.isDispatchNeeded(context)

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        val life = context[SimLife]
        if (life == null) {
            base.dispatch(context, block)
        } else {
            life.run { base.dispatch(context, block) }
        }
    }

    override fun scheduleResumeAfterDelay(
        timeMillis: Long,
        continuation: CancellableContinuation<Unit>,
    ) {
        val wake =
            Runnable {
                val resume = { with(continuation) { this@SimDispatcher.resumeUndispatched(Unit) } }
                val life = continuation.context[SimLife]
                if (life == null) resume() else life.run(resume)
            }
        val handle = base.invokeOnTimeout(timeMillis, wake, continuation.context)
        continuation.invokeOnCancellation { handle.dispose() }
    }

    override fun invokeOnTimeout(
        timeMillis: Long,
        block: Runnable,
        context: CoroutineContext,
    ): DisposableHandle = base.invokeOnTimeout(timeMillis, block, context)

    override fun toString(): String = "SimDispatcher($base)"
}

/**
 * Fresh names drawn from a seeded [random], shaped as the UUIDs production draws: what makes a
 * replayed seed name its journal actors, staging files and device ids as the first run did.
 */
internal class SimIds(
    private val random: Random,
) : IdSource {
    /** A version-4 (random) UUID, as the journal requires of an actor id. */
    override fun next(): String {
        val high = (random.nextLong() and VERSION_MASK.inv()) or VERSION_4
        val low = (random.nextLong() and VARIANT_MASK.inv()) or VARIANT_RFC
        return Uuid.fromLongs(high, low).toString()
    }

    private companion object {
        const val VERSION_MASK = 0xF000L
        const val VERSION_4 = 0x4000L
        const val VARIANT_MASK = -0x4000_0000_0000_0000L // the top two bits
        const val VARIANT_RFC = Long.MIN_VALUE // 0b10 in the top two bits
    }
}
