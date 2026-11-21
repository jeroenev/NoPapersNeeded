package dev.npn.core.index

import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test

/** The digest kept per change always equals the one read from every row, however the rows moved. */
class AppliedSourceSetTest {
    /** The table, as a map from path to row. */
    private val table = HashMap<String, MetadataSourceEntity>()

    private suspend fun AppliedSourceSet.current() =
        fingerprint(all = { table.values.toList() }, at = { paths -> paths.mapNotNull(table::get) })

    /** What a fresh session, which knows nothing yet, computes from every row. */
    private suspend fun fromEveryRow() = AppliedSourceSet().current()

    private fun row(
        path: String,
        hash: String,
        status: SourceStatus = SourceStatus.APPLIED,
    ) = MetadataSourceEntity(path, SourceKind.OBJECT, SourceFingerprint(1L, 2L, hash), hash, status)

    @Test
    fun keptPerChangeItMatchesEveryRowReadAgain() =
        runTest {
            val random = Random(7)
            val set = AppliedSourceSet()
            set.current()
            repeat(STEPS) { step ->
                val path = "p${random.nextInt(PATHS)}"
                set.changing(path, table[path])
                when (random.nextInt(4)) {
                    0 -> table.remove(path)
                    1 -> table[path] = row(path, "h$step", SourceStatus.RETRYABLE)
                    else -> table[path] = row(path, "h$step")
                }
                if (random.nextInt(3) == 0) set.current() shouldBe fromEveryRow()
            }
            set.current() shouldBe fromEveryRow()
        }

    /** A write whose transaction rolled back changes nothing: the row is read back as it still is. */
    @Test
    fun aChangeThatNeverLandedCancelsOut() =
        runTest {
            table["a"] = row("a", "h1")
            val set = AppliedSourceSet()
            val before = set.current()

            set.changing("a", table["a"])
            set.changing("b", table["b"])
            // Neither write lands.

            set.current() shouldBe before
        }

    @Test
    fun anyChangeToAnAppliedSourceMovesIt() =
        runTest {
            table["a"] = row("a", "h1")
            val set = AppliedSourceSet()
            val first = set.current()

            set.changing("a", table["a"])
            table["a"] = row("a", "h2")
            val second = set.current()
            second shouldNotBe first

            set.changing("a", table["a"])
            table["a"] = row("a", "h2", SourceStatus.RETRYABLE)
            set.current() shouldNotBe second
            set.current() shouldBe AppliedSourceSet().fingerprint(all = { emptyList() }, at = { emptyList() })
        }

    @Test
    fun forgettingReadsEveryRowAgain() =
        runTest {
            table["a"] = row("a", "h1")
            val set = AppliedSourceSet()
            set.current()

            set.forget()
            // Unrecorded, as a cleared accumulator's rows are.
            table.clear()
            table["b"] = row("b", "h2")

            set.current() shouldBe fromEveryRow()
        }

    private companion object {
        const val STEPS = 2_000
        const val PATHS = 40
    }
}
