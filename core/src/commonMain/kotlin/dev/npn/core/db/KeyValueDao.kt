package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: KeyValueEntity)

    suspend fun put(
        key: String,
        value: String,
    ) = put(KeyValueEntity(key, value))

    @Upsert
    suspend fun putAll(entries: List<KeyValueEntity>)

    @Query("SELECT * FROM kv WHERE key IN (:keys)")
    suspend fun getChunk(keys: List<String>): List<KeyValueEntity>

    /** The stored value of each of [keys] that has one, read in bind-limit slices. */
    suspend fun getAll(keys: List<String>): Map<String, String> =
        keys.chunkedBind { getChunk(it) }.associate { it.key to it.value }

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String)
}
