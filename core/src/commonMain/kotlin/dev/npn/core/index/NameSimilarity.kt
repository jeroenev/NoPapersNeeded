package dev.npn.core.index

/**
 * Small, dependency-free name similarity helpers for relink candidate scoring.
 */
internal object NameSimilarity {
    /** True if either name is a prefix of the other after stripping the extension (case-insensitive). */
    fun prefixMatch(
        a: String,
        b: String,
    ): Boolean {
        val x = stripExt(a).lowercase()
        val y = stripExt(b).lowercase()
        if (x.isEmpty() || y.isEmpty()) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    /**
     * Levenshtein edit distance, bounded early once it exceeds [max] (returns [max]+1). Used only
     * on short file names, so the full O(n*m) table is fine.
     */
    fun levenshtein(
        a: String,
        b: String,
        max: Int = Int.MAX_VALUE,
    ): Int {
        val s = a.lowercase()
        val t = b.lowercase()
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

    /** True when [a] and [b] are within a small edit distance relative to their length. */
    fun fuzzyMatch(
        a: String,
        b: String,
    ): Boolean {
        val x = stripExt(a)
        val y = stripExt(b)
        val threshold = maxOf(2, minOf(x.length, y.length) / 4)
        return levenshtein(x, y, threshold) <= threshold
    }

    private fun stripExt(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    fun parentDir(relPath: String): String {
        val slash = relPath.lastIndexOf('/')
        return if (slash >= 0) relPath.substring(0, slash) else ""
    }
}
