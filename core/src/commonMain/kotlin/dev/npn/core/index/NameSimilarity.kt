package dev.npn.core.index

import dev.npn.core.fs.NpnPaths
import kotlin.math.abs

/**
 * Small, dependency-free name similarity helpers for relink candidate scoring.
 */
internal object NameSimilarity {
    /** What [similar] compares of a file name: the name without its extension, in lower case. */
    fun stem(name: String): String = NpnPaths.stripPdfExtension(name).lowercase()

    /**
     * True when two [stem]s are alike: one is a prefix of the other, or they are within a small edit
     * distance relative to their length.
     */
    fun similar(
        x: String,
        y: String,
    ): Boolean = prefixMatch(x, y) || fuzzyMatch(x, y)

    private fun prefixMatch(
        x: String,
        y: String,
    ): Boolean {
        if (x.isEmpty() || y.isEmpty()) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    private fun fuzzyMatch(
        x: String,
        y: String,
    ): Boolean {
        val threshold = maxOf(2, minOf(x.length, y.length) / 4)
        // The edit distance is at least the length difference, so this decides most pairs cheaply.
        if (abs(x.length - y.length) > threshold) return false
        return levenshtein(x, y, threshold) <= threshold
    }

    /**
     * Levenshtein edit distance, bounded early once it exceeds [max] (returns [max]+1). Used only
     * on short file names, so the full O(n*m) table is fine.
     */
    private fun levenshtein(
        s: String,
        t: String,
        max: Int,
    ): Int {
        if (s == t) return 0
        if (s.isEmpty()) return t.length
        if (t.isEmpty()) return s.length
        var prev = IntArray(t.length + 1) { it }
        var cur = IntArray(t.length + 1)
        for (i in 1..s.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..t.length) {
                val cost = if (s[i - 1] == t[j - 1]) 0 else 1
                cur[j] =
                    minOf(
                        prev[j] + 1,
                        cur[j - 1] + 1,
                        prev[j - 1] + cost,
                    )
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[t.length]
    }
}
