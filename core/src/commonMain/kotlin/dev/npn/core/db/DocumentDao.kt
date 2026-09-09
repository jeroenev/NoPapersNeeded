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

    @Query("SELECT * FROM documents WHERE relPath = :relPath")
    suspend fun getByRelPath(relPath: String): DocumentEntity?

    /**
     * Names for [docIds], skipping any that are trashed or tombstoned. A queue row outlives the
     * document it points at, so a caller that starts from a queue table must treat a missing
     * result as "no longer worth showing" rather than assuming one row back per id.
     */
    @Query(
        "SELECT docId, title, fileName FROM documents " +
            "WHERE docId IN (:docIds) AND trashed = 0 AND deleted = 0",
    )
    suspend fun titlesFor(docIds: List<String>): List<DocTitle>

    /** Doc ids currently tagged with [tag]. */
    @Query("SELECT docId FROM document_tags WHERE tag = :tag")
    suspend fun getDocIdsWithTag(tag: String): List<String>

    /** Bounded live document rows carrying an exact tag, for AI tool context. */
    @Query(
        "SELECT d.* FROM documents d INNER JOIN document_tags t ON d.docId = t.docId " +
            "WHERE t.tag = :tag AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0 " +
            "ORDER BY d.addedAt DESC LIMIT :limit",
    )
    suspend fun getLiveWithTag(
        tag: String,
        limit: Int,
    ): List<DocumentEntity>

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
    suspend fun updateOcrState(
        docId: String,
        state: String,
        pageCount: Int?,
        engine: String?,
    )

    /**
     * Stamp the post-OCR AI enrichment marker. A partial UPDATE for the same reason as
     * [updateOcrState]: enrichment runs off the OCR thread, so a row upsert could clobber metadata
     * columns a concurrent journal fold just projected — including the ones enrichment itself
     * wrote.
     *
     * This protects the fold from the stamp, not the stamp from the fold: a fold that preloaded
     * this row before the stamp and flushes its full-row upsert after will reset the column, same
     * as it can for `ocrState`. Harmless here — the marker only suppresses repeat model calls, so
     * losing it costs at most one extra pass if the document is ever re-OCR'd.
     */
    @Query("UPDATE documents SET aiEnrichedAt = :at WHERE docId = :docId")
    suspend fun markAiEnriched(
        docId: String,
        at: Long,
    )

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

    /**
     * Live count of documents matching a WHERE clause assembled by the upper layer. Shares the
     * [observeLibraryRaw] invalidation set so tag/field edits re-emit the count.
     */
    @RawQuery(observedEntities = [DocumentEntity::class, DocumentTagEntity::class])
    fun observeCount(query: RoomRawQuery): Flow<Int>

    @Query(
        "SELECT * FROM documents WHERE trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY addedAt DESC",
    )
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE trashed = 0 AND deleted = 0 AND missing = 0 ORDER BY addedAt DESC")
    suspend fun getAllLive(): List<DocumentEntity>

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

    /** One-shot variant of [observeCorrespondents], for AI suggestion canonicalization. */
    @Query(
        "SELECT DISTINCT correspondent FROM documents " +
            "WHERE correspondent IS NOT NULL AND correspondent != '' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY correspondent COLLATE NOCASE",
    )
    suspend fun correspondents(): List<String>

    /** Distinct non-null document types on live documents, for filter autocomplete. */
    @Query(
        "SELECT DISTINCT docType FROM documents " +
            "WHERE docType IS NOT NULL AND docType != '' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY docType COLLATE NOCASE",
    )
    fun observeDocTypes(): Flow<List<String>>

    /** Distinct non-null correspondents starting with [prefix], for search autocomplete. */
    @Query(
        "SELECT DISTINCT correspondent FROM documents " +
            "WHERE correspondent IS NOT NULL AND correspondent != '' " +
            "AND correspondent LIKE :prefix ESCAPE '\\' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY correspondent COLLATE NOCASE LIMIT :limit",
    )
    suspend fun correspondentsForPrefix(
        prefix: String,
        limit: Int,
    ): List<String>

    /** Distinct non-null document types starting with [prefix], for search autocomplete. */
    @Query(
        "SELECT DISTINCT docType FROM documents " +
            "WHERE docType IS NOT NULL AND docType != '' " +
            "AND docType LIKE :prefix ESCAPE '\\' " +
            "AND trashed = 0 AND deleted = 0 AND missing = 0 " +
            "ORDER BY docType COLLATE NOCASE LIMIT :limit",
    )
    suspend fun docTypesForPrefix(
        prefix: String,
        limit: Int,
    ): List<String>

    /** All document tags for a set of docIds, for grouping list items. */
    @Query("SELECT docId, tag FROM document_tags WHERE docId IN (:docIds)")
    suspend fun tagsForDocsChunk(docIds: List<String>): List<DocumentTagEntity>

    /** Chunked [tagsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun tagsForDocs(docIds: List<String>): List<DocumentTagEntity> =
        chunkedQuery(docIds) { tagsForDocsChunk(it) }

    /** All documents for a set of docIds, for the bulk fold and FTS-first list fetch. */
    @Query("SELECT * FROM documents WHERE docId IN (:docIds)")
    suspend fun getByDocIdsChunk(docIds: List<String>): List<DocumentEntity>

    /** Chunked [getByDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun getByDocIds(docIds: List<String>): List<DocumentEntity> = chunkedQuery(docIds) { getByDocIdsChunk(it) }

    @Query("DELETE FROM documents")
    suspend fun clearAll()

    companion object {
        const val BIND_CHUNK = 500

        private suspend fun <T> chunkedQuery(
            ids: List<String>,
            block: suspend (List<String>) -> List<T>,
        ): List<T> {
            if (ids.isEmpty()) return emptyList()
            if (ids.size <= BIND_CHUNK) return block(ids)
            val out = ArrayList<T>(ids.size)
            for (i in ids.indices step BIND_CHUNK) {
                out += block(ids.subList(i, minOf(i + BIND_CHUNK, ids.size)))
            }
            return out
        }
    }
}
