// checkAll's per-test PropTestConfig overload (iteration counts here are tuned per property) is
// still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.journal

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll
import kotlin.test.Test

class HlcOrderPropertyTest {
    private val hlcArb =
        Arb.bind(
            Arb.long(0L, 5L), // small physical range to force collisions/ties
            Arb.int(0, 3),
            Arb.of("a", "b", "c", "d"),
        ) { t, c, d -> Hlc(t, c, d) }

    @Test
    fun antisymmetryAndReflexivity() =
        propTest {
            checkAll(PropTestConfig(iterations = 500), hlcArb, hlcArb) { a, b ->
                require(a.compareTo(a) == 0) { "reflexive" }
                val ab = a.compareTo(b)
                val ba = b.compareTo(a)
                // total order: sign(ab) == -sign(ba); equal only when all fields equal
                require(ab.compareTo(0) == -ba.compareTo(0)) { "antisymmetry violated for $a vs $b" }
                if (ab == 0) require(a == b) { "equal compare must mean structurally equal" }
            }
        }

    @Test
    fun transitivity() =
        propTest {
            checkAll(PropTestConfig(iterations = 500), hlcArb, hlcArb, hlcArb) { a, b, c ->
                val sorted = listOf(a, b, c).sorted()
                // sorting stability: pairwise order consistent
                require(sorted[0] <= sorted[1] && sorted[1] <= sorted[2])
                if (a <= b && b <= c) require(a <= c) { "transitivity violated" }
            }
        }

    @Test
    fun sortingIsATotalOrderNoAmbiguousTies() =
        propTest {
            checkAll(PropTestConfig(iterations = 300), Arb.long()) { seed ->
                val rng = kotlin.random.Random(seed)
                val list =
                    (0 until 40).map {
                        Hlc(rng.nextLong(0, 6), rng.nextInt(0, 4), listOf("a", "b", "c", "d")[rng.nextInt(4)])
                    }
                val sorted = list.sorted()
                // adjacent elements are ordered; ties only for structurally-equal HLCs
                for (i in 1 until sorted.size) {
                    val cmp = sorted[i - 1].compareTo(sorted[i])
                    require(cmp <= 0)
                    if (cmp == 0) require(sorted[i - 1] == sorted[i])
                }
            }
        }
}
