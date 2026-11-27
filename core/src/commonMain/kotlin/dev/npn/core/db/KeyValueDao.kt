package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    /** [get] as a flow, for a per-device choice a screen and a check both follow. */
    @Query("SELECT value FROM kv WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Upsert
    suspend fun put(entry: KeyValueEntity)

    suspend fun put(
        key: String,
        value: String,
    ) = put(KeyValueEntity(key, value))

    @Upsert
    suspend fun putAll(entries: List<KeyValueEntity>)

    /** Store each of [entries] whose key has no value yet, keeping the ones that do. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putMissing(entries: List<KeyValueEntity>)

    @Query("SELECT * FROM kv WHERE key IN (:keys)")
    suspend fun getChunk(keys: List<String>): List<KeyValueEntity>

    /** The stored value of each of [keys] that has one, read in bind-limit slices. */
    suspend fun getAll(keys: List<String>): Map<String, String> =
        keys.chunkedBind { getChunk(it) }.associate { it.key to it.value }

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String)

    /** Every key starting with [prefix] (which holds no LIKE wildcard), in order. */
    @Query("SELECT key FROM kv WHERE key LIKE :prefix || '%' ORDER BY key")
    suspend fun keysWithPrefix(prefix: String): List<String>

    @Query("DELETE FROM kv WHERE key IN (:keys)")
    suspend fun deleteChunk(keys: List<String>)

    /** Delete [keys], in bind-limit slices. */
    suspend fun deleteAll(keys: List<String>) {
        keys.chunkedBind { deleteChunk(it).let { emptyList<Unit>() } }
    }

    /** How many keys start with [prefix] (which holds no LIKE wildcard). */
    @Query("SELECT COUNT(*) FROM kv WHERE key LIKE :prefix || '%'")
    suspend fun countWithPrefix(prefix: String): Int

    /** The value [offset] places from the greatest among the keys starting with [prefix]. */
    @Query("SELECT value FROM kv WHERE key LIKE :prefix || '%' ORDER BY value DESC LIMIT 1 OFFSET :offset")
    suspend fun valueWithPrefixFromNewest(
        prefix: String,
        offset: Int,
    ): String?

    /** Delete every key starting with [prefix] whose value sorts below [value]. */
    @Query("DELETE FROM kv WHERE key LIKE :prefix || '%' AND value < :value")
    suspend fun deleteWithPrefixBelow(
        prefix: String,
        value: String,
    )
}
