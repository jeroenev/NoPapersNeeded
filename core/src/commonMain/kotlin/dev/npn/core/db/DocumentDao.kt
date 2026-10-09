package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.RawQuery
import androidx.room3.RoomRawQuery
import androidx.room3.Transaction
import androidx.room3.Upsert
import dev.npn.core.fs.NpnPaths
import kotlinx.coroutines.flow.Flow

/**
 * SQL for the documents the library shows: not trashed, not deleted, and on disk. Expects
 * `documents` under the alias `d`.
 */
internal const val LIVE_DOCUMENT = "d.trashed = 0 AND d.deleted = 0 AND d.missing = 0"

/**
 * Where a row waits while its document holds no path in the library: its file was replaced in
 * place, or another document took the path. Under `.npn/`, so never indexed, and one per document,
 * as the unique relPath requires.
 */
internal fun missingSentinel(docId: String): String = MISSING_SENTINEL_PREFIX + docId

private const val MISSING_SENTINEL_PREFIX = "${NpnPaths.NPN_DIR}/missing/"

@Dao
interface DocumentDao {
    suspend fun upsert(doc: DocumentEntity) = upsertAll(listOf(doc))

    /**
     * Write [docs], in order, as whole rows. relPath is unique, and this is where a path's holder is
     * decided: a hidden row (trashed, deleted or missing) gives its path up to a live one and waits
     * at its [missingSentinel]; any other row landing on a held path fails the write. Room's
     * `@Upsert` alone turns that conflict into an UPDATE that matches no row, so a new document
     * would vanish while the write reports success.
     */
    @Transaction
    suspend fun upsertAll(docs: List<DocumentEntity>) {
        if (docs.isEmpty()) return
        for (paths in docs.filter { it.isLive }.map { it.relPath }.chunked(BIND_CHUNK)) {
            releaseHiddenHolders(paths, MISSING_SENTINEL_PREFIX)
        }
        upsertUnchecked(docs)
        val ids = docs.mapTo(LinkedHashSet()) { it.docId }.toList()
        val written = ids.chunkedBind { storedDocIdsChunk(it) }.toHashSet()
        val dropped = ids.filterNot(written::contains)
        check(dropped.isEmpty()) { "Another live document holds the path of ${dropped.joinToString()}" }
    }

    /** Room's upsert without [upsertAll]'s rule for who holds a path; write through that instead. */
    @Upsert
    suspend fun upsertUnchecked(docs: List<DocumentEntity>)

    /** Move every hidden row at one of [paths] to its [missingSentinel], so a live row can take it. */
    @Query(
        "UPDATE documents SET relPath = :sentinelPrefix || docId " +
            "WHERE relPath IN (:paths) AND (trashed = 1 OR deleted = 1 OR missing = 1)",
    )
    suspend fun releaseHiddenHolders(
        paths: List<String>,
        sentinelPrefix: String,
    )

    @Query("SELECT docId FROM documents WHERE docId IN (:docIds)")
    suspend fun storedDocIdsChunk(docIds: List<String>): List<String>

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
    @Query("SELECT relPath, docId, size, mtime, (trashed OR missing) AS hidden FROM documents WHERE deleted = 0")
    suspend fun allPathsAndMeta(): List<PathMeta>

    /** Every live (not deleted) document's trash time, for the trash auto-purge. */
    @Query("SELECT docId, trashedAt FROM documents WHERE deleted = 0")
    suspend fun trashTimes(): List<DocTrashTime>

    /** All documents for a set of docIds, for the bulk fold and FTS-first list fetch. */
    @Query("SELECT * FROM documents WHERE docId IN (:docIds)")
    suspend fun getByDocIdsChunk(docIds: List<String>): List<DocumentEntity>

    /** Chunked [getByDocIdsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun getByDocIds(docIds: List<String>): List<DocumentEntity> = docIds.chunkedBind { getByDocIdsChunk(it) }

    /**
     * Drop one row outright: only for taking back an import (`ImportService.undoImport`), or a row
     * written ahead of a commit that then failed (`Indexer.putBack`).
     */
    @Query("DELETE FROM documents WHERE docId = :docId")
    suspend fun delete(docId: String)
}
