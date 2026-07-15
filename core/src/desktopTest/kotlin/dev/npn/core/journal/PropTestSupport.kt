package dev.npn.core.journal

import kotlinx.coroutines.runBlocking

/**
 * Runs a suspending property block on the current thread and returns Unit, so a JUnit4
 * `@Test fun x() = propTest { ... }` has the void return JUnit4 requires (kotest's `checkAll`
 * otherwise returns a non-Unit context).
 */
fun propTest(block: suspend () -> Unit) {
    runBlocking { block() }
}
