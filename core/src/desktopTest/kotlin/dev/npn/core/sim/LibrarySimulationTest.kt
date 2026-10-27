package dev.npn.core.sim

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The deterministic simulation of a library shared by two to four replicas through a sync tool
 * (docs/SIMULATION.md, [SyncSimulation]): seeded runs of each replica's session against its own
 * in-memory library folder on one virtual clock, each step checked against the library's
 * invariants and those across replicas, and, at rest, each replica against an index rebuilt from
 * its files alone and, after a full sync, against the others.
 *
 * The seeds are dealt round-robin over [SHARDS] test classes, `LibrarySimulationTest1` to
 * `LibrarySimulationTest8`, so Gradle can run them in as many test JVMs at once
 * (`maxParallelForks` in `core/build.gradle.kts`, within `--max-workers`); within a JVM the seeds
 * run one after the other, as a seed must. CI runs [CI_SEEDS] seeds of [CI_STEPS] steps. A longer
 * run, or a replay of one seed:
 *
 * ```
 * ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*' -Dnpn.sim.seeds=200 -Dnpn.sim.steps=500
 * ./gradlew :core:desktopTest --tests 'dev.npn.core.sim.LibrarySimulationTest*' -Dnpn.sim.seed=1234
 * ```
 *
 * `npn.sim.seeds` runs seeds 1 to n (from `npn.sim.firstSeed` if set); `npn.sim.seed` runs just
 * that one, the only seed of the run, so in shard 1; `npn.sim.maxReported` says how many failing
 * seeds each shard of a long run reports, and `npn.sim.traceDir` where every seed's trace goes. The
 * build forwards all of these and `npn.sim.steps` to the test JVMs.
 */
@Suppress("AbstractClassCanBeConcreteClass") // Abstract keeps JUnit from running the base as a ninth class.
abstract class LibrarySimulationShard(
    /** This class's shard, 1 to [SHARDS]: it runs every [SHARDS]th seed of the run, from its own. */
    private val shard: Int,
) {
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
        val mine = seeds.filterIndexed { index, _ -> index % SHARDS == shard - 1 }
        if (mine.isEmpty()) {
            println("Shard $shard of $SHARDS: none of the ${seeds.size} seed(s) falls to it")
            return
        }
        val failures = ArrayList<String>()
        val known = ArrayList<String>()
        val timings = ArrayList<String>()
        val maxReported = System.getProperty("npn.sim.maxReported")?.toInt() ?: MAX_REPORTED
        for (seed in mine) {
            val started = System.nanoTime()
            val failure = runCatching { runSeed(SimConfig.forSeed(seed, steps)) }.exceptionOrNull()
            timings +=
                "seed $seed: ${(System.nanoTime() - started) / NANOS_PER_MILLI} ms" +
                if (failure != null) " (stopped)" else ""
            if (failure != null && failure !is SimFailure) throw failure
            val stopped: SimFailure? = failure
            // A replay of one seed shows its failure, known or not.
            val knownFinding = stopped?.known?.takeIf { seeds.size > 1 }
            when {
                knownFinding != null -> known += "seed $seed: $knownFinding"
                stopped != null -> failures += stopped.message.orEmpty()
            }
            if (failures.size >= maxReported) break
        }
        println("Shard $shard of $SHARDS, simulation time per seed:\n" + timings.joinToString("\n"))
        // Seeds that stopped at a reported finding (KnownIssues.knownFailures), so a long run shows how often.
        if (known.isNotEmpty()) {
            println("${known.size} of ${mine.size} seed(s) stopped at a known finding:\n" + known.joinToString("\n"))
        }
        if (failures.isNotEmpty()) {
            throw SimFailure(
                "${failures.size} seed(s) failed:\n\n" + failures.joinToString("\n\n" + "=".repeat(RULE) + "\n\n"),
            )
        }
    }

    private fun runSeed(config: SimConfig) =
        runTest(timeout = (1 + config.steps / STEPS_PER_MINUTE).minutes) {
            SyncSimulation(testScheduler, config).run()
        }

    companion object {
        /** How many test classes the seeds are dealt over: the classes below. */
        const val SHARDS = 8

        private const val CI_SEEDS = 10
        private const val CI_STEPS = 250
        private const val STEPS_PER_MINUTE = 50
        private const val MAX_REPORTED = 3
        private const val RULE = 80
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

class LibrarySimulationTest1 : LibrarySimulationShard(1)

class LibrarySimulationTest2 : LibrarySimulationShard(2)

class LibrarySimulationTest3 : LibrarySimulationShard(3)

class LibrarySimulationTest4 : LibrarySimulationShard(4)

class LibrarySimulationTest5 : LibrarySimulationShard(5)

class LibrarySimulationTest6 : LibrarySimulationShard(6)

class LibrarySimulationTest7 : LibrarySimulationShard(7)

class LibrarySimulationTest8 : LibrarySimulationShard(8)
