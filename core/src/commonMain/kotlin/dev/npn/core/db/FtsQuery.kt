package dev.npn.core.db

/** Shared helpers for building safe FTS5 MATCH expressions. */
object FtsQuery {

    /**
     * Sanitize a user query into a safe MATCH expression. FTS5 treats bareword tokens as an
     * implicit AND; wrapping each token in double quotes avoids syntax errors from stray
     * operator characters while still matching every term. A trailing `*` is added for prefix
     * matching on the last token to make search feel incremental.
     */
    fun sanitize(query: String): String {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "\"\""
        return tokens.mapIndexed { i, t ->
            val cleaned = t.replace("\"", "")
            if (cleaned.isEmpty()) "" else if (i == tokens.lastIndex) "\"$cleaned\"*" else "\"$cleaned\""
        }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "\"\"" }
    }
}
