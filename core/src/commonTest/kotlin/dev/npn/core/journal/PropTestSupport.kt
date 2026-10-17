package dev.npn.core.journal

import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest

/**
 * Runs a suspending property block as a test, so `@Test fun x() = propTest { ... }` returns the
 * [TestResult] a test function may return (kotest's `checkAll` otherwise returns a non-Unit context).
 */
fun propTest(block: suspend () -> Unit): TestResult = runTest { block() }
