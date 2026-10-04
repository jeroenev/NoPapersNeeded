package dev.npn.core.rules

import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class FuzzyMatcherTest {
    /**
     * The one-pass search must decide as trying every stretch of the text would: is some stretch
     * within the edit bound of the pattern? A small alphabet makes near misses common, which is
     * where a cut-off that stopped too early would show.
     */
    @Test
    fun decidesLikeTryingEveryStretch() {
        val random = Random(SEED)
        repeat(CASES) {
            val pattern = randomText(random, random.nextInt(1, 12))
            val text = randomText(random, random.nextInt(0, 30))
            val p = pattern.lowercase().trim()
            val t = text.lowercase().trim()
            val expected = p.isNotEmpty() && t.isNotEmpty() && bestStretchDistance(p, t) <= maxDistance(p.length)
            withClue(pattern, text) { FuzzyMatcher(pattern, THRESHOLD).matches(text) shouldBe expected }
        }
    }

    /** Whatever the pattern-long window search it replaced matched, it still matches. */
    @Test
    fun matchesEverythingTheWindowSearchMatched() {
        val random = Random(SEED + 1)
        var windowMatches = 0
        repeat(CASES) {
            val pattern = randomText(random, random.nextInt(1, 12))
            val text = randomText(random, random.nextInt(0, 40))
            if (windowPartialRatio(pattern, text) >= THRESHOLD) {
                windowMatches++
                withClue(pattern, text) { FuzzyMatcher(pattern, THRESHOLD).matches(text) shouldBe true }
            }
        }
        (windowMatches > CASES / 4) shouldBe true
    }

    @Test
    fun matchesAcrossCaseAndSurroundingSpace() {
        FuzzyMatcher("  Electricity ", THRESHOLD).matches("  monthly ELECTRCTY bill") shouldBe true
        FuzzyMatcher("electricity", THRESHOLD).matches("water bill") shouldBe false
        FuzzyMatcher("electricity", THRESHOLD).matches("electric") shouldBe true
        FuzzyMatcher("electricity", THRESHOLD).matches("") shouldBe false
    }

    /**
     * A long OCR text is matched in one pass over it. The exhaustive search ran a full edit distance
     * for every position, pattern length squared each; at this size that took tens of seconds per
     * rule and document.
     */
    @Test
    fun aLongTextIsMatchedInOnePass() {
        val random = Random(SEED)
        val words = listOf("invoice", "total", "amount", "due", "payment", "reference", "account", "period")
        val text = buildString { while (length < LONG_TEXT) append(words[random.nextInt(words.size)]).append(' ') }
        val matcher = FuzzyMatcher("annual electricity supplier statement for the household account", THRESHOLD)

        var matched = true
        val elapsed = measureTime { matched = matcher.matches(text) }

        matched shouldBe false
        (elapsed < 10.seconds) shouldBe true
    }

    private fun randomText(
        random: Random,
        length: Int,
    ): String = buildString { repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    private inline fun withClue(
        pattern: String,
        text: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue("pattern=\"$pattern\" text=\"$text\"") { block() }

    /** The fewest edits that turn some stretch of [t], the empty one included, into [p]. */
    private fun bestStretchDistance(
        p: String,
        t: String,
    ): Int {
        var best = p.length
        for (start in t.indices) {
            for (end in start + 1..t.length) best = minOf(best, levenshtein(p, t.substring(start, end)))
        }
        return best
    }

    private fun maxDistance(length: Int): Int =
        (0..length).last { ((length - it).toFloat() / length * 100).toInt() >= THRESHOLD }

    /** The search this replaced: the best Levenshtein ratio of any pattern-long window. */
    private fun windowPartialRatio(
        pattern: String,
        text: String,
    ): Int {
        val p = pattern.lowercase().trim()
        val t = text.lowercase().trim()
        if (p.isEmpty() || t.isEmpty()) return 0
        if (p in t) return 100
        val window = p.length
        if (window > t.length) return ratio(p, t)
        var best = 0
        for (start in 0..t.length - window) {
            best = maxOf(best, ratio(p, t.substring(start, start + window)))
        }
        return best
    }

    private fun ratio(
        a: String,
        b: String,
    ): Int {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 100
        return ((maxLen - levenshtein(a, b)).toFloat() / maxLen * 100).toInt().coerceIn(0, 100)
    }

    private fun levenshtein(
        a: String,
        b: String,
    ): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private companion object {
        const val THRESHOLD = 60
        const val SEED = 20261004
        const val CASES = 3_000
        const val ALPHABET = "abcAB "
        const val LONG_TEXT = 4_000_000
    }
}
