package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface FtsDao {
    /**
     * Replace a document's FTS row. FTS5 external-content is not used here; this is a plain FTS5
     * table so updates are exact-match delete + reinsert keyed on docId. Build [row] with
     * [dev.npn.core.index.FtsRows] so every write site indexes the same columns.
     */
    @Transaction
    suspend fun replaceDocument(row: FtsDocumentEntity) {
        deleteForDoc(row.docId)
        insert(row)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: FtsDocumentEntity)

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

    @Query("DELETE FROM fts_documents WHERE docId = :docId")
    suspend fun deleteForDoc(docId: String)

    /**
     * The stored extracted-text content column for a doc, or null if the doc has no FTS row
     * yet. Used by the fold pipeline to preserve OCR/embedded content across metadata-only
     * rewrites (the fold has no content source of its own).
     */
    @Query("SELECT content FROM fts_documents WHERE docId = :docId")
    suspend fun contentForDoc(docId: String): String?

    @Query("SELECT docId, content FROM fts_documents WHERE docId IN (:docIds)")
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
    @Query("SELECT docId FROM fts_documents WHERE docId IN (:docIds)")
    suspend fun indexedDocIdsChunk(docIds: List<String>): List<String>

    /** Chunked [indexedDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun indexedDocIds(docIds: List<String>): List<String> = chunkedQuery(docIds) { indexedDocIdsChunk(it) }

    /**
     * Replace several documents' FTS rows atomically, exact-match delete + reinsert keyed on
     * docId, matching [replaceDocument] per row.
     */
    @Transaction
    suspend fun replaceDocuments(rows: List<FtsDocumentEntity>) {
        if (rows.isEmpty()) return
        chunkedDelete(rows.map { it.docId }) { deleteForDocs(it) }
        insertAll(rows)
    }

    @Query("DELETE FROM fts_documents WHERE docId IN (:docIds)")
    suspend fun deleteForDocs(docIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<FtsDocumentEntity>)

    /** Indexed rows containing a token with the requested prefix, used to derive autocomplete. */
    @Query("SELECT * FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank LIMIT :limit")
    suspend fun rowsForPrefix(
        query: String,
        limit: Int,
    ): List<FtsDocumentEntity>

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

        /** Chunked [deleteForDocs] so large doc sets stay under SQLite's bind-variable limit. */
        private suspend fun chunkedDelete(
            docIds: List<String>,
            block: suspend (List<String>) -> Unit,
        ) {
            if (docIds.isEmpty()) return
            if (docIds.size <= DocumentDao.BIND_CHUNK) {
                block(docIds)
                return
            }
            for (i in docIds.indices step DocumentDao.BIND_CHUNK) {
                block(docIds.subList(i, minOf(i + DocumentDao.BIND_CHUNK, docIds.size)))
            }
        }
    }
}

/** Minimal FTS projection: a row's docId and its extracted-text content, for the bulk fold preload. */
data class FtsContentRow(
    val docId: String,
    val content: String?,
)
