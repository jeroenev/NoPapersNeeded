package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentFieldDao {
    /** All custom fields for a document, ordered by key. Observed for live UI updates. */
    @Query("SELECT * FROM document_fields WHERE docId = :docId ORDER BY fieldKey COLLATE NOCASE")
    fun observeForDoc(docId: String): Flow<List<DocumentFieldEntity>>

    /** Custom fields for a set of documents, for attaching to list items in one pass. */
    @Query("SELECT * FROM document_fields WHERE docId IN (:docIds)")
    suspend fun fieldsForDocsChunk(docIds: List<String>): List<DocumentFieldEntity>

    /** Chunked [fieldsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun fieldsForDocs(docIds: List<String>): List<DocumentFieldEntity> =
        docIds.chunkedBind { fieldsForDocsChunk(it) }

    /** [docId]'s field values by key, in their canonical form. */
    suspend fun valuesOf(docId: String): Map<String, String> =
        fieldsForDocs(listOf(docId)).associate { it.fieldKey to it.fieldValue }

    /** All custom-field rows, for the AI's field search tools. */
    @Query("SELECT * FROM document_fields")
    suspend fun all(): List<DocumentFieldEntity>

    /** Distinct values of one field on live documents, for autocomplete and AI vocabulary. */
    @Query(DISTINCT_VALUES)
    suspend fun distinctValues(
        key: String,
        limit: Int,
    ): List<String>

    /** [distinctValues], observed. */
    @Query(DISTINCT_VALUES)
    fun observeDistinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>>

    /** Distinct values of one field on live documents starting with [likePrefix] (LIKE-escaped, `\`). */
    @Query(
        "SELECT DISTINCT f.fieldValue FROM document_fields f JOIN documents d ON d.docId = f.docId " +
            "WHERE f.fieldKey = :key AND f.fieldValue LIKE :likePrefix ESCAPE '\\' " +
            "AND $LIVE_DOCUMENT " +
            "ORDER BY f.fieldValue COLLATE NOCASE LIMIT :limit",
    )
    suspend fun valuesForPrefix(
        key: String,
        likePrefix: String,
        limit: Int,
    ): List<String>

    /** Distinct field keys with counts limited to documents the AI search tools can reach. */
    @Query(
        "SELECT f.fieldKey, COUNT(*) AS count FROM document_fields f " +
            "JOIN documents d ON d.docId = f.docId " +
            "WHERE $LIVE_DOCUMENT " +
            "GROUP BY f.fieldKey ORDER BY f.fieldKey COLLATE NOCASE",
    )
    suspend fun keysWithLiveCounts(): List<FieldKeyCount>

    @Query(
        "SELECT fieldKey, COUNT(*) AS count FROM document_fields " +
            "GROUP BY fieldKey ORDER BY fieldKey COLLATE NOCASE",
    )
    fun observeKeysWithCounts(): Flow<List<FieldKeyCount>>

    @Upsert
    suspend fun upsertAll(fields: List<DocumentFieldEntity>)

    /** Replace the field rows of several documents atomically, keyed by docId. */
    @Transaction
    suspend fun replaceForDocs(updates: Map<String, List<DocumentFieldEntity>>) {
        if (updates.isEmpty()) return
        for (chunk in updates.keys.chunked(BIND_CHUNK)) clearFieldsFor(chunk)
        upsertAll(updates.values.flatten())
    }

    @Query("DELETE FROM document_fields WHERE docId IN (:docIds)")
    suspend fun clearFieldsFor(docIds: List<String>)
}

private const val DISTINCT_VALUES =
    "SELECT DISTINCT f.fieldValue FROM document_fields f JOIN documents d ON d.docId = f.docId " +
        "WHERE f.fieldKey = :key AND $LIVE_DOCUMENT " +
        "ORDER BY f.fieldValue COLLATE NOCASE LIMIT :limit"
