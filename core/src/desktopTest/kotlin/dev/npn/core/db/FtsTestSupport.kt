package dev.npn.core.db

/** The documents [FtsDao.search] finds for [query], best match first, without their snippets. */
suspend fun FtsDao.searchDocIds(query: String): List<String> = search(query).map { it.docId }
