package dev.npn.core.sim

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The deterministic simulation of one replica (docs/SIMULATION.md): seeded runs of the session
 * against an in-memory library folder on virtual time, each step checked against the library's
 * invariants and, at rest, against an index rebuilt from the files alone.
 *
 * CI runs [CI_SEEDS] seeds of [CI_STEPS] steps. A longer run, or a replay of one seed:
 *
 * ```
 * ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seeds=200 -Dnpn.sim.steps=500
 * ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest' -Dnpn.sim.seed=1234
 * ```
 *
 * `npn.sim.seeds` runs seeds 1 to n (from `npn.sim.firstSeed` if set); `npn.sim.seed` runs just
 * that one. The build forwards all four properties to the test JVM.
 */
class LibrarySimulationTest {
    @Test
    fun seededRunsKeepEveryInvariant() {
        val steps = System.getProperty("npn.sim.steps")?.toInt() ?: CI_STEPS
        val seeds =
            System.getProperty("npn.sim.seed")?.let { listOf(it.toLong()) }
                ?: run {
                    val first = System.getProperty("npn.sim.firstSeed")?.toLong() ?: 1L
                    val count = System.getProperty("npn.sim.seeds")?.toInt() ?: CI_SEEDS
                    (first until first + count).toList()
                }
        val failures = ArrayList<String>()
        for (seed in seeds) {
            val config = SimConfig.forSeed(seed, steps)
            val failure = runCatching { runSeed(config) }.exceptionOrNull() ?: continue
            if (failure !is SimFailure) throw failure
            failures += failure.message.orEmpty()
            if (failures.size >= MAX_REPORTED) break
        }
        if (failures.isNotEmpty()) {
            throw SimFailure("${failures.size} seed(s) failed:\n\n" + failures.joinToString("\n\n" + "=".repeat(RULE) + "\n\n"))
        }
    }

    private fun runSeed(config: SimConfig) =
        runTest(timeout = (1 + config.steps / STEPS_PER_MINUTE).minutes) {
            Simulation(testScheduler, config).run()
        }

    private companion object {
        const val CI_SEEDS = 6
        const val CI_STEPS = 150
        const val STEPS_PER_MINUTE = 50
        const val MAX_REPORTED = 3
        const val RULE = 80
    }
}
