package dev.npn.core.app

import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import dev.npn.core.db.LIVE_DOCUMENT
import dev.npn.core.util.NpnJson

/**
 * The library list's SQL: what a [LibraryFilter] and a [LibrarySort] make of the `documents`
 * query [DocumentRepositoryImpl.observeDocuments] observes. Pure: it builds the statement and its
 * binds, and reads nothing.
 */
internal object LibraryQuerySql {
    /**
     * Build the filtered library query. Base predicate excludes trashed/deleted/missing.
     * Tag AND/ANY/NONE and field equals/range are EXISTS subqueries on their join tables; folder
     * is a relPath LIKE prefix; untaggedOnly uses NOT EXISTS; missingFields becomes one OR-ed
     * "no field row" group.
     * Query text (FTS) is handled by [DocumentRepositoryImpl.observeDocuments]'s two steps, so it is
     * not part of this SQL beyond the id constraint being applied in memory.
     *
     * An id filter drives the query instead of narrowing it: its ids, bound as one JSON array however
     * many there are (a selection can pass SQLite's bind-variable limit), each look their row up by
     * primary key. CROSS JOIN keeps them the outer loop; as a `docId IN (...)` term, the planner reads
     * every live row through the trashed/deleted/missing indices, which it takes for selective.
     */
    fun build(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): RoomRawQuery {
        val parts = buildWhere(filter)
        val ids = filter.docIds.takeIf { it.isNotEmpty() }?.let { NpnJson.Persisted.encodeToString(it.distinct()) }
        val join = sortJoinSql(sort)
        val sql =
            buildString {
                append("SELECT d.* FROM ")
                if (ids != null) append("json_each(?) ids CROSS JOIN ")
                append("documents d")
                join?.let(::append)
                append(" WHERE ")
                if (ids != null) append("d.docId = ids.value AND ")
                append(parts.where.joinToString(" AND "))
                append(" ORDER BY ")
                append(orderBySql(sort))
            }
        // The ids and the join precede every WHERE bind in the statement, so they are bound first.
        val sortKey = (sort.field as? SortField.Field)?.key
        return RoomRawQuery(sql = sql, onBindStatement = { st ->
            var index = 1
            if (ids != null) st.bindText(index++, ids)
            if (sortKey != null) st.bindText(index++, sortKey)
            parts.binds.forEach { bind -> bind(st, index++) }
        })
    }

    /** WHERE clause + bind lambdas of the list query. */
    private fun buildWhere(filter: LibraryFilter): QueryParts {
        val where = ArrayList<String>()
        val binds = ArrayList<(SQLiteStatement, Int) -> Unit>()

        fun bindText(v: String) {
            binds.add { st, i -> st.bindText(i, v) }
        }

        fun bindLong(v: Long) {
            binds.add { st, i -> st.bindLong(i, v) }
        }

        where.add(LIVE_DOCUMENT)

        for (tag in filter.tagsAll) {
            where.add("EXISTS (SELECT 1 FROM document_tags dt WHERE dt.docId = d.docId AND dt.tag = ?)")
            bindText(tag)
        }
        if (filter.tagsAny.isNotEmpty()) {
            val placeholders = filter.tagsAny.joinToString(", ") { "?" }
            where.add(
                "EXISTS (SELECT 1 FROM document_tags dt WHERE dt.docId = d.docId AND dt.tag IN ($placeholders))",
            )
            filter.tagsAny.forEach { bindText(it) }
        }
        for (tag in filter.tagsNone) {
            where.add("NOT EXISTS (SELECT 1 FROM document_tags dt WHERE dt.docId = d.docId AND dt.tag = ?)")
            bindText(tag)
        }
        if (filter.untaggedOnly) {
            where.add("NOT EXISTS (SELECT 1 FROM document_tags dt WHERE dt.docId = d.docId)")
        }
        if (filter.unreviewedOrUntagged) {
            where.add(
                "(d.reviewed = 0 OR NOT EXISTS (SELECT 1 FROM document_tags dt WHERE dt.docId = d.docId))",
            )
        }
        filter.reviewed?.let {
            where.add("d.reviewed = ?")
            bindLong(if (it) 1L else 0L)
        }
        addFieldPredicates(filter, where, ::bindText)
        filter.folder?.takeIf { it.isNotBlank() }?.let { folder ->
            val prefix = folder.trimEnd('/') + "/"
            where.add("d.relPath LIKE ? ESCAPE '\\'")
            bindText(escapeLike(prefix) + "%")
        }
        return QueryParts(where, binds)
    }

