package dev.npn.core.mail

/**
 * Where a check starts reading: the stored cursor, or one placed at the account's [MailStartPoint]
 * over an open connection. Kept apart from [MailService], which decides when to place it.
 */
internal object MailCursorPlacement {
    /**
     * The cursor this check reads from: the stored one, or one placed at the account's start point
     * when this device has not read the folder yet, the server renumbered it, or the start point
     * changed. Null when the server could not say where the start point is.
     *
     * A start moved further back moves the cursor back (the older mail is read again; dedupe by
     * hash keeps documents already in the library from being added twice); one moved later only
     * moves a cursor that is still behind it forward, past mail the user chose not to import.
     */
    suspend fun place(
        account: MailAccount,
        stored: MailCursor?,
        connection: MailConnection,
        now: Long,
    ): MailCursor? {
        val start = account.startFrom
        val validity = connection.uidValidity
        return when {
            stored?.uidValidity == null -> {
                val first = firstUid(connection, start, now) ?: return null
                (stored ?: MailCursor(account.accountKey, account.folder)).copy(
                    uidValidity = validity,
                    lastUid = first - 1,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = null,
                )
            }

            stored.uidValidity != validity -> {
                // A renumbered folder: the old UIDs mean nothing. Read from the start point, or from
                // a day before the last check when that is earlier, so mail that arrived since the
                // last check is not lost with the old numbering.
                val sinceLastCheck = stored.lastCheckedAtMillis?.minus(MailStartPoint.DAY_MILLIS)
                val first = firstUid(connection, start, now, sinceLastCheck) ?: return null
                stored.copy(
                    uidValidity = validity,
                    lastUid = first - 1,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = null,
                )
            }

            stored.startFrom != start -> {
                val placed = (firstUid(connection, start, now) ?: return null) - 1
                val previous = stored.startFrom
                val lastUid =
                    if (previous != null && start.reachesFurtherBackThan(previous)) {
                        minOf(stored.lastUid, placed)
                    } else {
                        // A cursor from before start points is treated like a later start: it never
                        // reads back past where it already was.
                        maxOf(stored.lastUid, placed)
                    }
                stored.copy(
                    lastUid = lastUid,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = stored.throughMillis.takeIf { lastUid == stored.lastUid },
                )
            }

            else -> {
                stored
            }
        }
    }

    /** The first UID [start] reads, or the earlier one [alsoSince] reaches; null when the server failed. */
    private suspend fun firstUid(
        connection: MailConnection,
        start: MailStartPoint,
        now: Long,
        alsoSince: Long? = null,
    ): Long? {
        if (start == MailStartPoint.EVERYTHING) return 1
        val since = listOfNotNull(start.sinceMillis(now), alsoSince).minOrNull() ?: return connection.uidNext
        return connection.firstUidSince(since)
    }
}
