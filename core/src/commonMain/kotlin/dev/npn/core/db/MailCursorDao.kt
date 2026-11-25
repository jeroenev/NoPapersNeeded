package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

/** This device's mail cursors, one per account and folder (`device.db`). */
@Dao
interface MailCursorDao {
    @Query("SELECT * FROM mail_cursor WHERE accountKey = :accountKey AND folder = :folder")
    suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursorEntity?

    @Upsert
    suspend fun upsert(cursor: MailCursorEntity)

    /** The cursor's progress beside it, in the device's key-value table. */
    @Upsert
    suspend fun upsertProgress(entry: KeyValueEntity)

    /** The cursor row and its progress together: a crash never leaves one without the other. */
    @Transaction
    suspend fun save(
        cursor: MailCursorEntity,
        progress: KeyValueEntity,
    ) {
        upsert(cursor)
        upsertProgress(progress)
    }
}
