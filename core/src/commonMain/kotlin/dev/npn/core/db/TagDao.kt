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
    suspend fun tagsForPrefix(prefix: String, limit: Int): List<String>

    @Query(
        "SELECT t.name AS name, t.color AS color, t.icon AS icon, " +
            "COUNT(dt.docId) AS count " +
            "FROM tags t LEFT JOIN document_tags dt ON t.name = dt.tag " +
            "GROUP BY t.name, t.color, t.icon ORDER BY t.name",
    )
    fun observeAllWithCounts(): Flow<List<TagWithCount>>

    @Query("SELECT tag FROM document_tags WHERE docId = :docId ORDER BY tag")
    suspend fun tagsForDocument(docId: String): List<String>

    /** Replace the full tag set of a document atomically. */
    @Transaction
    suspend fun setForDocument(docId: String, tags: List<String>) {
        clearDocumentTags(docId)
        if (tags.isNotEmpty()) {
            insertDocumentTags(tags.map { DocumentTagEntity(docId, it) })
        }
    }

    @Query("DELETE FROM document_tags WHERE docId = :docId")
    suspend fun clearDocumentTags(docId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocumentTags(rows: List<DocumentTagEntity>)
}
