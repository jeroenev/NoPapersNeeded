package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

/** The reminders this device already showed, in [DeviceDatabase]; see [ReminderNotificationEntity]. */
@Dao
interface ReminderNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ReminderNotificationEntity): Long

    /**
     * Insert [entries], answering only those this device had not recorded yet. The insert is the
     * claim: two checks racing for the same reminder, the in-app one and the Android worker, cannot
     * both get it back.
     */
    @Transaction
    suspend fun claim(entries: List<ReminderNotificationEntity>): List<ReminderNotificationEntity> =
        entries.filter { insert(it) != -1L }

    @Query("DELETE FROM reminder_notifications WHERE docId = :docId")
    suspend fun deleteForDocument(docId: String)
}
