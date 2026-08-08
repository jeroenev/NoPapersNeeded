package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Upsert
    suspend fun upsert(tag: TagEntity)

    @Delete
    suspend fun delete(tag: TagEntity)

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

    @Query("SELECT tag FROM document_tags WHERE docId = :docId ORDER BY tag")
    suspend fun tagsForDocument(docId: String): List<String>

    /** Replace the full tag set of a document atomically. */
    @Transaction
    suspend fun setForDocument(
        docId: String,
        tags: List<String>,
    ) {
        clearDocumentTags(docId)
        if (tags.isNotEmpty()) {
            insertDocumentTags(tags.map { DocumentTagEntity(docId, it) })
        }
    }

    /** Replace the full tag sets of several documents atomically, keyed by docId. */
    @Transaction
    suspend fun setForDocuments(updates: Map<String, List<String>>) {
        if (updates.isEmpty()) return
        chunkedDelete(updates.keys.toList()) { clearDocumentTagsFor(it) }
        insertDocumentTags(
            updates.flatMap { (docId, tags) ->
                tags.map { tag -> DocumentTagEntity(docId, tag) }
            },
        )
    }

    @Query("DELETE FROM document_tags WHERE docId = :docId")
    suspend fun clearDocumentTags(docId: String)

    @Query("DELETE FROM document_tags WHERE docId IN (:docIds)")
    suspend fun clearDocumentTagsFor(docIds: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTags(rows: List<DocumentTagEntity>)

    companion object {
        /** Chunked [clearDocumentTagsFor] so large doc sets stay under SQLite's bind-variable limit. */
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
