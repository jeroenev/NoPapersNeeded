package dev.npn.core.mail

import dev.npn.core.db.MailCursorDao
import dev.npn.core.db.MailCursorEntity

/** [MailCursorStore] over `device.db`; the only copy of how far this device has read a folder. */
internal class DeviceMailCursors(
    private val dao: MailCursorDao,
) : MailCursorStore {
    override suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursor? = dao.get(accountKey, folder)?.toModel()

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
    }

    private fun MailCursorEntity.toModel(): MailCursor =
        MailCursor(
            accountKey = accountKey,
            folder = folder,
            uidValidity = uidValidity,
            lastUid = lastUid,
            lastCheckedAtMillis = lastCheckedAt,
            lastError = lastError?.let { stored -> MailFailureReason.entries.firstOrNull { it.name == stored } },
        )
}
