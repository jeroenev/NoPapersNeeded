package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDao
import dev.npn.core.db.FtsQuery
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.defs
import dev.npn.core.db.isLive
import dev.npn.core.index.DuplicateReport
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.Hlc
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/**
 * The single translation from the disposable index row to the [Doc] the rest of the app sees.
 * Every read in [DocumentRepositoryImpl] goes through here, which is what keeps a `documents`
 * column change confined to this file. Stringly-typed columns are decoded to their closed
 * vocabularies ([OcrState], [OcrEngineRef]) and the display-title fallback is resolved once.
 *
 * [fields] are the document's `document_fields` rows when the caller loaded them. The well-known
 * five are always present from the row's cache columns, so a read that skips the field join
 * (trash, missing) still shows titles rather than file names. Fields the app keeps for itself
 * ([FieldKeys.HIDDEN]) are left out: nothing that shows a document shows them.
 */
internal fun DocumentEntity.toDoc(fields: Map<String, String> = emptyMap()): Doc =
    Doc(
        docId = docId,
        relPath = relPath,
        fileName = fileName,
        displayTitle = displayTitleOf(title, fileName),
        size = size,
        pageCount = pageCount,
        createdAt = createdAt ?: mtime,
        reviewed = reviewed,
        fields = cachedFields() + (fields - FieldKeys.HIDDEN),
        trashed = trashed,
        deleted = deleted,
        trashedOrigPath = trashedOrigPath,
        missing = missing,
        ocrState = OcrState.fromColumn(ocrState),
        ocrEngine = OcrEngineRef.ofColumn(ocrEngine),
        ocrSource = ocrSource,
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
 * Read side of the library, built on [NpnDatabase]. Filtering/sorting SQL is assembled by
 * [LibraryQuerySql] and handed to [dev.npn.core.db.DocumentDao.observeLibraryRaw] (its observed
 * entities cover both documents and document_tags, so tag changes re-emit).
 *
 * Two-step FTS join (design decision): when [LibraryFilter.query] is non-blank we cannot express
 * `MATCH` inside the RawQuery in a way Room verifies cleanly, so we first fetch the matching docIds
 * from [FtsDao], constrain the main query to those ids, and attach snippets in memory, built for the
 * best [SNIPPET_LIMIT] matches only. The FTS lookup runs inside [mapLatest] on each re-emission of
 * the base flow, so it stays live as underlying tables change (tag edits, new imports).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class DocumentRepositoryImpl(
    private val db: NpnDatabase,
    /** Where a query's rows are turned into list items. */
    private val mapping: CoroutineDispatcher = Dispatchers.Default,
    /** Where each history row (its actor and HLC) came from ([LocalJournalActors.origins]). */
    private val origins: suspend (List<Pair<String, Hlc>>) -> List<HistoryOrigin> = { rows ->
        rows.map { HistoryOrigin.UNKNOWN }
    },
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
        val rawFlow = documentDao.observeLibraryRaw(LibraryQuerySql.build(filter, sort))
        return rawFlow
            .conflate()
            .mapLatest { docs ->
                if (queryText == null) {
                    listItems(docs)
                } else {
                    // FTS-first: the base flow stays authoritative for the WHERE clause (all filter
                    // predicates + trashed/deleted/missing), so intersect it with the FTS match set
                    // instead of scanning the whole library in memory. The expensive work (tags/fields
                    // attach and the rank sort) is bounded to the matched docs only. Re-emits when any
                    // documents/tags/fields row changes re-emit the base flow.
                    val match = FtsQuery.sanitize(queryText)
                    val order = ftsDao.search(match).withIndex().associate { it.value to it.index }
                    val matched = docs.filter { it.docId in order }.sortedBy { order.getValue(it.docId) }
                    // Snippets for the best matches only: past them nobody reads one, and a short
                    // prefix matches most of a library, whose every text a snippet would re-read.
                    val snippets = ftsDao.snippetsFor(match, matched.take(SNIPPET_LIMIT).map { it.docId })
                    listItems(matched, snippets)
                }
            }.flowOn(mapping)
    }

    override suspend fun searchDocuments(
        query: String,
        limit: Int,
    ): List<DocListItem> {
        if (query.isBlank() || limit <= 0) return emptyList()
        return withContext(mapping) {
            val match = FtsQuery.sanitize(query)
            // Ranked ids are cheap; rows are read a slice at a time until enough live ones are found.
            val found = ArrayList<DocumentEntity>(limit)
            for (slice in ftsDao.search(match).chunked(limit)) {
                val rows = documentDao.getByDocIds(slice).filter { it.isLive }.associateBy { it.docId }
                slice.mapNotNullTo(found) { rows[it] }
                if (found.size >= limit) break
            }
            val top = found.take(limit)
            listItems(top, ftsDao.snippetsFor(match, top.map { it.docId }))
        }
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
        val likePrefix = LibraryQuerySql.escapeLike(prefix) + "%"
        val rows = ftsDao.rowsForPrefix(FtsQuery.prefix(prefix), sourceLimit)
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
                .defs()
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

    override suspend fun liveDocuments(docIds: List<String>): List<DocListItem> =
        listItems(documentDao.getByDocIds(docIds.distinct()).filter { it.isLive })

    /** [docs] with their tags, custom fields and search [snippets], each attached in one pass. */
    private suspend fun listItems(
        docs: List<DocumentEntity>,
        snippets: Map<String, String?> = emptyMap(),
    ): List<DocListItem> {
        val ids = docs.map { it.docId }
        val tagsByDoc = tagsForDocuments(ids)
        val fieldsByDoc = fieldsFor(ids)
        return docs.map {
            DocListItem(it.toDoc(fieldsByDoc[it.docId].orEmpty()), tagsByDoc[it.docId].orEmpty(), snippets[it.docId])
        }
    }

    private suspend fun fieldsFor(docIds: List<String>): Map<String, Map<String, String>> {
        if (docIds.isEmpty()) return emptyMap()
        return fieldDao
            .fieldsForDocs(docIds)
            .groupBy { it.docId }
            .mapValues { (_, rows) -> rows.associate { it.fieldKey to it.fieldValue } }
    }

    override fun observeReviewQueueCount(mode: ReviewMode): Flow<Int> =
        when (mode) {
            ReviewMode.UNTAGGED_ONLY -> documentDao.countUntagged()
            ReviewMode.INBOX_WORKFLOW -> documentDao.countUnreviewedOrUntagged()
        }

    override fun observeTrashed(): Flow<List<DocListItem>> =
        documentDao
            .observeTrashed()
            .conflate()
            .mapLatest { docs ->
                val tags = tagsForDocuments(docs.map { it.docId })
                docs.map { DocListItem(it.toDoc(), tags[it.docId].orEmpty()) }
            }.flowOn(mapping)

    override fun observeMissing(): Flow<List<Doc>> =
        documentDao.observeMissing().map { docs -> docs.map { it.toDoc() } }

    override fun observeDuplicates(): Flow<List<DuplicateReport>> =
        db.duplicateDao().observeAll().map { rows -> rows.map { DuplicateReport(it.docId, it.relPath) } }

    override suspend fun history(docId: String): List<DocumentHistoryRecord> {
        val dao = db.metadataProjectionDao()
        val rows = dao.eventsForDocument(docId)
        // Journal events carry a fresh actor per transaction, not the device id, so this device's
        // own writes are known only by the actors its commits recorded (LocalJournalActors).
        val origin = origins(rows.map { it.deviceId to Hlc(it.hlcPhysical, it.hlcCounter, it.hlcDevice) })
        // In HLC order, as the query sorts them.
        return rows.mapIndexed { index, row ->
            // A row an older build recorded may hold what this one cannot read.
            val event = runCatchingCancellable { EventDecoderRegistry.decodeStored(row.canonicalJson) }.getOrNull()
            DocumentHistoryRecord(
                timeMillis = row.hlcPhysical,
                origin = origin[index],
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

    private companion object {
        const val MIN_SUGGESTION_LENGTH = 2

        /** How many of a search's best matches get a snippet: several screens' worth. */
        const val SNIPPET_LIMIT = 200
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}_-]+")
    }
}
