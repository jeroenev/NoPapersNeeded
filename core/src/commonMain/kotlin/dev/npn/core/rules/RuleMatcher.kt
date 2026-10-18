package dev.npn.core.rules

/**
 * Matches an [AutoMatchRule] against a document text string.
 *
 * All algorithms, including regular expressions, are case-insensitive. Invalid regular
 * expressions never match. [compile] parses a rule's pattern once, so a pass over many documents
 * does not build the same expressions again for each.
 */
object RuleMatcher {
    /** For FUZZY: how much of the pattern, in percent, a matching stretch must leave unedited. */
    private const val FUZZY_THRESHOLD = 60

    private val WHITESPACE = Regex("\\s+")

    /** [rule] with its pattern parsed: words, expressions and the fuzzy pattern are built here, once. */
    fun compile(rule: AutoMatchRule): CompiledRule {
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return CompiledRule(rule) { false }
        val test: (String) -> Boolean =
            when (rule.algorithm) {
                MatchAlgorithm.EXACT -> {
                    { text -> text.contains(pattern, ignoreCase = true) }
                }

                MatchAlgorithm.ANY_WORD -> {
                    val words = wordPatterns(pattern)
                    ({ text -> words.any { it.containsMatchIn(text) } })
                }

                MatchAlgorithm.ALL_WORDS -> {
                    val words = wordPatterns(pattern)
                    ({ text -> words.all { it.containsMatchIn(text) } })
                }

                MatchAlgorithm.REGEX -> {
                    val regex = regexOrNull(pattern)
                    ({ text -> regex?.containsMatchIn(text) == true })
                }

                MatchAlgorithm.FUZZY -> {
                    FuzzyMatcher(pattern, FUZZY_THRESHOLD)::matches
                }
            }
        return CompiledRule(rule, test)
    }

    /** One whole-word, case-insensitive expression per word of [pattern]. */
    private fun wordPatterns(pattern: String): List<Regex> =
        pattern
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { "(?<!\\w)${Regex.escape(it)}(?!\\w)".toRegex(RegexOption.IGNORE_CASE) }

    private fun regexOrNull(pattern: String): Regex? =
        try {
            Regex(pattern, RegexOption.IGNORE_CASE)
        } catch (_: IllegalArgumentException) {
            null
        }
}

/** A rule whose pattern [RuleMatcher.compile] has parsed, ready to match any number of texts. */
class CompiledRule internal constructor(
    val rule: AutoMatchRule,
    private val test: (String) -> Boolean,
) {
    /** Whether [text] matches; a missing or blank text never does. */
    fun matches(text: String?): Boolean {
        val haystack = text?.trim()
        return !haystack.isNullOrEmpty() && test(haystack)
    }
}

/**
 * Levenshtein partial matching: can some stretch of a text be edited into the pattern while
 * leaving at least [threshold] percent of the pattern untouched? No external dependencies.
 *
 * The threshold is an edit bound, [maxDistance], and any stretch within it is the pattern's length
 * give or take that bound. One pass over the text finds the best such stretch (Sellers' algorithm),
 * computing each column only as far down the pattern as can still come within the bound
 * (Ukkonen's cut-off): linear in the text, where trying every pattern-long window with a full edit
 * distance cost the text length times the pattern length squared. Every window that search
 * accepted is a stretch this one accepts.
 */
internal class FuzzyMatcher(
    pattern: String,
    threshold: Int,
) {
    private val p = pattern.lowercase().trim()

    /** The most edits a stretch may need and still leave [threshold] percent of the pattern. */
    private val maxDistance = (0..p.length).lastOrNull { score(p.length, it) >= threshold } ?: 0

    fun matches(text: String): Boolean {
        val t = text.lowercase().trim()
        if (p.isEmpty() || t.isEmpty()) return false
        if (p in t) return true
        return anyStretchWithinDistance(t)
    }

    /**
     * [column] holds, per pattern prefix, the fewest edits that turn it into a stretch of the text
     * ending at the current character. Only the rows down to [active] can still be within the
     * bound; the rows below it hold values past the bound and are not recomputed.
     */
    private fun anyStretchWithinDistance(t: String): Boolean {
        val m = p.length
        val k = maxDistance
        val column = IntArray(m + 1) { it }
        var active = minOf(k + 1, m)
        for (c in t) {
            var diagonal = 0
            var current = 0
            for (i in 1..active) {
                current = if (p[i - 1] == c) diagonal else minOf(diagonal, column[i], current) + 1
                diagonal = column[i]
                column[i] = current
            }
            while (column[active] > k) active--
            if (active == m) return true
            active++
        }
        return false
    }

    private companion object {
        /** The share of a pattern [length] long, in percent, that [distance] edits leave untouched. */
        fun score(
            length: Int,
            distance: Int,
        ): Int = ((length - distance).toFloat() / length * 100).toInt().coerceIn(0, 100)
    }
}
