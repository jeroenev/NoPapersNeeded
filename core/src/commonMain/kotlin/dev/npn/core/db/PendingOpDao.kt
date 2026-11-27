package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

/** This device's open multi-step operations (`device.db`, [PendingOpEntity]). */
@Dao
interface PendingOpDao {
    @Upsert
    suspend fun open(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun close(id: String)

    @Query("SELECT * FROM pending_ops ORDER BY createdAt, id")
    suspend fun all(): List<PendingOpEntity>
}
