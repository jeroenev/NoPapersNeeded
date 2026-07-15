package dev.npn.core.db

/** Lightweight projection used by the indexer diff (path + identity + fast-path fields). */
data class PathMeta(
    val relPath: String,
    val docId: String,
    val size: Long,
    val mtime: Long,
)

/** Tag with its live document count, for the tag management screen. */
data class TagWithCount(
    val name: String,
    val color: String?,
    val icon: String?,
    val count: Int,
)

/** A single full-text search hit with a highlighted snippet fragment. */
data class FtsHit(
    val docId: String,
    val snippet: String,
)