    /**
     * The `document_fields` predicates: equality, ranges and "missing". Each is an EXISTS on the
     * field table keyed by docId, the same shape as the tag predicates above.
     */
    private fun addFieldPredicates(
        filter: LibraryFilter,
        where: MutableList<String>,
        bindText: (String) -> Unit,
    ) {
        for ((key, value) in filter.fieldEquals) {
            where.add(
                "EXISTS (SELECT 1 FROM document_fields f WHERE f.docId = d.docId " +
                    "AND f.fieldKey = ? AND f.fieldValue = ?)",
            )
            bindText(key)
            bindText(value)
        }
        for ((key, range) in filter.fieldRanges) {
            // A NUMBER/DECIMAL row carries numValue, so it compares numerically; every other row
            // (dates, text, off-type values) compares on the canonical string, which for ISO dates
            // is chronological.
            val bounds = ArrayList<String>()
            range.from?.let { bounds.add(boundSql(">=")) }
            range.to?.let { bounds.add(boundSql("<=")) }
            if (bounds.isEmpty()) continue
            where.add(
                "EXISTS (SELECT 1 FROM document_fields f WHERE f.docId = d.docId AND f.fieldKey = ? AND " +
                    bounds.joinToString(" AND ") + ")",
            )
            bindText(key)
            range.from?.let {
                bindText(it)
                bindText(it)
            }
            range.to?.let {
                bindText(it)
                bindText(it)
            }
        }
        val missing = filter.missingFields.distinct()
        if (missing.isNotEmpty()) {
            // A cleared field has no row, so "missing" is exactly "no row".
            where.add(
                missing.joinToString(separator = " OR ", prefix = "(", postfix = ")") {
                    "NOT EXISTS (SELECT 1 FROM document_fields f WHERE f.docId = d.docId AND f.fieldKey = ?)"
                },
            )
            missing.forEach(bindText)
        }
    }

    private fun boundSql(op: String): String =
        "(CASE WHEN f.numValue IS NOT NULL THEN f.numValue $op CAST(? AS REAL) ELSE f.fieldValue $op ? END)"

    /**
     * A field sort joins its rows once per document. A LEFT JOIN, not a correlated subquery in
     * ORDER BY: the (fieldKey, fieldValue)/(fieldKey, numValue) indices serve it, and documents
     * without the field sort last in either direction.
     */
    private fun sortJoinSql(sort: LibrarySort): String? =
        (sort.field as? SortField.Field)?.let {
            " LEFT JOIN document_fields sf ON sf.docId = d.docId AND sf.fieldKey = ?"
        }

    /**
     * Every sort ends in tiebreakers that run the same direction, down to the unique docId, so the
     * order is total and flipping the direction reverses the tied rows too. Without them SQLite
     * leaves tied rows in its scan order whichever way the list is sorted. A field sort keeps its
     * empty values last either way.
     */
    private fun orderBySql(sort: LibrarySort): String {
        val dir = if (sort.ascending) "ASC" else "DESC"
        val primary =
            when (sort.field) {
                SortField.Name -> "d.fileName COLLATE NOCASE $dir"

                // A row whose PDF is not read yet sorts by its file's mtime meanwhile, as Doc shows it.
                SortField.Created -> "$CREATED_AT $dir"

                SortField.Size -> "d.size $dir"

                // Nulls last; numeric shadow decides for NUMBER/DECIMAL fields, the canonical string
                // (chronological for dates) for everything else.
                is SortField.Field -> "sf.fieldValue IS NULL, sf.numValue $dir, sf.fieldValue COLLATE NOCASE $dir"
            }
        val byName = if (sort.field == SortField.Name) "" else ", d.fileName COLLATE NOCASE $dir"
        return "$primary$byName, d.docId $dir"
    }

    /** The creation date a row sorts and shows by: [Doc.createdAt]. */
    private const val CREATED_AT = "COALESCE(d.createdAt, d.mtime)"

    /** [s] with LIKE's wildcards and its escape character escaped, for a `LIKE ? ESCAPE '\\'` pattern. */
    fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** The list query's WHERE terms, and its binds in the order their placeholders appear. */
    private data class QueryParts(
        val where: List<String>,
        val binds: List<(SQLiteStatement, Int) -> Unit>,
    )
}
