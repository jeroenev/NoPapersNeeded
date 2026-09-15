package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface SyncRemoteDao {
    @Query("SELECT * FROM sync_remote_entries")
    suspend fun all(): List<SyncRemoteEntryEntity>

    @Upsert
    suspend fun upsertAll(entries: List<SyncRemoteEntryEntity>)

    @Query("DELETE FROM sync_remote_entries WHERE remotePath IN (:paths)")
    suspend fun deleteByPathsChunk(paths: List<String>)

    /** [deleteByPathsChunk] in bind-limit slices, however many paths there are. */
    suspend fun deleteByPaths(paths: List<String>) {
        for (chunk in paths.chunked(BIND_CHUNK)) deleteByPathsChunk(chunk)
    }

    @Query("DELETE FROM sync_remote_entries")
    suspend fun clear()
}
