package dev.npn.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

/**
 * Base class that installs a [TestDispatcher] as the Main dispatcher so `viewModelScope`
 * (which uses Dispatchers.Main.immediate) is driven by the test scheduler. Subclasses run their
 * bodies with `runTest(dispatcher) { ... }` so scheduler advancement (advanceUntilIdle,
 * advanceTimeBy for the debounce) works against the same dispatcher.
 */
open class MainDispatcherTest {
    protected val dispatcher: TestDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }
}
