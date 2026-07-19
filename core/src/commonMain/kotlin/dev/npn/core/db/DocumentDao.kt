package dev.npn.core.db

import androidx.room3.Dao
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

    /**
     * Targeted update of the OCR bookkeeping columns. A partial UPDATE (not a row upsert) so a
     * background OCR write can never clobber metadata columns a concurrent journal fold just
     * projected onto the same row. [pageCount] only fills a NULL page count; a null [engine]
     * keeps the stored engine.
     */
    @Query(
        "UPDATE documents SET ocrState = :state, " +
            "pageCount = COALESCE(pageCount, :pageCount), " +
            "ocrEngine = COALESCE(:engine, ocrEngine) " +
            "WHERE docId = :docId",
    )
    suspend fun updateOcrState(docId: String, state: String, pageCount: Int?, engine: String?)

    @Query("SELECT * FROM documents WHERE docId = :docId")
    fun observeByDocId(docId: String): Flow<DocumentEntity?>

    /** Live tag list for one document (detail view); re-emits on document_tags changes. */
    @Query("SELECT tag FROM document_tags WHERE docId = :docId ORDER BY tag")
    fun observeTagsForDoc(docId: String): Flow<List<String>>

    /**
     * Filtered library listing. The upper layer builds the SQL (filters, sort, tag joins)
     * and passes it as a [RoomRawQuery]. Observed entities cover documents, tags, and custom
     * fields so tag/field edits re-emit the flow (tags and fields are attached in memory by
     * the repository on each emission).
     */
    @RawQuery(
        observedEntities = [
            DocumentEntity::class,
            DocumentTagEntity::class,
            DocumentFieldEntity::class,
        ],
    )
    fun observeLibraryRaw(query: RoomRawQuery): Flow<List<DocumentEntity>>

    // --- Canned queries ---

    @Query(
        "SELECT * FROM documents WHERE trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY addedAt DESC",
    )
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashed = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeTrashed(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE missing = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeMissing(): Flow<List<DocumentEntity>>

    // --- Counts ---

    @Query(
        "SELECT COUNT(*) FROM documents d " +
            "LEFT JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag IS NULL AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0",
    )
    fun countUntagged(): Flow<Int>

    /**
     * Review-queue count for INBOX_WORKFLOW mode: documents that are unreviewed OR have no tags.
     * (UNTAGGED_ONLY mode uses [countUntagged].)
     */
    @Query(
        "SELECT COUNT(*) FROM documents d WHERE d.trashed = 0 AND d.deleted = 0 AND d.missing = 0 " +
            "AND (d.reviewed = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM document_tags t WHERE t.docId = d.docId))",
    )
    fun countUnreviewedOrUntagged(): Flow<Int>

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
}
