package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Upsert
    suspend fun upsertAll(tags: List<TagEntity>)

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

    /** Tag names starting with [prefix], for search autocomplete. */
    @Query("SELECT name FROM tags WHERE name LIKE :prefix ESCAPE '\\' ORDER BY name COLLATE NOCASE LIMIT :limit")
    suspend fun tagsForPrefix(
        prefix: String,
        limit: Int,
    ): List<String>

    @Query(
        "SELECT t.name AS name, t.color AS color, t.icon AS icon, " +
            "COUNT(dt.docId) AS count " +
            "FROM tags t LEFT JOIN document_tags dt ON t.name = dt.tag " +
            "GROUP BY t.name, t.color, t.icon ORDER BY t.name",
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    /** Tag counts limited to documents the AI search tools can reach. */
    @Query(
        "SELECT t.name AS name, t.color AS color, t.icon AS icon, COUNT(d.docId) AS count " +
            "FROM tags t LEFT JOIN document_tags dt ON t.name = dt.tag " +
            "LEFT JOIN documents d ON d.docId = dt.docId " +
            "AND d.trashed = 0 AND d.deleted = 0 AND d.missing = 0 " +
            "GROUP BY t.name, t.color, t.icon ORDER BY t.name",
    )
    fun observeAllWithLiveCounts(): Flow<List<TagWithCount>>

    /**
     * Remove tag rows that no non-deleted document references. Tags exist only through use: when
     * the last document drops a tag (untag, rename, merge, permanent delete) it vanishes from the
     * tag list; re-adding the name later brings it back (with its journal-kept style, if any).
     * Trashed documents keep their tags alive so restoring is lossless.
     */
    @Query(
        "DELETE FROM tags WHERE name NOT IN (" +
            "SELECT dt.tag FROM document_tags dt " +
            "JOIN documents d ON d.docId = dt.docId WHERE d.deleted = 0)",
    )
    suspend fun pruneUnreferenced()

    /** Doc ids currently tagged with [tag]. */
    @Query("SELECT docId FROM document_tags WHERE tag = :tag")
    suspend fun docIdsWithTag(tag: String): List<String>

    @Query(TAGS_FOR_DOCUMENT)
    suspend fun tagsForDocument(docId: String): List<String>

    /** [tagsForDocument], observed: re-emits on document_tags changes (detail view). */
    @Query(TAGS_FOR_DOCUMENT)
    fun observeTagsForDocument(docId: String): Flow<List<String>>

    /** All document tags for a set of docIds, for grouping list items. */
    @Query("SELECT docId, tag FROM document_tags WHERE docId IN (:docIds)")
    suspend fun tagsForDocsChunk(docIds: List<String>): List<DocumentTagEntity>

    /** Chunked [tagsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun tagsForDocs(docIds: List<String>): List<DocumentTagEntity> = docIds.chunkedBind { tagsForDocsChunk(it) }

    /** Replace the full tag sets of several documents atomically, keyed by docId. */
    @Transaction
    suspend fun setForDocuments(updates: Map<String, List<String>>) {
        if (updates.isEmpty()) return
        for (chunk in updates.keys.chunked(BIND_CHUNK)) clearDocumentTagsFor(chunk)
        insertDocumentTags(
            updates.flatMap { (docId, tags) ->
                tags.map { tag -> DocumentTagEntity(docId, tag) }
            },
        )
    }

    @Query("DELETE FROM document_tags WHERE docId IN (:docIds)")
    suspend fun clearDocumentTagsFor(docIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTags(rows: List<DocumentTagEntity>)
}

private const val TAGS_FOR_DOCUMENT = "SELECT tag FROM document_tags WHERE docId = :docId ORDER BY tag"
