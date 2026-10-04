package dev.npn.core.app

import androidx.room3.RoomRawQuery
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.LIVE_DOCUMENT
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.toFieldDef
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * The single translation from the disposable index row to the [Doc] the rest of the app sees.
 * Every read in [DocumentRepositoryImpl] goes through here, which is what keeps a `documents`
 * column change confined to this file. Stringly-typed columns are decoded to their closed
 * vocabularies ([OcrState], [OcrEngineRef]) and the display-title fallback is resolved once.
 *
 * [fields] are the document's `document_fields` rows when the caller loaded them. The well-known
 * five are always present from the row's cache columns, so a read that skips the field join
 * (trash, missing) still shows titles rather than file names.
 */
internal fun DocumentEntity.toDoc(fields: Map<String, String> = emptyMap()): Doc =
    Doc(
        docId = docId,
        relPath = relPath,
        fileName = fileName,
        displayTitle = displayTitleOf(title, fileName),
        size = size,
        pageCount = pageCount,
        addedAt = addedAt,
        reviewed = reviewed,
        fields = cachedFields() + fields,
        trashed = trashed,
        trashedOrigPath = trashedOrigPath,
        missing = missing,
        ocrState = OcrState.fromColumn(ocrState),
        ocrEngine = OcrEngineRef.ofColumn(ocrEngine),
        ocrEngineOverride = OcrEngineRef.ofColumn(ocrEngineOverride),
    )

