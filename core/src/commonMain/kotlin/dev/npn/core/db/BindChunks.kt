package dev.npn.core.db

/**
 * Run [query] over [ids] in slices of [DocumentDao.BIND_CHUNK], so an `IN (:ids)` list stays under
 * SQLite's bind-variable limit however many ids there are.
 */
internal suspend fun <T> inBindChunks(
    ids: List<String>,
    query: suspend (List<String>) -> List<T>,
): List<T> {
    if (ids.isEmpty()) return emptyList()
    if (ids.size <= DocumentDao.BIND_CHUNK) return query(ids)
    val out = ArrayList<T>(ids.size)
    for (start in ids.indices step DocumentDao.BIND_CHUNK) {
        out += query(ids.subList(start, minOf(start + DocumentDao.BIND_CHUNK, ids.size)))
    }
    return out
}
