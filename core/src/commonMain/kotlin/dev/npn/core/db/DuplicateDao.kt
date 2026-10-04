package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM duplicates WHERE docId IN (:docIds)")
    suspend fun deleteForDocs(docIds: List<String>)

    /** Replace the duplicate paths recorded for each of [docIds] with those in [rows], at once. */
    @Transaction
    suspend fun replaceForDocs(
        docIds: Collection<String>,
        rows: List<DuplicateEntity>,
    ) {
        for (chunk in docIds.chunked(BIND_CHUNK)) deleteForDocs(chunk)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
