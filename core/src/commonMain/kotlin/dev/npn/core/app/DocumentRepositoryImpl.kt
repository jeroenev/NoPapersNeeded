package dev.npn.core.app

import androidx.room3.RoomRawQuery
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
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

    private val fieldDao get() = db.documentFieldDao()

    override fun observeDocuments(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): Flow<List<DocListItem>> {
        val queryText = filter.query?.takeIf { it.isNotBlank() }
        val rawFlow = documentDao.observeLibraryRaw(buildQuery(filter, sort))
        return rawFlow
            .conflate()
            .mapLatest { docs ->
                if (queryText == null) {
                    // Attach tags and custom fields for all docs in one pass.
                    val ids = docs.map { it.docId }
                    val tagsByDoc = tagsFor(ids)
                    val fieldsByDoc = fieldsFor(ids)
                    docs.map {
                        DocListItem(it, tagsByDoc[it.docId].orEmpty(), fields = fieldsByDoc[it.docId].orEmpty())
                    }
                } else {
                    // FTS-first: the base flow stays authoritative for the WHERE clause (all filter
                    // predicates + trashed/deleted/missing), so intersect it with the FTS match set
                    // instead of scanning the whole library in memory. The expensive work (tags/fields
                    // attach and the rank sort) is bounded to the matched docs only. Re-emits when any
                    // documents/tags/fields row changes re-emit the base flow.
                    val hits = ftsDao.search(FtsQuery.sanitize(queryText))
                    val snippetById = hits.associate { it.docId to it.snippet }
                    val order = hits.map { it.docId }.withIndex().associate { it.value to it.index }
                    val matched = docs.filter { it.docId in snippetById }
                    val ids = matched.map { it.docId }
                    val tagsByDoc = tagsFor(ids)
                    val fieldsByDoc = fieldsFor(ids)
                    matched
                        .sortedBy { order[it.docId] ?: Int.MAX_VALUE }
                        .map {
                            DocListItem(
                                it,
                                tagsByDoc[it.docId].orEmpty(),
                                snippetById[it.docId],
                                fieldsByDoc[it.docId].orEmpty(),
                            )
                        }
                }
            }.flowOn(Dispatchers.Default)
    }

    /**
     * Live count of documents matching [filter] (same WHERE semantics as [observeDocuments]).
     * Unlike observing the list, this never materializes rows or attaches tags/fields, so it is
     * cheap even for large libraries. Impl-only: no UI consumer yet, so it is deliberately not
     * part of [DocumentRepository].
     */
    fun observeCount(filter: LibraryFilter): Flow<Int> {
        // FTS matches can't be expressed as a COUNT predicate, so the query-text path reuses the
        // (FTS-bounded, already cheap) list flow instead of scanning the library.
        if (!filter.query.isNullOrBlank()) {
            return observeDocuments(filter, LibrarySort()).map { it.size }
        }
        val parts = buildWhere(filter)
        val sql = "SELECT COUNT(*) FROM documents d WHERE ${parts.where.joinToString(" AND ")}"
        return documentDao.observeCount(
            RoomRawQuery(sql = sql, onBindStatement = { st ->
                parts.binds.forEachIndexed { idx, bind -> bind(st, idx + 1) }
            }),
        )
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> =
        combine(
            documentDao.observeByDocId(docId),
            documentDao.observeTagsForDoc(docId),
            fieldDao.observeForDoc(docId),
        ) { doc, tags, fields ->
            if (doc == null) {
                null
            } else {
                DocListItem(
                    doc,
                    tags,
                    fields = fields.associate { it.fieldKey to it.fieldValue },
                )
            }
        }

    override fun observeFieldKeys(): Flow<List<String>> = fieldDao.observeDistinctKeys()

    override suspend fun searchSuggestions(
        query: String,
        limit: Int,
    ): List<String> {
        val prefix = query.trim().takeIf { it.length >= MIN_SUGGESTION_LENGTH } ?: return emptyList()
        val sourceLimit = limit.coerceAtLeast(1)
        val likePrefix = escapeLike(prefix) + "%"
        val rows = ftsDao.rowsForPrefix("\"${prefix.replace("\"", "\"\"")}\"*", sourceLimit)
        val contentTerms =
            rows
                .asSequence()
                .flatMap { row -> sequenceOf(row.fileName, row.title, row.content).filterNotNull() }
                .flatMap { TOKEN_REGEX.findAll(it).map(MatchResult::value) }
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(sourceLimit)
                .toList()
        val sources =
            listOf(
                contentTerms,
                db.tagDao().tagsForPrefix(likePrefix, sourceLimit),
                documentDao.docTypesForPrefix(likePrefix, sourceLimit),
                documentDao.correspondentsForPrefix(likePrefix, sourceLimit),
            )
        return buildList {
            var index = 0
            while (size < limit && sources.any { index < it.size }) {
                sources.forEach { source -> source.getOrNull(index)?.let(::add) }
                index++
            }
        }.distinctBy { it.lowercase() }.take(limit)
    }

    private suspend fun fieldsFor(docIds: List<String>): Map<String, Map<String, String>> {
        if (docIds.isEmpty()) return emptyMap()
        return fieldDao
            .fieldsForDocs(docIds)
            .groupBy { it.docId }
            .mapValues { (_, rows) -> rows.associate { it.fieldKey to it.fieldValue } }
    }

    override fun observeUntaggedCount(): Flow<Int> = documentDao.countUntagged()

    override fun observeUnreviewedOrUntaggedCount(): Flow<Int> = documentDao.countUnreviewedOrUntagged()

    override fun observeTrashed(): Flow<List<DocListItem>> =
        documentDao
            .observeTrashed()
            .conflate()
            .mapLatest { docs ->
                val tags = tagsFor(docs.map { it.docId })
                docs.map { DocListItem(it, tags[it.docId].orEmpty()) }
            }.flowOn(Dispatchers.Default)

    override fun observeMissing(): Flow<List<DocumentEntity>> = documentDao.observeMissing()

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> =
        db.duplicateDao().observeAll().map { rows -> rows.map { it.docId to it.relPath } }

    override fun observeCorrespondents(): Flow<List<String>> = documentDao.observeCorrespondents()

    override fun observeDocTypes(): Flow<List<String>> = documentDao.observeDocTypes()

    override suspend fun history(docId: String): List<DocumentHistoryRecord> {
        val dao = db.metadataProjectionDao()
        return dao
            .eventsForDocument(docId)
            .map { row ->
                DocumentHistoryRecord(
                    hlc = Hlc(row.hlcPhysical, row.hlcCounter, row.hlcDevice),
                    deviceId = row.deviceId,
                    operation = row.operation,
                    event = JournalCodec.decodeLineOrNull(row.canonicalJson),
                )
            }.sortedBy { it.hlc }
    }

    private suspend fun tagsFor(docIds: List<String>): Map<String, List<String>> {
        if (docIds.isEmpty()) return emptyMap()
        return documentDao
            .tagsForDocs(docIds)
            .groupBy({ it.docId }, { it.tag })
            .mapValues { it.value.sorted() }
    }

    /**
     * Build the filtered library query. Base predicate excludes trashed/deleted/missing.
     * Tag AND/ANY/NONE are done with EXISTS subqueries; folder is a relPath LIKE prefix;
     * untaggedOnly uses NOT EXISTS; missingAny becomes one OR-ed "field is null/blank" group.
     * Query text (FTS) is handled by the two-step above, so it is not
     * part of this SQL beyond the id constraint being applied in memory.
     */
    private fun buildQuery(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): RoomRawQuery {
        val parts = buildWhere(filter)
        val sql =
            buildString {
                append("SELECT d.* FROM documents d WHERE ")
                append(parts.where.joinToString(" AND "))
                append(" ORDER BY ")
                append(orderBySql(sort))
            }

        return RoomRawQuery(sql = sql, onBindStatement = { st ->
            parts.binds.forEachIndexed { idx, bind -> bind(st, idx + 1) }
        })
    }

    /** WHERE clause + bind lambdas shared by the list query and [observeCount]. */
    private fun buildWhere(filter: LibraryFilter): QueryParts {
        val where = ArrayList<String>()
        val binds = ArrayList<(androidx.sqlite.SQLiteStatement, Int) -> Unit>()

        fun bindText(v: String) {
            binds.add { st, i -> st.bindText(i, v) }
        }

        fun bindLong(v: Long) {
            binds.add { st, i -> st.bindLong(i, v) }
        }

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
        if (filter.missingAny.isNotEmpty()) {
            where.add(
                filter.missingAny
                    .distinct()
                    .joinToString(separator = " OR ", prefix = "(", postfix = ")", transform = ::missingSql),
            )
        }

        return QueryParts(where, binds)
    }

    /** "Field is absent" predicate: NULL and empty string are both treated as missing. */
    private fun missingSql(field: DocField): String =
        when (field) {
            DocField.TITLE -> "(d.title IS NULL OR d.title = '')"
            DocField.CORRESPONDENT -> "(d.correspondent IS NULL OR d.correspondent = '')"
            DocField.DOC_DATE -> "(d.docDate IS NULL OR d.docDate = '')"
        }

    private fun orderBySql(sort: LibrarySort): String {
        val dir = if (sort.ascending) "ASC" else "DESC"
        return when (sort.field) {
            SortField.NAME -> "d.fileName COLLATE NOCASE $dir"

            SortField.ADDED -> "d.addedAt $dir"

            SortField.DOC_DATE -> "d.docDate IS NULL, d.docDate $dir"

            // nulls last
            SortField.SIZE -> "d.size $dir"
        }
    }

    private fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** WHERE clause list plus ordered bind lambdas, built once and shared by list + count queries. */
    private data class QueryParts(
        val where: List<String>,
        val binds: List<(androidx.sqlite.SQLiteStatement, Int) -> Unit>,
    )

    private companion object {
        const val MIN_SUGGESTION_LENGTH = 2
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}_-]+")
    }
}
