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

    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun deleteByName(name: String)

    /**
     * Rename a tag across the tags table and every document_tags row, atomically.
     * Uses INSERT OR REPLACE semantics so a rename onto an existing tag merges them.
     */
    @Transaction
    suspend fun rename(oldName: String, newName: String) {
        renameTagRow(oldName, newName)
        renameDocumentTags(oldName, newName)
        // Any document_tags rows that failed to move because the doc already had newName
        // (duplicate PK) are removed by the INSERT OR IGNORE + delete pattern below.
        deleteDanglingDocumentTags(oldName)
    }

    @Query("UPDATE OR REPLACE tags SET name = :newName WHERE name = :oldName")
    suspend fun renameTagRow(oldName: String, newName: String)

    @Query("UPDATE OR IGNORE document_tags SET tag = :newName WHERE tag = :oldName")
    suspend fun renameDocumentTags(oldName: String, newName: String)

    @Query("DELETE FROM document_tags WHERE tag = :oldName")
    suspend fun deleteDanglingDocumentTags(oldName: String)

    @Query("SELECT * FROM tags")
    suspend fun all(): List<TagEntity>

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

    @Transaction
    suspend fun addTagToDocs(tag: String, docIds: List<String>) {
        insertDocumentTags(docIds.map { DocumentTagEntity(it, tag) })
    }

    @Query("DELETE FROM document_tags WHERE tag = :tag AND docId IN (:docIds)")
    suspend fun removeTagFromDocs(tag: String, docIds: List<String>)
}
