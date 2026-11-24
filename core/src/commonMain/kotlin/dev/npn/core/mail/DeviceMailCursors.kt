package dev.npn.core.mail

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.MailCursorDao
import dev.npn.core.db.MailCursorEntity
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable

/**
 * [MailCursorStore] over `device.db`; the only copy of how far this device has read a folder.
 *
 * The `mail_cursor` row holds the position; where it was placed from and how a catch-up stands
 * live beside it in the device's key-value table, as JSON, so this state needed no new schema
 * version. The row is written first: a crash between the two writes leaves the old start point
 * recorded, so the next check places the cursor again (an idempotent step) rather than skipping
 * the placement and the mail it would have read.
 */
internal class DeviceMailCursors(
    private val dao: MailCursorDao,
    private val kv: KeyValueDao,
) : MailCursorStore {
    override suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursor? {
        val row = dao.get(accountKey, folder) ?: return null
        val progress =
            kv.get(progressKey(accountKey, folder))?.let { stored ->
                runCatching { NpnJson.Persisted.decodeFromString(StoredProgress.serializer(), stored) }.getOrNull()
            }
        return row.toModel(progress)
    }

    override suspend fun save(cursor: MailCursor) {
        dao.upsert(
            MailCursorEntity(
                accountKey = cursor.accountKey,
                folder = cursor.folder,
                uidValidity = cursor.uidValidity,
                lastUid = cursor.lastUid,
                lastCheckedAt = cursor.lastCheckedAtMillis,
                lastError = cursor.lastError?.name,
            ),
        )
        val progress =
            StoredProgress(
                startFrom = cursor.startFrom?.name,
                catchingUp = cursor.catchingUp,
                throughMillis = cursor.throughMillis,
                remaining = cursor.remaining,
            )
        kv.put(
            progressKey(cursor.accountKey, cursor.folder),
            NpnJson.Persisted.encodeToString(StoredProgress.serializer(), progress),
        )
    }

    private fun MailCursorEntity.toModel(progress: StoredProgress?): MailCursor =
        MailCursor(
            accountKey = accountKey,
            folder = folder,
            uidValidity = uidValidity,
            lastUid = lastUid,
            lastCheckedAtMillis = lastCheckedAt,
            lastError = lastError?.let { stored -> MailFailureReason.entries.firstOrNull { it.name == stored } },
            startFrom = progress?.startFrom?.let { stored -> MailStartPoint.entries.firstOrNull { it.name == stored } },
            catchingUp = progress?.catchingUp ?: false,
            throughMillis = progress?.throughMillis,
            remaining = progress?.remaining,
        )

    /**
     * The cursor's progress as the key-value table keeps it. The start point is its name, so one
     * this build does not know reads as none and the cursor is placed again.
     */
    @Serializable
    private data class StoredProgress(
        val startFrom: String? = null,
        val catchingUp: Boolean = false,
        val throughMillis: Long? = null,
        val remaining: Int? = null,
    )

    private companion object {
        /** The account key and folder joined by a character neither can hold. */
        fun progressKey(
            accountKey: String,
            folder: String,
        ): String = "mail.cursor.progress:$accountKey\u0000$folder"
    }
}
