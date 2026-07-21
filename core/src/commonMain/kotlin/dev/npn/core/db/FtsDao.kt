package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface FtsDao {

    /**
     * Replace a document's FTS row(s). FTS5 external-content is not used here; this is a
     * plain FTS5 table so updates are exact-match delete + reinsert keyed on docId.
     */
    @Transaction
    suspend fun replaceDocument(
        docId: String,
        fileName: String?,
        title: String?,
        content: String?,
        tags: String?,
        correspondent: String?,
        docType: String?,
        notes: String? = null,
    ) {
        deleteForDoc(docId)
        insert(
            FtsDocumentEntity(
                docId = docId,
                fileName = fileName,
                title = title,
                notes = notes,
                content = content,
                tags = tags,
                correspondent = correspondent,
                docType = docType,
            ),
        )
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

    /** Indexed rows containing a token with the requested prefix, used to derive autocomplete. */
    @Query("SELECT * FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank LIMIT :limit")
    suspend fun rowsForPrefix(query: String, limit: Int): List<FtsDocumentEntity>
}
