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
     * FTS5 MATCH search, best match first, with a snippet from whichever column matched (`-1`);
     * `char(1)` and `char(2)` mark where each hit starts and ends.
     */
    @Query(
        "SELECT docId, snippet(fts_documents, -1, char(1), char(2), '...', 16) AS snippet " +
            "FROM fts_documents WHERE fts_documents MATCH :query ORDER BY rank",
    )
    suspend fun search(query: String): List<FtsHit>

    /**
     * Indexed rows containing a token with the requested prefix, used to derive autocomplete.
     * Only bounded content/field fragments come back: whole OCR bodies and long-text fields would
     * be copied and tokenized on every keystroke.
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

    /** A document's extracted text, or null when it has no FTS row or no text yet. */
    @Query("SELECT content FROM fts_documents WHERE rowid = (SELECT ftsRowid FROM fts_keys WHERE docId = :docId)")
    suspend fun contentForDoc(docId: String): String?

    /**
     * Which of [docIds] already have an FTS row. Presence only: the fold needs to know whether a
     * document has been indexed yet (a brand-new one has no row), not what its text says.
     */
    @Query("SELECT docId FROM fts_keys WHERE docId IN (:docIds)")
    suspend fun indexedDocIdsChunk(docIds: List<String>): List<String>

    /** Chunked [indexedDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun indexedDocIds(docIds: List<String>): List<String> = docIds.chunkedBind { indexedDocIdsChunk(it) }

    /**
     * The fold's write: the metadata columns of each of [rows] (file name, tags, fields). A document
     * with an FTS row keeps its extracted text, which only [upsertContent] writes; one without gets
     * [rows]' row as given. Build rows with [dev.npn.core.index.FtsRows].
     */
    @Transaction
    suspend fun upsertMetadata(rows: List<FtsDocumentEntity>) {
        if (rows.isEmpty()) return
        val rowids = rowidsFor(rows.map { it.docId })
        for (row in rows) {
            val rowid = rowids[row.docId]
            if (rowid != null) {
                updateMetadata(rowid, row.fileName, row.tags, row.fields)
            } else {
                // A new document's first FTS row, under a newly allocated key.
                val newRowid = insertKey(FtsKeyEntity(docId = row.docId))
                insertRow(newRowid, row.docId, row.fileName, row.content, row.tags, row.fields)
                rowids[row.docId] = newRowid
            }
        }
    }

    /**
     * The OCR pipeline's write: [row]'s extracted text. A document with an FTS row keeps the
     * metadata columns, which only [upsertMetadata] writes; one without gets [row] as given.
     */
    @Transaction
    suspend fun upsertContent(row: FtsDocumentEntity) {
        val rowid = rowidFor(row.docId)
        if (rowid != null) {
            updateContent(rowid, row.content)
        } else {
            val newRowid = insertKey(FtsKeyEntity(docId = row.docId))
            insertRow(newRowid, row.docId, row.fileName, row.content, row.tags, row.fields)
        }
    }

    @Query("SELECT ftsRowid FROM fts_keys WHERE docId = :docId")
    suspend fun rowidFor(docId: String): Long?

    /** Drop [docId]'s FTS row and its key, for a document the index forgets outright. */
    @Transaction
    suspend fun delete(docId: String) {
        deleteRowOf(docId)
        deleteKey(docId)
    }

    /** Before [deleteKey]: the key is how the row is found. */
    @Query("DELETE FROM fts_documents WHERE rowid IN (SELECT ftsRowid FROM fts_keys WHERE docId = :docId)")
    suspend fun deleteRowOf(docId: String)

    @Query("DELETE FROM fts_keys WHERE docId = :docId")
    suspend fun deleteKey(docId: String)

    @Query("SELECT * FROM fts_keys WHERE docId IN (:docIds)")
    suspend fun keysChunk(docIds: List<String>): List<FtsKeyEntity>

    /** The FTS rowid of each of [docIds] that has a row, chunked under SQLite's bind limit. */
    suspend fun rowidsFor(docIds: List<String>): HashMap<String, Long> =
        docIds.chunkedBind { keysChunk(it) }.associateTo(HashMap()) { it.docId to it.ftsRowid }

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

    @Query("UPDATE fts_documents SET fileName = :fileName, tags = :tags, fields = :fields WHERE rowid = :rowid")
    suspend fun updateMetadata(
        rowid: Long,
        fileName: String?,
        tags: String?,
        fields: String?,
    )

    @Query("UPDATE fts_documents SET content = :content WHERE rowid = :rowid")
    suspend fun updateContent(
        rowid: Long,
        content: String?,
    )
}
