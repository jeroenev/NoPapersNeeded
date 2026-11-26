package dev.npn.app.ui.reminders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.npn.app.platform.ReminderDelivery
import dev.npn.app.platform.ReminderTexts
import dev.npn.app.platform.remindersCheckedInApp
import dev.npn.app.platform.scheduleReminderChecks
import dev.npn.app.platform.showReminderNotifications
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderCandidate
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * The reminder check for as long as the library is open. On Android, WorkManager alone checks: a
 * change of the settings only (re)plans its job, run at once so a check due earlier today catches up.
 * On desktop the app checks itself, at the fixed morning time ([reminderCheckLoop]), once on opening
 * (a check missed while the app was closed) and again whenever the zone or the wall clock moves.
 * [hideDetails] is whether the app lock is on, read at each delivery.
 */
@Composable
internal fun ReminderChecks(
    session: LibrarySession,
    libraryLocation: String,
    hideDetails: () -> Boolean,
) {
    val hidden by rememberUpdatedState(hideDetails)
    LaunchedEffect(session, libraryLocation) {
        combine(session.reminders.settings, session.reminders.notifiesHere) { settings, here ->
            settings.isActive && here
        }.distinctUntilChanged().collectLatest { active ->
            if (!remindersCheckedInApp) {
                scheduleReminderChecks(active, 0L)
                return@collectLatest
            }
            if (!active) return@collectLatest
            val check: suspend () -> Unit = {
                deliverAfterRescan(session) {
                    deliverDueReminders(session, libraryLocation, ReminderTexts(hidden()))
                }
            }
            check()
            reminderCheckLoop(
                untilNext = { session.reminders.nextCheckDelayMillis() },
                zone = TimeZone::currentSystemDefault,
                check = check,
            )
        }
    }
}

/** Why [reminderCheckLoop] woke up to check; see [reminderWake]. */
internal enum class ReminderWake {
    /** The time zone changed: the morning time moved, maybe to a moment already past. */
    ZONE_CHANGED,

    /** The morning time came while the loop waited for it. */
    TRIGGER_DUE,

    /** The wall clock jumped past the morning time (a suspended laptop resumed): it was missed. */
    CLOCK_JUMPED,
}

/**
 * Whether a wait that began [before] millis ahead of the next check, and now finds it [after] millis
 * ahead, should check now: the zone changed, the wait was the last one before the check, or the
 * check's moment passed during it, which leaves the next one further off than before (the wait
 * itself only ever brings it closer).
 */
internal fun reminderWake(
    before: Long,
    after: Long,
    zoneChanged: Boolean,
): ReminderWake? =
    when {
        zoneChanged -> ReminderWake.ZONE_CHANGED
        before <= RECHECK_MS -> ReminderWake.TRIGGER_DUE
        after > before -> ReminderWake.CLOCK_JUMPED
        else -> null
    }

/**
 * Wait for the next check and run [check], for as long as [untilNext] has one, waking every
 * [RECHECK_MS] at most: a sleep measures the monotonic clock, which neither a suspended machine nor
 * a change of zone moves, so the loop looks at the wall clock again each time ([reminderWake]).
 */
internal suspend fun reminderCheckLoop(
    untilNext: suspend () -> Long?,
    zone: () -> TimeZone,
    check: suspend () -> Unit,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    var lastZone = zone()
    while (true) {
        val before = untilNext() ?: return
        sleep(before.coerceIn(MIN_WAIT_MS, RECHECK_MS))
        val currentZone = zone()
        val after = untilNext() ?: return
        val wake = reminderWake(before, after, zoneChanged = currentZone != lastZone)
        lastZone = currentZone
        if (wake != null) check()
    }
}

/** Claim what is due and show it, here and in the Android worker alike. */
internal suspend fun deliverDueReminders(
    session: LibrarySession,
    libraryLocation: String,
    texts: ReminderTexts,
) = deliverReminders(session) { due -> showReminderNotifications(libraryLocation, due, texts) }

/**
 * Claim the reminders due now and [show] them together; release every claim when nothing was shown
 * (no permission, no notification service, a failure), so a later check tries again.
 */
internal suspend fun deliverReminders(
    session: LibrarySession,
    show: suspend (List<ReminderCandidate>) -> ReminderDelivery,
) = withContext(NonCancellable) {
    val due =
        session.log
            .runLogged("Reading the reminders due now") {
                session.reminders.claimDueReminders()
            }.orEmpty()
    if (due.isEmpty()) return@withContext
    val delivery = session.log.runLogged("Showing reminders") { show(due) }
    if (delivery != ReminderDelivery.NOTIFIED) {
        due.forEach { session.reminders.releaseReminder(it) }
    }
}

/** How a background reminder run ([runReminderCheck]) ended, for the job that ran it. */
internal enum class ReminderRunOutcome {
    /** Finished, or given up on for today with the next morning's run planned. */
    DONE,

    /** Failed, and worth one more try soon. */
    RETRY,
}

/** How many times a background run is tried in a day before it waits for the next morning. */
internal const val MAX_REMINDER_ATTEMPTS = 2

/**
 * One background reminder run (Android's worker), attempt [attempt] counting from 0. The permission
 * comes first: while notifications are blocked, nothing opens the library or waits for its rescan.
 * [open] answers the library's session, opening it when the process has none, or null when no
 * library is set; it throws when the library will not open. [check] shows what is due. [plan] plans
 * the next run: from the session's reminders, or with none, for the next morning. A failure is tried
 * once more, then given up for the day: the job must not wake the device for ever.
 */
internal suspend fun runReminderCheck(
    attempt: Int,
    permitted: () -> Boolean,
    open: suspend () -> LibrarySession?,
    check: suspend (LibrarySession) -> Unit,
    plan: suspend (LibrarySession?) -> Unit,
    log: NpnLog,
): ReminderRunOutcome {
    if (!permitted()) {
        plan(null)
        return ReminderRunOutcome.DONE
    }
    val giveUp = attempt + 1 >= MAX_REMINDER_ATTEMPTS
    val session =
        runCatchingCancellable { open() }.getOrElse {
            log.warn("Opening the library for reminders failed", it)
            if (!giveUp) return ReminderRunOutcome.RETRY
            plan(null)
            return ReminderRunOutcome.DONE
        } ?: return ReminderRunOutcome.DONE
    val checked = runCatchingCancellable { check(session) }.onFailure { log.warn("Checking reminders failed", it) }
    if (checked.isFailure && !giveUp) return ReminderRunOutcome.RETRY
    plan(session)
    return ReminderRunOutcome.DONE
}

/** The longest the loop sleeps before it reads the wall clock and the zone again. */
internal const val RECHECK_MS = 60_000L
private const val MIN_WAIT_MS = 1_000L

/** Do not consume a claim until the index has had a successful initial reconciliation. */
internal suspend fun deliverAfterRescan(
    session: LibrarySession,
    deliver: suspend () -> Unit,
) {
    if (session.awaitInitialRescan()) deliver()
}
