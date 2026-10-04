package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * The full-text index. FTS5 finds a row only by MATCH or by rowid, and a `docId = ?` filter on
 * `fts_documents` reads every row, so every document-scoped read and write resolves the document's
 * rowid through [FtsKeyEntity] first.
 */
@Dao
interface FtsDao {
    /**
     * FTS5 MATCH search returning a highlighted snippet. `snippet()` is an FTS5 auxiliary
     * function; Room's compile-time query verification does not know it, so this uses
     * @Query with a subquery form Room can still parse. The docId is column 0.
     */
    @Query(
        "SELECT docId, snippet(fts_documents, -1, char(1), char(2), '...', 16) AS snippet " +
            "FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank",
    )
    suspend fun search(query: String): List<FtsHit>

    @Query("SELECT docId FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank")
    suspend fun searchDocIds(query: String): List<String>

    /**
     * Indexed rows containing a token with the requested prefix, used to derive autocomplete.
     * Return only bounded content/field fragments: selecting complete OCR bodies and long-text
     * fields made Android copy and tokenize several potentially huge values on every keystroke.
     */
    @Query(
        "SELECT docId, fileName, " +
            "snippet(fts_documents, 2, '', '', ' ', 16) AS content, tags, " +
            "snippet(fts_documents, 4, '', '', ' ', 16) AS fields " +
            "FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank LIMIT :limit",
    )
    suspend fun rowsForPrefix(
        query: String,
        limit: Int,
    ): List<FtsDocumentEntity>

    /**
     * The stored extracted-text content column for a doc, or null if the doc has no FTS row
     * yet. Used by the fold pipeline to preserve OCR/embedded content across metadata-only
     * rewrites (the fold has no content source of its own).
     */
    @Query("SELECT content FROM fts_documents WHERE rowid = (SELECT ftsRowid FROM fts_keys WHERE docId = :docId)")
    suspend fun contentForDoc(docId: String): String?

    @Query(
        "SELECT docId, content FROM fts_documents " +
            "WHERE rowid IN (SELECT ftsRowid FROM fts_keys WHERE docId IN (:docIds))",
    )
    suspend fun contentsForDocsChunk(docIds: List<String>): List<FtsContentRow>

    /** Chunked [contentsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun contentsForDocs(docIds: List<String>): List<FtsContentRow> =
        chunkedQuery(docIds) { contentsForDocsChunk(it) }

    /**
     * Which of [docIds] already have an FTS row. Presence only: the fold needs to know whether a
     * document has been indexed yet (a brand-new one has no row), NOT what its text says. Reading
     * the `content` column here instead would pull every document's entire extracted text into
     * memory on every whole-library fold — and [replaceDocuments]' caller re-reads the content it
     * carries forward under the write gate anyway (see Indexer.flushChanges).
     */
    @Query("SELECT docId FROM fts_keys WHERE docId IN (:docIds)")
    suspend fun indexedDocIdsChunk(docIds: List<String>): List<String>

    /** Chunked [indexedDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun indexedDocIds(docIds: List<String>): List<String> = chunkedQuery(docIds) { indexedDocIdsChunk(it) }

    /**
     * Write a document's FTS row: in place when it has one, else as a new row under a newly
     * allocated key. Build [row] with [dev.npn.core.index.FtsRows] so every write site indexes the
     * same columns.
     */
    @Transaction
    suspend fun replaceDocument(row: FtsDocumentEntity) = replaceDocuments(listOf(row))

    /** Several documents' rows written atomically, each as [replaceDocument] writes one. */
    @Transaction
    suspend fun replaceDocuments(rows: List<FtsDocumentEntity>) {
        if (rows.isEmpty()) return
        val rowids = rowidsFor(rows.map { it.docId })
        for (row in rows) {
            val rowid = rowids[row.docId]
            if (rowid != null) {
                updateRow(rowid, row.fileName, row.content, row.tags, row.fields)
            } else {
                val allocated = insertKey(FtsKeyEntity(docId = row.docId))
                insertRow(allocated, row.docId, row.fileName, row.content, row.tags, row.fields)
                rowids[row.docId] = allocated
            }
        }
    }

    @Query("SELECT * FROM fts_keys WHERE docId IN (:docIds)")
    suspend fun keysChunk(docIds: List<String>): List<FtsKeyEntity>

    /** The FTS rowid of each of [docIds] that has a row, chunked under SQLite's bind limit. */
    suspend fun rowidsFor(docIds: List<String>): HashMap<String, Long> =
        chunkedQuery(docIds) { keysChunk(it) }.associateTo(HashMap()) { it.docId to it.ftsRowid }

    /** Allocate a document's key; the returned rowid is the one its FTS row is inserted under. */
    @Insert
    suspend fun insertKey(key: FtsKeyEntity): Long

    @Query(
        "INSERT INTO fts_documents (rowid, docId, fileName, content, tags, fields) " +
            "VALUES (:rowid, :docId, :fileName, :content, :tags, :fields)",
    )
    suspend fun insertRow(
        rowid: Long,
        docId: String,
        fileName: String?,
        content: String?,
        tags: String?,
        fields: String?,
    )

    @Query(
        "UPDATE fts_documents SET fileName = :fileName, content = :content, tags = :tags, fields = :fields " +
            "WHERE rowid = :rowid",
    )
    suspend fun updateRow(
        rowid: Long,
        fileName: String?,
        content: String?,
        tags: String?,
        fields: String?,
    )

    companion object {
        private suspend fun <T> chunkedQuery(
            ids: List<String>,
            block: suspend (List<String>) -> List<T>,
        ): List<T> {
            if (ids.isEmpty()) return emptyList()
            if (ids.size <= DocumentDao.BIND_CHUNK) return block(ids)
            val out = ArrayList<T>(ids.size)
            for (i in ids.indices step DocumentDao.BIND_CHUNK) {
                out += block(ids.subList(i, minOf(i + DocumentDao.BIND_CHUNK, ids.size)))
            }
            return out
        }
    }
}

/** Minimal FTS projection: a row's docId and its extracted-text content, for the bulk fold preload. */
data class FtsContentRow(
    val docId: String,
    val content: String?,
)
