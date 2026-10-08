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

    @Query("SELECT * FROM duplicates")
    suspend fun all(): List<DuplicateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DuplicateEntity>)

    @Query("DELETE FROM duplicates WHERE docId = :docId AND relPath = :relPath")
    suspend fun delete(
        docId: String,
        relPath: String,
    )

    @Query("DELETE FROM duplicates")
    suspend fun deleteAll()

    /**
     * Replace every recorded duplicate with [rows], what a rescan found: it lists the whole library,
     * so a copy it did not find is gone, or holds other bytes now. Unchanged, it writes nothing, so
     * the table's observers do not fire on every rescan.
     */
    @Transaction
    suspend fun replaceAll(rows: List<DuplicateEntity>) {
        if (all().toSet() == rows.toSet()) return
        deleteAll()
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
