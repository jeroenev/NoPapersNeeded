package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedViewDao {
    @Query("SELECT * FROM saved_views ORDER BY position, name")
    fun observeAll(): Flow<List<SavedViewEntity>>

    @Upsert
    suspend fun upsert(view: SavedViewEntity)

    @Query("DELETE FROM saved_views WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DocumentFieldDao {
    /** All custom fields for a document, ordered by key. Observed for live UI updates. */
    @Query("SELECT * FROM document_fields WHERE docId = :docId ORDER BY fieldKey COLLATE NOCASE")
    fun observeForDoc(docId: String): Flow<List<DocumentFieldEntity>>

    /** Custom fields for a set of documents, for attaching to list items in one pass. */
    @Query("SELECT * FROM document_fields WHERE docId IN (:docIds)")
    suspend fun fieldsForDocsChunk(docIds: List<String>): List<DocumentFieldEntity>

    /** Chunked [fieldsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun fieldsForDocs(docIds: List<String>): List<DocumentFieldEntity> {
        if (docIds.isEmpty()) return emptyList()
        if (docIds.size <= DocumentDao.BIND_CHUNK) return fieldsForDocsChunk(docIds)
        val out = ArrayList<DocumentFieldEntity>(docIds.size)
        for (i in docIds.indices step DocumentDao.BIND_CHUNK) {
            out += fieldsForDocsChunk(docIds.subList(i, minOf(i + DocumentDao.BIND_CHUNK, docIds.size)))
        }
        return out
    }

    /**
     * Distinct field keys in use across the library, for add-field key autocomplete. Observed so
     * suggestions stay fresh as fields are added elsewhere.
     */
    @Query("SELECT DISTINCT fieldKey FROM document_fields ORDER BY fieldKey COLLATE NOCASE")
    fun observeDistinctKeys(): Flow<List<String>>

    /** All custom-field rows, for the AI's field search tools. */
    @Query("SELECT * FROM document_fields")
    suspend fun all(): List<DocumentFieldEntity>

    /** Distinct field keys with their document counts, for the AI's list_fields tool. */
    @Query(
        "SELECT fieldKey, COUNT(*) AS count FROM document_fields " +
            "GROUP BY fieldKey ORDER BY fieldKey COLLATE NOCASE",
    )
    suspend fun keysWithCounts(): List<FieldKeyCount>

    @Upsert
    suspend fun upsertAll(fields: List<DocumentFieldEntity>)

    /** Replace the custom-field sets of several documents atomically, keyed by docId. */
    @Transaction
    suspend fun replaceForDocs(updates: Map<String, Map<String, String>>) {
        if (updates.isEmpty()) return
        chunkedDelete(updates.keys.toList()) { clearFieldsFor(it) }
        val allRows =
            updates.flatMap { (docId, fields) ->
                fields.map { (key, value) -> DocumentFieldEntity(docId, key, value) }
            }
        upsertAll(allRows)
    }

    @Query("DELETE FROM document_fields WHERE docId = :docId")
    suspend fun deleteForDoc(docId: String)

    @Query("DELETE FROM document_fields WHERE docId IN (:docIds)")
    suspend fun clearFieldsFor(docIds: List<String>)

    @Query("DELETE FROM document_fields")
    suspend fun clearAll()

    companion object {
        /** Chunked [clearFieldsFor] so large doc sets stay under SQLite's bind-variable limit. */
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

@Dao
interface ThumbnailDao {
    @Query("SELECT * FROM thumbnails WHERE docId = :docId")
    suspend fun get(docId: String): ThumbnailEntity?

    @Query("SELECT * FROM thumbnails")
    suspend fun getAll(): List<ThumbnailEntity>

    @Upsert
    suspend fun upsert(thumbnail: ThumbnailEntity)

    @Query("DELETE FROM thumbnails WHERE docId = :docId")
    suspend fun delete(docId: String)

    @Query("DELETE FROM thumbnails")
    suspend fun deleteAll()
}

@Dao
interface DuplicateDao {
    @Query("SELECT * FROM duplicates ORDER BY docId, relPath")
    fun observeAll(): Flow<List<DuplicateEntity>>

    @Query("SELECT * FROM duplicates WHERE docId = :docId")
    suspend fun forDoc(docId: String): List<DuplicateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DuplicateEntity>)

    @Query("DELETE FROM duplicates WHERE docId = :docId")
    suspend fun deleteForDoc(docId: String)

    /** Replace all duplicate paths recorded for a document. */
    @Transaction
    suspend fun replaceAll(
        docId: String,
        rows: List<DuplicateEntity>,
    ) {
        deleteForDoc(docId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface OcrQueueDao {
    @Query("SELECT * FROM ocr_queue WHERE state = 'pending' ORDER BY updatedAt LIMIT 1")
    suspend fun nextPending(): OcrQueueEntity?

    @Upsert
    suspend fun upsert(entry: OcrQueueEntity)

    @Query("SELECT * FROM ocr_queue ORDER BY updatedAt")
    fun observeQueue(): Flow<List<OcrQueueEntity>>

    /**
     * Rows still owed something: waiting, being worked on, or given up on after the attempt cap.
     * Excludes `done`, which this table keeps as history rather than deleting, for
     * [dev.npn.core.app.WorkFacade.queue].
     */
    @Query("SELECT * FROM ocr_queue WHERE state <> 'done' ORDER BY updatedAt")
    fun observeOutstanding(): Flow<List<OcrQueueEntity>>

    @Query("SELECT * FROM ocr_queue WHERE docId = :docId")
    suspend fun getByDocId(docId: String): OcrQueueEntity?

    /** Count of queue rows still to process (pending or running), for [dev.npn.core.app.OcrStatus]. */
    @Query("SELECT COUNT(*) FROM ocr_queue WHERE state IN ('pending', 'running')")
    fun countActive(): Flow<Int>

    @Query("DELETE FROM ocr_queue WHERE docId = :docId")
    suspend fun delete(docId: String)
}

@Dao
interface AiEnrichQueueDao {
    /**
     * Oldest due row, never-tried ones first.
     *
     * Ordering by [AiEnrichQueueEntity.attempts] before [AiEnrichQueueEntity.nextAttemptAt] is what
     * keeps one failing document from costing fresh imports their enrichment: a failure stops the
     * whole drain, so a retry that sorted ahead of a just-imported document would spend the kick on
     * itself and leave the new document waiting for the next one.
     */
    @Query("SELECT * FROM ai_enrich_queue WHERE nextAttemptAt <= :now ORDER BY attempts, nextAttemptAt LIMIT 1")
    suspend fun nextDue(now: Long): AiEnrichQueueEntity?

    @Upsert
    suspend fun upsert(entry: AiEnrichQueueEntity)

    @Query("SELECT * FROM ai_enrich_queue WHERE docId = :docId")
    suspend fun getByDocId(docId: String): AiEnrichQueueEntity?

    /** Rows owed a pass, including ones waiting out a retry backoff. */
    @Query("SELECT COUNT(*) FROM ai_enrich_queue")
    suspend fun countPending(): Int

    /**
     * Every row owed a pass, in the order [nextDue] will take them, for
     * [dev.npn.core.app.WorkFacade.queue]. Existence is the whole state here, so unlike the OCR
     * queue there is nothing to filter out.
     */
    @Query("SELECT * FROM ai_enrich_queue ORDER BY attempts, nextAttemptAt")
    fun observeQueue(): Flow<List<AiEnrichQueueEntity>>

    @Query("DELETE FROM ai_enrich_queue WHERE docId = :docId")
    suspend fun delete(docId: String)
}

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: KeyValueEntity)

    suspend fun put(
        key: String,
        value: String,
    ) = put(KeyValueEntity(key, value))

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String)
}

/** Custom-field key usage count, for the AI's list_fields tool. */
data class FieldKeyCount(
    val fieldKey: String,
    val count: Int,
)
