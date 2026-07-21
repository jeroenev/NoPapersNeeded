package dev.npn.core.rules

/**
 * Matches an [AutoMatchRule] against a document text string.
 *
 * All algorithms, including regular expressions, are case-insensitive. Invalid regular
 * expressions never match.
 */
object RuleMatcher {

    /** Minimum fuzzy partial-ratio score (0-100) to count as a match. */
    private const val FUZZY_THRESHOLD = 60

    fun matches(rule: AutoMatchRule, text: String?): Boolean {
        if (text == null) return false
        val pattern = rule.pattern.trim()
        if (pattern.isEmpty()) return false
        val haystack = text.trim()
        if (haystack.isEmpty()) return false

        return when (rule.algorithm) {
            MatchAlgorithm.EXACT -> haystack.contains(pattern, ignoreCase = true)
            MatchAlgorithm.ANY_WORD -> wordsOf(pattern).any { wordMatches(it, haystack) }
            MatchAlgorithm.ALL_WORDS -> wordsOf(pattern).all { wordMatches(it, haystack) }
            MatchAlgorithm.REGEX -> regexMatches(pattern, haystack)
            MatchAlgorithm.FUZZY -> FuzzyMatcher.partialRatio(pattern, haystack) >= FUZZY_THRESHOLD
        }
    }

    private fun wordsOf(pattern: String): List<String> =
        pattern.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun wordMatches(word: String, text: String): Boolean {
        if (word.isEmpty()) return false
        val regex = "(?<!\\w)${Regex.escape(word)}(?!\\w)".toRegex(RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(text)
    }

    private fun regexMatches(pattern: String, text: String): Boolean = try {
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    } catch (_: IllegalArgumentException) {
        false
    }
}

/**
 * Simple Levenshtein-based partial ratio matcher. No external dependencies.
 */
internal object FuzzyMatcher {

    fun partialRatio(pattern: String, text: String): Int {
        val p = pattern.lowercase().trim()
        val t = text.lowercase().trim()
        if (p.isEmpty() || t.isEmpty()) return 0
        if (p in t) return 100

        val window = p.length
        if (window > t.length) return ratio(p, t)

        var best = 0
        for (start in 0..t.length - window) {
            val sub = t.substring(start, start + window)
            val score = ratio(p, sub)
            if (score > best) best = score
        }
        return best
    }

    private fun ratio(a: String, b: String): Int {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 100
        val dist = levenshtein(a, b)
        return ((maxLen - dist).toFloat() / maxLen * 100).toInt().coerceIn(0, 100)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost,
                )
            }
            for (j in prev.indices) prev[j] = curr[j]
        }
        return prev[b.length]
    }
}
