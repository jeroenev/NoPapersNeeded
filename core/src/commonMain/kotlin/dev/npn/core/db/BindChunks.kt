package dev.npn.core.db

/** The most ids one `IN (:ids)` list binds, well under SQLite's bind-variable limit. */
internal const val BIND_CHUNK = 500

/**
 * Run [query] over these ids in slices of [BIND_CHUNK], so an `IN (:ids)` list stays under SQLite's
 * bind-variable limit however many ids there are.
 */
internal suspend fun <T> List<String>.chunkedBind(query: suspend (List<String>) -> List<T>): List<T> {
    if (isEmpty()) return emptyList()
    if (size <= BIND_CHUNK) return query(this)
    val out = ArrayList<T>(size)
    for (chunk in chunked(BIND_CHUNK)) out += query(chunk)
    return out
}
