package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * The reminders this device already showed, in [DeviceDatabase]; see [ReminderNotificationEntity].
 * Rows are deliberately not pruned: they grow with documents × watched dates and keep a changed
 * due date from notifying twice, and `device.db` is never dropped.
 */
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

    /**
     * Every claim for one of [dueDates], whatever its document: a check looks among them for a
     * reminder an earlier version of a relinked document already showed.
     */
    @Query("SELECT * FROM reminder_notifications WHERE dueDate IN (:dueDates)")
    suspend fun onDates(dueDates: List<String>): List<ReminderNotificationEntity>

    /** Release one claim whose delivery failed, so a later check can show it. */
    @Query(
        "DELETE FROM reminder_notifications WHERE docId = :docId AND fieldKey = :fieldKey AND dueDate = :dueDate",
    )
    suspend fun release(
        docId: String,
        fieldKey: String,
        dueDate: String,
    )
}
