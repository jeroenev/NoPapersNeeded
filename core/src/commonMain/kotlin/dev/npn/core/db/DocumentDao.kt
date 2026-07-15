package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Upsert
    suspend fun upsert(doc: DocumentEntity)

    @Upsert
    suspend fun upsertAll(docs: List<DocumentEntity>)

    @Query("SELECT * FROM documents WHERE docId = :docId")
    suspend fun getByDocId(docId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE relPath = :relPath")
    suspend fun getByPath(relPath: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE docId = :docId")
    fun observeByDocId(docId: String): Flow<DocumentEntity?>

    @Query("DELETE FROM documents WHERE docId = :docId")
    suspend fun deleteByDocId(docId: String)

    /**
     * Filtered library listing. The upper layer builds the SQL (filters, sort, tag joins)
     * and passes it as a [RoomRawQuery]. Observed entities cover both tables so tag changes
     * re-emit the flow.
     */
    @RawQuery(observedEntities = [DocumentEntity::class, DocumentTagEntity::class])
    fun observeLibraryRaw(query: RoomRawQuery): Flow<List<DocumentEntity>>

    // --- Canned queries ---

    @Query(
        "SELECT * FROM documents WHERE trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY addedAt DESC",
    )
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query(
        "SELECT * FROM documents WHERE status = :status AND trashed = 0 AND deleted = 0 " +
            "AND missing = 0 ORDER BY addedAt DESC",
    )
    fun observeByStatus(status: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashed = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeTrashed(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE missing = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeMissing(): Flow<List<DocumentEntity>>

    @Query(
        "SELECT d.* FROM documents d " +
            "LEFT JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag IS NULL AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0 " +
            "ORDER BY d.addedAt DESC",
    )
    fun observeUntagged(): Flow<List<DocumentEntity>>

    // --- Counts ---

    @Query(
        "SELECT COUNT(*) FROM documents WHERE status = 'inbox' AND trashed = 0 " +
            "AND deleted = 0 AND missing = 0",
    )
    fun countInbox(): Flow<Int>

    @Query(
        "SELECT COUNT(*) FROM documents d " +
            "LEFT JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag IS NULL AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0",
    )
    fun countUntagged(): Flow<Int>

    @Query("SELECT COUNT(*) FROM documents WHERE trashed = 1 AND deleted = 0")
    fun countTrash(): Flow<Int>

    /** Projection for the indexer diff: current known paths + fast-path fields. */
    @Query("SELECT relPath, docId, size, mtime FROM documents WHERE deleted = 0")
    suspend fun allPathsAndMeta(): List<PathMeta>

    /** Distinct non-null correspondents on live documents, for filter autocomplete. */
    @Query(
        "SELECT DISTINCT correspondent FROM documents " +
            "WHERE correspondent IS NOT NULL AND correspondent != '' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY correspondent COLLATE NOCASE",
    )
    fun observeCorrespondents(): Flow<List<String>>

    /** Distinct non-null document types on live documents, for filter autocomplete. */
    @Query(
        "SELECT DISTINCT docType FROM documents " +
            "WHERE docType IS NOT NULL AND docType != '' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY docType COLLATE NOCASE",
    )
    fun observeDocTypes(): Flow<List<String>>

    /** All document tags for a set of docIds, for grouping list items. */
    @Query("SELECT docId, tag FROM document_tags WHERE docId IN (:docIds)")
    suspend fun tagsForDocs(docIds: List<String>): List<DocumentTagEntity>

    @Query("DELETE FROM documents")
    suspend fun clearAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertForTest(doc: DocumentEntity)
}
