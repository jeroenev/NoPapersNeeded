package dev.npn.core.db

/** Lightweight projection used by the indexer diff (path + identity + fast-path fields). */
data class PathMeta(
    val relPath: String,
    val docId: String,
    val size: Long,
    val mtime: Long,
    /** Trashed or missing: its stored size and mtime say nothing about the file at its path now. */
    val hidden: Boolean = false,
)

/** A live document's trash time ([DocumentEntity.trashedAt]), null while it is not in trash. */
data class DocTrashTime(
    val docId: String,
    val trashedAt: Long?,
)

/** A field key with the number of documents carrying a value for it. */
data class FieldKeyCount(
    val fieldKey: String,
    val count: Int,
)

/** Tag with its live document count, for the tag management screen. */
data class TagWithCount(
    val name: String,
    val color: String?,
    val icon: String?,
    val count: Int,
)

/**
 * Just enough of a document row to name it: the title/file-name pair every display title is
 * derived from. Used by views that start from some other table's doc ids and only need a label.
 */
data class DocTitle(
    val docId: String,
    val title: String?,
    val fileName: String,
)

/** A single full-text search hit with a highlighted snippet fragment. */
data class FtsHit(
    val docId: String,
    val snippet: String,
)
