// Search highlighting as a whole, not its one class (HighlightSegment), names this file.
@file:Suppress("MatchingDeclarationName")

package dev.npn.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * One segment of text with an associated highlight flag. Produced by parsing FTS5 snippet
 * markers or matching query terms against plain text. [current] marks the one match a find bar is
 * on, drawn apart from the others.
 */
data class HighlightSegment(
    val text: String,
    val highlight: Boolean,
    val current: Boolean = false,
)

/**
 * Parse an FTS5 snippet that wraps matched terms in [startMarker] and [endMarker]
 * (defaults to `[` and `]` as configured in [dev.npn.core.db.FtsDao.search]).
 */
fun parseFtsSnippet(
    snippet: String,
    startMarker: String = "\u0001",
    endMarker: String = "\u0002",
): List<HighlightSegment> {
    val segments = mutableListOf<HighlightSegment>()
    var remaining = snippet
    val startLen = startMarker.length
    val endLen = endMarker.length
    while (remaining.isNotEmpty()) {
        val start = remaining.indexOf(startMarker)
        if (start < 0) {
            segments += HighlightSegment(remaining, false)
            remaining = ""
        } else {
            if (start > 0) {
                segments += HighlightSegment(remaining.substring(0, start), false)
            }
            val end = remaining.indexOf(endMarker, start + startLen)
            if (end < 0) {
                segments += HighlightSegment(remaining.substring(start), false)
                remaining = ""
            } else {
                segments +=
                    HighlightSegment(
                        remaining.substring(start + startLen, end),
                        true,
                    )
                remaining = remaining.substring(end + endLen)
            }
        }
    }
    return segments
}

/**
 * Tokenize [query] into non-empty search terms. Terms are lowercased and deduplicated.
 */
fun queryTerms(query: String): List<String> =
    TOKEN_REGEX
        .findAll(query)
        .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .toList()

private val TOKEN_REGEX = Regex("\"([^\"]+)\"|([^\\s\"]+)")

/** The token currently being typed, excluding an unmatched opening quote. */
fun activeSearchToken(query: String): String = query.substring(activeTokenStart(query)).trim().removePrefix("\"")

/** Replace only the token currently being typed, preserving previous terms and quote style. */
fun completeActiveSearchToken(
    query: String,
    suggestion: String,
): String {
    val tokenStart = activeTokenStart(query)
    val quoted = query.getOrNull(tokenStart) == '"'
    val replacement = if (quoted) "\"$suggestion\"" else suggestion
    return query.substring(0, tokenStart) + replacement
}

private fun activeTokenStart(query: String): Int {
    val quote = query.lastIndexOf('"')
    val hasOpenQuote = query.count { it == '"' } % 2 != 0
    return if (hasOpenQuote) quote else query.indexOfLast { it.isWhitespace() } + 1
}

/**
 * Split [text] into segments, marking each occurrence of any [queryTerms] as highlighted.
 * Matching is case-insensitive and respects the original casing of the matched text.
 */
fun parseQueryHighlight(
    text: String,
    query: String,
): List<HighlightSegment> {
    val terms = queryTerms(query)
    if (terms.isEmpty()) {
        return listOf(HighlightSegment(text, false))
    }
    val pattern = terms.joinToString("|") { Regex.escape(it) }.toRegex(RegexOption.IGNORE_CASE)
    val segments = mutableListOf<HighlightSegment>()
    var last = 0
    for (match in pattern.findAll(text)) {
        if (match.range.first > last) {
            segments += HighlightSegment(text.substring(last, match.range.first), false)
        }
        segments += HighlightSegment(match.value, true)
        last = match.range.last + 1
    }
    if (last < text.length) {
        segments += HighlightSegment(text.substring(last), false)
    }
    return segments
}

/**
 * Render [segments] as a single [Text] with highlighted spans on the current theme's primary
 * container color, and the [HighlightSegment.current] match on its primary color.
 */
@Composable
fun HighlightText(
    segments: List<HighlightSegment>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTextLayout: (TextLayoutResult) -> Unit = {},
) {
    val highlight = SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)
    val current =
        SpanStyle(background = MaterialTheme.colorScheme.primary, color = MaterialTheme.colorScheme.onPrimary)
    Text(
        text =
            buildAnnotatedString {
                segments.forEach { segment ->
                    when {
                        segment.current -> withStyle(current) { append(segment.text) }
                        segment.highlight -> withStyle(highlight) { append(segment.text) }
                        else -> append(segment.text)
                    }
                }
            },
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = onTextLayout,
    )
}
