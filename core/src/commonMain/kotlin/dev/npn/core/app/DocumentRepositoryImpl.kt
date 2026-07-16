package dev.npn.core.app

import androidx.room3.RoomRawQuery
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.NpnDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Read side of the library, built on [NpnDatabase]. Filtering/sorting SQL is assembled here and
 * handed to [dev.npn.core.db.DocumentDao.observeLibraryRaw] as a [RoomRawQuery] (its observed
 * entities cover both documents and document_tags, so tag changes re-emit).
 *
 * Two-step FTS join (design decision): when [LibraryFilter.query] is non-blank we cannot express
 * `MATCH` inside the RawQuery in a way Room verifies cleanly, so we first fetch matching docIds +
 * snippets from [FtsDao], constrain the main query to those ids, and re-attach the snippets in
 * memory. The FTS lookup runs inside [mapLatest] on each re-emission of the base flow, so it stays
 * live as underlying tables change (tag edits, new imports).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DocumentRepositoryImpl(
    private val db: NpnDatabase,
) : DocumentRepository {

    private val documentDao get() = db.documentDao()
    private val ftsDao: FtsDao get() = db.ftsDao()

    override fun observeDocuments(filter: LibraryFilter, sort: LibrarySort): Flow<List<DocListItem>> {
        val queryText = filter.query?.takeIf { it.isNotBlank() }
        val rawFlow = documentDao.observeLibraryRaw(buildQuery(filter, sort))
        return rawFlow.mapLatest { docs ->
            // Attach tags for all docs in one pass.
            val tagsByDoc = tagsFor(docs.map { it.docId })
            if (queryText == null) {
                docs.map { DocListItem(it, tagsByDoc[it.docId].orEmpty()) }
            } else {
                // Two-step FTS: fetch snippets, keep only matched ids, preserve FTS rank order.
                val hits = ftsDao.search(sanitizeFts(queryText))
                val snippetById = hits.associate { it.docId to it.snippet }
                val order = hits.map { it.docId }.withIndex().associate { it.value to it.index }
                docs.filter { it.docId in snippetById }
                    .sortedBy { order[it.docId] ?: Int.MAX_VALUE }
                    .map { DocListItem(it, tagsByDoc[it.docId].orEmpty(), snippetById[it.docId]) }
            }
        }
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> =
        documentDao.observeByDocId(docId).mapLatest { doc ->
            if (doc == null) null else DocListItem(doc, tagsFor(listOf(docId))[docId].orEmpty())
        }

    override fun observeUntaggedCount(): Flow<Int> = documentDao.countUntagged()

    override fun observeUnreviewedOrUntaggedCount(): Flow<Int> = documentDao.countUnreviewedOrUntagged()

    override fun observeTrashed(): Flow<List<DocListItem>> =
        documentDao.observeTrashed().mapLatest { docs ->
            val tags = tagsFor(docs.map { it.docId })
            docs.map { DocListItem(it, tags[it.docId].orEmpty()) }
        }

    override fun observeMissing(): Flow<List<DocumentEntity>> = documentDao.observeMissing()

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> =
        db.duplicateDao().observeAll().map { rows -> rows.map { it.docId to it.relPath } }

    override fun observeCorrespondents(): Flow<List<String>> = documentDao.observeCorrespondents()

    override fun observeDocTypes(): Flow<List<String>> = documentDao.observeDocTypes()

    private suspend fun tagsFor(docIds: List<String>): Map<String, List<String>> {
        if (docIds.isEmpty()) return emptyMap()
        return documentDao.tagsForDocs(docIds)
            .groupBy({ it.docId }, { it.tag })
            .mapValues { it.value.sorted() }
    }

    /**
     * Build the filtered/sorted library query. Base predicate excludes trashed/deleted/missing.
     * Tag AND/ANY/NONE are done with EXISTS subqueries; folder is a relPath LIKE prefix;
     * untaggedOnly uses NOT EXISTS. Query text (FTS) is handled by the two-step above, so it is not
     * part of this SQL beyond the id constraint being applied in memory.
     */
    private fun buildQuery(filter: LibraryFilter, sort: LibrarySort): RoomRawQuery {
        val where = ArrayList<String>()
        val binds = ArrayList<(androidx.sqlite.SQLiteStatement, Int) -> Unit>()
        fun bindText(v: String) { binds.add { st, i -> st.bindText(i, v) } }
        fun bindLong(v: Long) { binds.add { st, i -> st.bindLong(i, v) } }

        where.add("d.trashed = 0")
        where.add("d.deleted = 0")
        where.add("d.missing = 0")

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
        filter.correspondent?.let {
            where.add("d.correspondent = ?")
            bindText(it)
        }
        filter.docType?.let {
            where.add("d.docType = ?")
            bindText(it)
        }
        filter.docDateFrom?.let {
            where.add("d.docDate IS NOT NULL AND d.docDate >= ?")
            bindText(it)
        }
        filter.docDateTo?.let {
            where.add("d.docDate IS NOT NULL AND d.docDate <= ?")
            bindText(it)
        }
        filter.addedFrom?.let {
            where.add("d.addedAt >= ?")
            bindLong(it)
        }
        filter.addedTo?.let {
            where.add("d.addedAt <= ?")
            bindLong(it)
        }
        filter.folder?.takeIf { it.isNotBlank() }?.let { folder ->
            val prefix = folder.trimEnd('/') + "/"
            where.add("d.relPath LIKE ? ESCAPE '\\'")
            bindText(escapeLike(prefix) + "%")
        }

        val dir = if (sort.ascending) "ASC" else "DESC"
        val orderBy = when (sort.field) {
            SortField.NAME -> "d.fileName COLLATE NOCASE $dir"
            SortField.ADDED -> "d.addedAt $dir"
            SortField.DOC_DATE -> "d.docDate IS NULL, d.docDate $dir" // nulls last
            SortField.SIZE -> "d.size $dir"
        }

        val sql = buildString {
            append("SELECT d.* FROM documents d WHERE ")
            append(where.joinToString(" AND "))
            append(" ORDER BY ")
            append(orderBy)
        }

        return RoomRawQuery(sql = sql, onBindStatement = { st ->
            binds.forEachIndexed { idx, bind -> bind(st, idx + 1) }
        })
    }

    private fun escapeLike(s: String): String =
        s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Sanitize a user FTS query into a safe MATCH expression. FTS5 treats bareword tokens as an
     * implicit AND; wrapping each token in double quotes avoids syntax errors from stray operator
     * characters while still matching every term. A trailing `*` is added for prefix matching on
     * the last token to make search feel incremental.
     */
    private fun sanitizeFts(query: String): String {
        val tokens = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "\"\""
        return tokens.mapIndexed { i, t ->
            val cleaned = t.replace("\"", "")
            if (cleaned.isEmpty()) "" else if (i == tokens.lastIndex) "\"$cleaned\"*" else "\"$cleaned\""
        }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { "\"\"" }
    }
}
