package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
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
}
