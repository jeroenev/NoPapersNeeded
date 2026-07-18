package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FoldedSegmentDao {

    @Query("SELECT * FROM folded_segments")
    suspend fun allSegments(): List<FoldedSegmentEntity>

    @Query("SELECT * FROM folded_segments WHERE deviceId = :deviceId")
    suspend fun segmentsForDevice(deviceId: String): List<FoldedSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(segment: FoldedSegmentEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(segments: List<FoldedSegmentEntity>)

    @Query("DELETE FROM folded_segments WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: String)

    /** Refold a device's directory: replace the tracked segment set for that device. */
    @Transaction
    suspend fun replaceForDevice(deviceId: String, names: List<String>) {
        deleteForDevice(deviceId)
        insertAll(names.map { FoldedSegmentEntity(deviceId, it) })
    }
}

@Dao
interface SavedViewDao {

    @Query("SELECT * FROM saved_views ORDER BY position, name")
    fun observeAll(): Flow<List<SavedViewEntity>>

    @Upsert
    suspend fun upsert(view: SavedViewEntity)

    @Query("DELETE FROM saved_views WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ThumbnailDao {

    @Query("SELECT * FROM thumbnails WHERE docId = :docId")
    suspend fun get(docId: String): ThumbnailEntity?

    @Query("SELECT * FROM thumbnails")
    suspend fun getAll(): List<ThumbnailEntity>

    @Upsert
    suspend fun upsert(thumbnail: ThumbnailEntity)

    @Query("DELETE FROM thumbnails WHERE docId = :docId")
    suspend fun delete(docId: String)

    @Query("DELETE FROM thumbnails")
    suspend fun deleteAll()
}

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

    @Query("DELETE FROM duplicates WHERE relPath = :relPath")
    suspend fun deleteForPath(relPath: String)

    /** Replace all duplicate paths recorded for a document. */
    @Transaction
    suspend fun replaceAll(docId: String, rows: List<DuplicateEntity>) {
        deleteForDoc(docId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface OcrQueueDao {

    @Query("SELECT * FROM ocr_queue WHERE state = 'pending' ORDER BY updatedAt LIMIT 1")
    suspend fun nextPending(): OcrQueueEntity?

    @Upsert
    suspend fun upsert(entry: OcrQueueEntity)

    @Query("SELECT * FROM ocr_queue ORDER BY updatedAt")
    fun observeQueue(): Flow<List<OcrQueueEntity>>

    @Query("SELECT * FROM ocr_queue WHERE docId = :docId")
    suspend fun getByDocId(docId: String): OcrQueueEntity?

    /** Count of queue rows still to process (pending or running), for [dev.npn.core.app.OcrStatus]. */
    @Query("SELECT COUNT(*) FROM ocr_queue WHERE state IN ('pending', 'running')")
    fun countActive(): Flow<Int>

    @Query("DELETE FROM ocr_queue WHERE docId = :docId")
    suspend fun delete(docId: String)
}

@Dao
interface KeyValueDao {

    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: KeyValueEntity)

    suspend fun put(key: String, value: String) = put(KeyValueEntity(key, value))

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String)
}
