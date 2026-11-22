package dev.npn.app.ui.palette

/** How many results one group shows. */
internal const val PALETTE_GROUP_LIMIT = 8

/**
 * How well [text] answers [query], higher first: an exact prefix beats a word that starts with it,
 * which beats an occurrence inside a word, which beats a fuzzy in-order match. 0 means no match.
 * A multi-word query matches when every word does, at the weakest word's score.
 */
internal fun paletteMatchScore(
    query: String,
    text: String,
): Int {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return 0
    val t = text.lowercase()
    val direct = phraseScore(q, t)
    if (direct > 0) return direct
    val words = q.split(' ', '\t', '\n').filter { it.isNotEmpty() }
    if (words.size < 2) return 0
    val scores = words.map { phraseScore(it, t) }
    return if (scores.any { it == 0 }) 0 else scores.min()
}

private fun phraseScore(
    q: String,
    t: String,
): Int {
    if (t.startsWith(q)) return SCORE_PREFIX
    if (startsAWord(q, t)) return SCORE_WORD
    if (t.contains(q)) return SCORE_SUBSTRING
    return if (isSubsequence(q, t)) SCORE_FUZZY else 0
}

/** Whether [q] starts at the beginning of a word of [t] (after a space or punctuation, not mid-word). */
private fun startsAWord(
    q: String,
    t: String,
): Boolean {
    var index = t.indexOf(q)
    while (index >= 0) {
        if (index == 0 || !t[index - 1].isLetterOrDigit()) return true
        index = t.indexOf(q, index + 1)
    }
    return false
}

/** Whether every character of [q] appears in [t] in order, far enough apart to be a real match. */
private fun isSubsequence(
    q: String,
    t: String,
): Boolean {
    if (q.length < 2) return false
    var from = 0
    for (char in q) {
        val at = t.indexOf(char, from)
        if (at < 0) return false
        from = at + 1
    }
    return true
}

internal const val SCORE_PREFIX = 4
internal const val SCORE_WORD = 3
internal const val SCORE_SUBSTRING = 2
internal const val SCORE_FUZZY = 1

private const val NO_RECENT = Int.MAX_VALUE

private class Scored<T>(
    val value: T,
    val score: Int,
    val recent: Int,
    val title: String,
    val index: Int,
)

/**
 * [items] that answer [query], best first and at most [limit]: a prefix match, then a word match,
 * then a substring, then a fuzzy one, with a recently chosen item ahead of an equally good one.
 * With a blank query every item is kept, recently chosen ones first and the rest in their given
 * order. [includeUnmatched] keeps items whose text does not match (a document the full-text index
 * found by its contents rather than by its title), after every textual match.
 */
internal fun <T> rankPalette(
    query: String,
    items: List<T>,
    recents: List<String> = emptyList(),
    includeUnmatched: Boolean = false,
    limit: Int = PALETTE_GROUP_LIMIT,
    key: (T) -> String,
    text: (T) -> String,
): List<T> {
    if (limit <= 0) return emptyList()
    val ranking = recents.withIndex().associate { it.value to it.index }
    val scored =
        items.mapIndexedNotNull { index, item ->
            val score = paletteMatchScore(query, text(item))
            if (score == 0 && query.isNotBlank() && !includeUnmatched) {
                null
            } else {
                Scored(item, score, ranking[key(item)] ?: NO_RECENT, text(item), index)
            }
        }
    return scored
        .sortedWith(
            if (query.isBlank()) {
                compareBy<Scored<T>> { it.recent }.thenBy { it.index }
            } else {
                compareByDescending<Scored<T>> { it.score }
                    .thenBy { it.recent }
                    // Textual matches read best by their title; items kept only because their
                    // source matched them (a full-text hit) stay in the source's own order.
                    .thenBy { if (it.score == 0) it.index else 0 }
                    .thenBy { if (it.score == 0) 0 else it.title.length }
                    .thenBy { if (it.score == 0) "" else it.title.lowercase() }
                    .thenBy { it.index }
            },
        ).take(limit)
        .map { it.value }
}
