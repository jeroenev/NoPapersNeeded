package dev.npn.app

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Everything [flow] emits from now until the test ends, collected on the [TestScope.backgroundScope].
 * Unconfined, so it subscribes at once and takes each emission as it is made: a `WhileSubscribed`
 * state stays live for the whole test, and no emission waits on the scheduler, which
 * `advanceUntilIdle` does not drain for background work.
 */
fun <T> TestScope.collecting(flow: Flow<T>): List<T> {
    val seen = mutableListOf<T>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
    return seen
}
