package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * SQL for the documents the library shows: not trashed, not deleted, and on disk. Expects
 * `documents` under the alias `d`.
 */
internal const val LIVE_DOCUMENT = "d.trashed = 0 AND d.deleted = 0 AND d.missing = 0"

@Dao
interface DocumentDao {
    @Upsert
    suspend fun upsert(doc: DocumentEntity)

    @Upsert
    suspend fun upsertAll(docs: List<DocumentEntity>)

    @Query("SELECT * FROM documents WHERE docId = :docId")
    suspend fun getByDocId(docId: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE relPath = :relPath")
    suspend fun getByRelPath(relPath: String): DocumentEntity?

    @Query(
        "SELECT docId, title, fileName FROM documents " +
            "WHERE docId IN (:docIds) AND trashed = 0 AND deleted = 0",
    )
    suspend fun titlesForChunk(docIds: List<String>): List<DocTitle>

    /**
     * Names for [docIds], skipping any that are trashed or tombstoned. A queue row outlives the
     * document it points at, so a caller that starts from a queue table must treat a missing
     * result as "no longer worth showing" rather than assuming one row back per id. Chunked under
     * SQLite's bind-variable limit.
     */
    suspend fun titlesFor(docIds: List<String>): List<DocTitle> = docIds.chunkedBind { titlesForChunk(it) }

    /** Bounded live document rows carrying an exact tag, for AI tool context. */
    @Query(
        "SELECT d.* FROM documents d INNER JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag = :tag AND $LIVE_DOCUMENT " +
            "ORDER BY d.addedAt DESC LIMIT :limit",
    )
    suspend fun getLiveWithTag(
        tag: String,
        limit: Int,
    ): List<DocumentEntity>

    /**
     * Targeted update of the OCR bookkeeping columns. A partial UPDATE (not a row upsert) so a
     * background OCR write can never clobber metadata columns a concurrent journal fold just
     * projected onto the same row. [pageCount] only fills a NULL page count; a null [engine] or
     * [source] keeps the stored value.
     */
    @Query(
        "UPDATE documents SET ocrState = :state, " +
            "pageCount = COALESCE(pageCount, :pageCount), " +
            "ocrEngine = COALESCE(:engine, ocrEngine), " +
            "ocrSource = COALESCE(:source, ocrSource) " +
            "WHERE docId = :docId",
    )
    suspend fun updateOcrState(
        docId: String,
        state: String,
        pageCount: Int?,
        engine: String?,
        source: String? = null,
    )

    /**
     * Fill in a page count the row does not have yet, the way every background writer that opens
     * a PDF anyway records one. A partial UPDATE, never a row upsert: such a writer read the row
     * before its slow open, and writing that copy back would revert whatever a fold projected onto
     * the row meanwhile (a move, a title, the OCR state). A count already there is left alone.
     */
    @Query("UPDATE documents SET pageCount = :pageCount WHERE docId = :docId AND pageCount IS NULL")
    suspend fun fillPageCount(
        docId: String,
        pageCount: Int,
    )

    /**
     * Point a document at the place its bytes were just moved to. A partial UPDATE, not a row
     * upsert, so columns another writer set since the fold read the row survive the move.
     */
    @Query(
        "UPDATE documents SET relPath = :relPath, fileName = :fileName, size = :size, mtime = :mtime, " +
            "missing = 0 WHERE docId = :docId",
    )
    suspend fun relocate(
        docId: String,
        relPath: String,
        fileName: String,
        size: Long,
        mtime: Long,
    )

    @Query("SELECT * FROM documents WHERE docId = :docId")
    fun observeByDocId(docId: String): Flow<DocumentEntity?>

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

    @Query("SELECT * FROM documents d WHERE $LIVE_DOCUMENT ORDER BY addedAt DESC")
    suspend fun getAllLive(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE trashed = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeTrashed(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE missing = 1 AND deleted = 0 ORDER BY addedAt DESC")
    fun observeMissing(): Flow<List<DocumentEntity>>

    // --- Counts ---

    @Query(
        "SELECT COUNT(*) FROM documents d " +
            "LEFT JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag IS NULL AND $LIVE_DOCUMENT",
    )
    fun countUntagged(): Flow<Int>

    /**
     * Review-queue count for INBOX_WORKFLOW mode: documents that are unreviewed OR have no tags.
     * (UNTAGGED_ONLY mode uses [countUntagged].)
     */
    @Query(
        "SELECT COUNT(*) FROM documents d WHERE $LIVE_DOCUMENT " +
            "AND (d.reviewed = 0 OR NOT EXISTS " +
            "(SELECT 1 FROM document_tags t WHERE t.docId = d.docId))",
    )
    fun countUnreviewedOrUntagged(): Flow<Int>

    /**
     * Live documents not reviewed yet, or with [taggedOnly] only those among them with a tag: what
     * the review migration marks.
     */
    @Query(
        "SELECT d.docId FROM documents d WHERE d.reviewed = 0 AND $LIVE_DOCUMENT " +
            "AND (:taggedOnly = 0 OR EXISTS " +
            "(SELECT 1 FROM document_tags t WHERE t.docId = d.docId))",
    )
    suspend fun unreviewedLiveDocIds(taggedOnly: Boolean): List<String>

    /** Live documents whose OCR state is [ocrState]: what a rescan still has to queue for OCR. */
    @Query("SELECT d.docId FROM documents d WHERE d.ocrState = :ocrState AND $LIVE_DOCUMENT")
    suspend fun liveDocIdsWithOcrState(ocrState: String): List<String>

    /** Projection for the indexer diff: current known paths + fast-path fields. */
    @Query("SELECT relPath, docId, size, mtime FROM documents WHERE deleted = 0")
    suspend fun allPathsAndMeta(): List<PathMeta>

    /** Every live (not deleted) document's trash time, for the trash auto-purge. */
    @Query("SELECT docId, trashedAt FROM documents WHERE deleted = 0")
    suspend fun trashTimes(): List<DocTrashTime>

    /** All documents for a set of docIds, for the bulk fold and FTS-first list fetch. */
    @Query("SELECT * FROM documents WHERE docId IN (:docIds)")
    suspend fun getByDocIdsChunk(docIds: List<String>): List<DocumentEntity>

    /** Chunked [getByDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun getByDocIds(docIds: List<String>): List<DocumentEntity> = docIds.chunkedBind { getByDocIdsChunk(it) }

    /** Drop one row outright: only for taking back an import nothing else has seen yet. */
    @Query("DELETE FROM documents WHERE docId = :docId")
    suspend fun delete(docId: String)
}