/** The well-known field values the row caches; see [DocumentEntity.correspondent]. */
private fun DocumentEntity.cachedFields(): Map<String, String> =
    buildMap {
        title?.let { put(FieldKeys.TITLE, it) }
        correspondent?.let { put(FieldKeys.CORRESPONDENT, it) }
        docType?.let { put(FieldKeys.DOC_TYPE, it) }
        docDate?.let { put(FieldKeys.DOC_DATE, it) }
        notes?.let { put(FieldKeys.NOTES, it) }
    }

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
    private val tagDao get() = db.tagDao()
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
                    val tagsByDoc = tagsForDocuments(ids)
                    val fieldsByDoc = fieldsFor(ids)
                    docs.map {
                        DocListItem(it.toDoc(fieldsByDoc[it.docId].orEmpty()), tagsByDoc[it.docId].orEmpty())
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
                    val tagsByDoc = tagsForDocuments(ids)
                    val fieldsByDoc = fieldsFor(ids)
                    matched
                        .sortedBy { order[it.docId] ?: Int.MAX_VALUE }
                        .map {
                            DocListItem(
                                it.toDoc(fieldsByDoc[it.docId].orEmpty()),
                                tagsByDoc[it.docId].orEmpty(),
                                snippetById[it.docId],
                            )
                        }
                }
            }.flowOn(Dispatchers.Default)
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> =
        combine(
            documentDao.observeByDocId(docId),
            tagDao.observeTagsForDocument(docId),
            fieldDao.observeForDoc(docId),
        ) { doc, tags, fields ->
            if (doc == null) {
                null
            } else {
                DocListItem(doc.toDoc(fields.associate { it.fieldKey to it.fieldValue }), tags)
            }
        }

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
                .flatMap { row -> sequenceOf(row.fileName, row.fields, row.content).filterNotNull() }
                .flatMap { TOKEN_REGEX.findAll(it).map(MatchResult::value) }
                .filter { it.startsWith(prefix, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(sourceLimit)
                .toList()
        // Every field whose values documents share contributes them: what correspondent and docType
        // got by name before, any such registered field gets now.
        val textFields =
            db
                .fieldDefDao()
                .all()
                .mapNotNull { it.toFieldDef() }
                .filter { it.sharesVocabulary }
                .map { fieldDao.valuesForPrefix(it.key, likePrefix, sourceLimit) }
        val sources = listOf(contentTerms, tagDao.tagsForPrefix(likePrefix, sourceLimit)) + textFields
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
                val tags = tagsForDocuments(docs.map { it.docId })
                docs.map { DocListItem(it.toDoc(), tags[it.docId].orEmpty()) }
            }.flowOn(Dispatchers.Default)

    override fun observeMissing(): Flow<List<Doc>> =
        documentDao.observeMissing().map { docs -> docs.map { it.toDoc() } }

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> =
        db.duplicateDao().observeAll().map { rows -> rows.map { it.docId to it.relPath } }

    override suspend fun history(docId: String): List<DocumentHistoryRecord> {
        val dao = db.metadataProjectionDao()
        return dao
            .eventsForDocument(docId)
            .sortedBy { Hlc(it.hlcPhysical, it.hlcCounter, it.hlcDevice) }
            .map { row ->
                val event = JournalCodec.decodeLineOrNull(row.canonicalJson)
                DocumentHistoryRecord(
                    timeMillis = row.hlcPhysical,
                    deviceId = row.deviceId,
                    entry = event?.let { HistoryEntry.of(it.op) } ?: HistoryEntry.Undecodable(row.operation),
                )
            }
    }

    override suspend fun tagsForDocuments(docIds: List<String>): Map<String, List<String>> {
        if (docIds.isEmpty()) return emptyMap()
        return tagDao
            .tagsForDocs(docIds)
            .groupBy({ it.docId }, { it.tag })
            .mapValues { it.value.sorted() }
    }

    /**
     * Build the filtered library query. Base predicate excludes trashed/deleted/missing.
     * Tag AND/ANY/NONE and field equals/range are EXISTS subqueries on their join tables; folder
     * is a relPath LIKE prefix; untaggedOnly uses NOT EXISTS; missingFields becomes one OR-ed
     * "no field row" group.
     * Query text (FTS) is handled by the two-step above, so it is not
     * part of this SQL beyond the id constraint being applied in memory.
     */
    private fun buildQuery(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): RoomRawQuery {
        val parts = buildWhere(filter)
        val join = sortJoinSql(sort)
        val sql =
            buildString {
                append("SELECT d.* FROM documents d")
                join?.let(::append)
                append(" WHERE ")
                append(parts.where.joinToString(" AND "))
                append(" ORDER BY ")
                append(orderBySql(sort))
            }
        // The join's bind precedes every WHERE bind in the statement, so it is bound first.
        val sortKey = (sort.field as? SortField.Field)?.key
        return RoomRawQuery(sql = sql, onBindStatement = { st ->
            var index = 1
            if (sortKey != null) st.bindText(index++, sortKey)
            parts.binds.forEach { bind -> bind(st, index++) }
        })
    }

    /** WHERE clause + bind lambdas of the list query. */
    private fun buildWhere(filter: LibraryFilter): QueryParts {
        val where = ArrayList<String>()
        val binds = ArrayList<(androidx.sqlite.SQLiteStatement, Int) -> Unit>()

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
        if (filter.docIds.isNotEmpty()) {
            val placeholders = filter.docIds.joinToString(", ") { "?" }
            where.add("d.docId IN ($placeholders)")
            filter.docIds.forEach(::bindText)
        }
        filter.reviewed?.let {
            where.add("d.reviewed = ?")
            bindLong(if (it) 1L else 0L)
        }
        addFieldPredicates(filter, where, ::bindText)
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

    private fun orderBySql(sort: LibrarySort): String {
        val dir = if (sort.ascending) "ASC" else "DESC"
        return when (sort.field) {
            SortField.Name -> "d.fileName COLLATE NOCASE $dir"

            SortField.Added -> "d.addedAt $dir"

            SortField.Size -> "d.size $dir"

            // Nulls last; numeric shadow decides for NUMBER/DECIMAL fields, the canonical string
            // (chronological for dates) for everything else.
            is SortField.Field -> "sf.fieldValue IS NULL, sf.numValue $dir, sf.fieldValue COLLATE NOCASE $dir"
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
