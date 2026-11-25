package dev.npn.core.mail

import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.db.MailCursorDao
import dev.npn.core.db.MailCursorEntity
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable

/**
 * [MailCursorStore] over `device.db`; the only copy of how far this device has read a folder.
 *
 * The `mail_cursor` row holds the position; where it was placed from, how a catch-up stands and
 * which messages were passed over live beside it in the device's key-value table, as JSON, so this
 * state needed no new schema version. Both are written in one transaction.
 *
 * Whether this device imports at all is a key of its own in the same table: one per library, as
 * `device.db` is.
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
        val progress =
            StoredProgress(
                startFrom = cursor.startFrom?.name,
                catchingUp = cursor.catchingUp,
                throughMillis = cursor.throughMillis,
                remaining = cursor.remaining,
                blockedUid = cursor.blockedUid,
                blockedAttempts = cursor.blockedAttempts,
                passedOver = cursor.passedOver,
            )
        dao.save(
            MailCursorEntity(
                accountKey = cursor.accountKey,
                folder = cursor.folder,
                uidValidity = cursor.uidValidity,
                lastUid = cursor.lastUid,
                lastCheckedAt = cursor.lastCheckedAtMillis,
                lastError = cursor.lastError?.name,
            ),
            KeyValueEntity(
                progressKey(cursor.accountKey, cursor.folder),
                NpnJson.Persisted.encodeToString(StoredProgress.serializer(), progress),
            ),
        )
    }

    override suspend fun enabledHere(): Boolean = kv.get(ENABLED_KEY) == "true"

    override suspend fun setEnabledHere(enabled: Boolean) {
        kv.put(ENABLED_KEY, enabled.toString())
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
            blockedUid = progress?.blockedUid,
            blockedAttempts = progress?.blockedAttempts ?: 0,
            passedOver = progress?.passedOver.orEmpty(),
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
        val blockedUid: Long? = null,
        val blockedAttempts: Int = 0,
        val passedOver: List<Long> = emptyList(),
    )

    private companion object {
        /** Whether this device checks the account: its own choice, never the library's. */
        const val ENABLED_KEY = "mail.enabledHere"

        /** The account key and folder joined by a character neither can hold. */
        fun progressKey(
            accountKey: String,
            folder: String,
        ): String = "mail.cursor.progress:$accountKey\u0000$folder"
    }
}
