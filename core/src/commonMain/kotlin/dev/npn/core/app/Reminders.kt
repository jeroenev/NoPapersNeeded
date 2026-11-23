package dev.npn.core.app

import dev.npn.core.db.ReminderFieldRow
import dev.npn.core.db.ReminderNotificationDao
import dev.npn.core.db.ReminderNotificationEntity
import dev.npn.core.db.defs
import dev.npn.core.db.toFieldDef
import dev.npn.core.index.JournalCoordinator
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Which part of the reminder window a due date falls in. The library's Upcoming view groups by
 * these, and the first two are "due now" for its badge.
 */
enum class ReminderBucket {
    OVERDUE,
    TODAY,
    NEXT_7_DAYS,
    LATER,
}

/**
 * The library's reminder settings. Library data, synced through the journal like the trash
 * retention: which date fields to watch, how many days ahead to warn, and the local time of the
 * daily check. [enabled] false is the default, so a library that never opted in does nothing.
 */
data class ReminderSettings(
    val enabled: Boolean = false,
    val fieldKeys: List<String> = emptyList(),
    val leadDays: Int = DEFAULT_LEAD_DAYS,
    val timeOfDay: LocalTime = DEFAULT_TIME,
) {
    /** Whether reminders can produce anything: on, with at least one date field chosen. */
    val isActive: Boolean get() = enabled && fieldKeys.isNotEmpty()

    companion object {
        const val DEFAULT_LEAD_DAYS = 7
        val DEFAULT_TIME = LocalTime(9, 0)
        val LEAD_DAY_RANGE = 0..365
    }
}

/**
 * One date on a live document that falls in the reminder window, for the Upcoming list. At most one
 * is kept per document (the earliest of its selected reminder dates): the list is about documents,
 * not about every field a document happens to carry.
 */
data class UpcomingReminder(
    val doc: Doc,
    val fieldKey: String,
    val fieldLabel: String,
    val dueDate: LocalDate,
    val bucket: ReminderBucket,
)

/** One reminder a check has claimed for notification; see [RemindersFacade.claimDueReminders]. */
data class ReminderCandidate(
    val docId: String,
    val documentTitle: String,
    val fieldKey: String,
    val fieldLabel: String,
    val dueDate: LocalDate,
    val bucket: ReminderBucket,
)

/**
 * Reminders over the library's date fields: which fields are watched, and the Upcoming list and
 * daily notification check built from them. The settings are synced journal data; the record of
 * what this device already notified is its own (`device.db`), so another device notifies once too.
 */
interface RemindersFacade {
    val settings: Flow<ReminderSettings>

    suspend fun setEnabled(enabled: Boolean)

    /** Replace the watched fields; keys that are not registered DATE fields are dropped. */
    suspend fun setFieldKeys(keys: List<String>)

    /** Days before a due date at which it enters the notification window, one of [ReminderSettings.LEAD_DAY_RANGE]. */
    suspend fun setLeadDays(days: Int)

    suspend fun setTimeOfDay(time: LocalTime)

    /** Every live document with one of the watched dates, grouped by bucket and date for the Upcoming screen. */
    fun observeUpcoming(): Flow<List<UpcomingReminder>>

    /**
     * How long until the next daily check should run, on this device's clock and time zone; null
     * while reminders are off. After a check, the next one is the following day, never the same
     * instant again.
     */
    suspend fun nextCheckDelayMillis(): Long?

    /**
     * The reminders due at this moment that this device has not notified yet, claimed in `device.db`
     * so a second check (an in-app one and the Android worker, for instance) cannot repeat them.
     * Empty before the configured time of day.
     */
    suspend fun claimDueReminders(): List<ReminderCandidate>

    /**
     * Release a claim whose delivery failed or was refused, so the next check can try it again.
     * The claim is the anti-duplicate guard, not a statement that the user saw it.
     */
    suspend fun releaseReminder(candidate: ReminderCandidate)
}

/**
 * The pure reminder-window math, kept out of the database and the clock so the same rules are
 * tested directly: which bucket a date is in, and when the next daily check is due.
 */
object ReminderWindow {
    /** Which group [dueDate] belongs to today; "next 7 days" is tomorrow through today + 7. */
    fun bucket(
        dueDate: LocalDate,
        today: LocalDate,
    ): ReminderBucket =
        when {
            dueDate < today -> ReminderBucket.OVERDUE
            dueDate == today -> ReminderBucket.TODAY
            dueDate <= today.plus(DatePeriod(days = 7)) -> ReminderBucket.NEXT_7_DAYS
            else -> ReminderBucket.LATER
        }

    /** The local date at [nowMillis] in [zone]. */
    fun today(
        nowMillis: Long,
        zone: TimeZone,
    ): LocalDate = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date

    /** The local time at [nowMillis] in [zone]. */
    fun localTime(
        nowMillis: Long,
        zone: TimeZone,
    ): LocalTime = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).time

    /** Epoch millis of [timeOfDay] on [date] in [zone], as the zone maps that wall time. */
    fun triggerAt(
        date: LocalDate,
        timeOfDay: LocalTime,
        zone: TimeZone,
    ): Long = date.atTime(timeOfDay).toInstant(zone).toEpochMilliseconds()

    /**
     * Millis until the next daily check strictly after [nowMillis]: today's time when it is still
     * ahead, else tomorrow's. A check that ran at a time now past therefore waits for tomorrow.
     */
    fun delayUntilNextTrigger(
        nowMillis: Long,
        zone: TimeZone,
        timeOfDay: LocalTime,
    ): Long {
        val today = today(nowMillis, zone)
        val todayTrigger = triggerAt(today, timeOfDay, zone)
        val trigger =
            if (nowMillis < todayTrigger) {
                todayTrigger
            } else {
                triggerAt(today.plus(DatePeriod(days = 1)), timeOfDay, zone)
            }
        return trigger - nowMillis
    }

    /**
     * Whether a check at [nowMillis] may notify: from the configured time of today on. Before it,
     * an in-app check that happens to run (startup, a settings change) stays quiet and waits.
     */
    fun checkAllowed(
        nowMillis: Long,
        zone: TimeZone,
        timeOfDay: LocalTime,
    ): Boolean = localTime(nowMillis, zone) >= timeOfDay

    /** The last date a notification window covers: [today] plus [leadDays] days. */
    fun windowEnd(
        today: LocalDate,
        leadDays: Int,
    ): LocalDate = today.plus(DatePeriod(days = leadDays.coerceIn(ReminderSettings.LEAD_DAY_RANGE)))

    /**
     * The first date a notification window covers: [today] minus [leadDays] days. Upcoming still
     * shows every overdue date, but an archive's years-old invoices must not all notify on the day
     * reminders are first enabled.
     */
    fun windowStart(
        today: LocalDate,
        leadDays: Int,
    ): LocalDate = today.plus(DatePeriod(days = -leadDays.coerceIn(ReminderSettings.LEAD_DAY_RANGE)))
}

/** `HH:mm` as the settings file and journal store it. */
fun formatReminderTime(time: LocalTime): String =
    time.hour.toString().padStart(2, '0') + ":" + time.minute.toString().padStart(2, '0')

/** Parse the stored `HH:mm`; null for anything else, including a newer build's format. */
fun parseReminderTime(text: String): LocalTime? {
    val parts = text.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime(hour, minute)
}

/** A due date read out of the index, before it becomes an [UpcomingReminder] or a notification. */
private data class DueDate(
    val doc: dev.npn.core.db.DocumentEntity,
    val fieldKey: String,
    val fieldLabel: String,
    val dueDate: LocalDate,
)

/** The journal's settings implementation of [RemindersFacade]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class JournalReminders(
    private val coordinator: JournalCoordinator,
    private val indexDb: dev.npn.core.db.NpnDatabase,
    private val notifications: ReminderNotificationDao,
    private val lifetime: SessionLifetime,
    private val clock: EpochClock,
    private val log: NpnLog,
) : RemindersFacade {
    private val raw: Flow<ReminderSettings> =
        combine(
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_ENABLED),
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_FIELDS),
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_LEAD_DAYS),
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_TIME),
        ) { enabled, fields, lead, time ->
            ReminderSettings(
                enabled = enabled.toBoolean(),
                fieldKeys = parseFieldKeys(fields),
                leadDays =
                    lead?.toIntOrNull()?.takeIf { it in ReminderSettings.LEAD_DAY_RANGE }
                        ?: ReminderSettings.DEFAULT_LEAD_DAYS,
                timeOfDay = time?.let(::parseReminderTime) ?: ReminderSettings.DEFAULT_TIME,
            )
        }

    /** Only registered DATE fields can be watched; a removed or retyped field stops counting. */
    private val dateKeys: Flow<Set<String>> =
        indexDb.fieldDefDao().observeAll().map { defs ->
            defs
                .mapNotNull { it.toFieldDef() }
                .filter { it.type.isDate }
                .map { it.key }
                .toSet()
        }

    override val settings: Flow<ReminderSettings> =
        combine(raw, dateKeys) { stored, valid -> stored.copy(fieldKeys = stored.fieldKeys.filter { it in valid }) }

    override suspend fun setEnabled(enabled: Boolean) {
        // The filtered set, not the raw stored list: a watched field removed or retyped away from
        // DATE leaves the raw list looking non-empty while reminders can do nothing.
        if (enabled && settings.first().fieldKeys.isEmpty()) {
            // Turning reminders on with nothing chosen would do nothing: start from the seeded
            // document date, as the settings pane's first suggestion would.
            val dateFields =
                indexDb
                    .fieldDefDao()
                    .defs()
                    .filter { it.type.isDate }
                    .map { it.key }
            val seeded = FieldKeys.DOC_DATE.takeIf { it in dateFields } ?: dateFields.firstOrNull()
            if (seeded != null) {
                lifetime.owned<Unit> { coordinator.putSetting(LibrarySettingKeys.REMINDER_FIELDS, seeded) }
            }
        }
        lifetime.owned<Unit> { coordinator.putSetting(LibrarySettingKeys.REMINDER_ENABLED, enabled.toString()) }
    }

    override suspend fun setFieldKeys(keys: List<String>) {
        val valid =
            indexDb
                .fieldDefDao()
                .defs()
                .filter { it.type.isDate }
                .map { it.key }
                .toSet()
        val chosen = keys.distinct().filter { it in valid }
        lifetime.owned<Unit> {
            coordinator.putSetting(LibrarySettingKeys.REMINDER_FIELDS, chosen.joinToString(",").ifBlank { null })
        }
    }

    override suspend fun setLeadDays(days: Int) {
        require(days in ReminderSettings.LEAD_DAY_RANGE) { "A lead time of $days days is out of range" }
        lifetime.owned<Unit> { coordinator.putSetting(LibrarySettingKeys.REMINDER_LEAD_DAYS, days.toString()) }
    }

    override suspend fun setTimeOfDay(time: LocalTime) {
        lifetime.owned<Unit> { coordinator.putSetting(LibrarySettingKeys.REMINDER_TIME, formatReminderTime(time)) }
    }

    override fun observeUpcoming(): Flow<List<UpcomingReminder>> =
        settings.flatMapLatest { current ->
            if (!current.isActive) {
                flowOf(emptyList())
            } else {
                combine(
                    indexDb.documentDao().observeReminderRows(current.fieldKeys),
                    indexDb.fieldDefDao().observeAll(),
                    midnightTicks(),
                ) { rows, defs, _ ->
                    val labels = defs.mapNotNull { it.toFieldDef() }.associate { it.key to it.label }
                    val today = ReminderWindow.today(clock.nowMillis(), currentZone())
                    upcoming(rows.mapNotNull { it.toDue(labels) }, today)
                }
            }
        }

    override suspend fun nextCheckDelayMillis(): Long? {
        val current = settings.first()
        if (!current.isActive) return null
        return ReminderWindow.delayUntilNextTrigger(clock.nowMillis(), currentZone(), current.timeOfDay)
    }

    override suspend fun claimDueReminders(): List<ReminderCandidate> {
        val current = settings.first()
        if (!current.isActive) return emptyList()
        val zone = currentZone()
        val now = clock.nowMillis()
        if (!ReminderWindow.checkAllowed(now, zone, current.timeOfDay)) return emptyList()
        val today = ReminderWindow.today(now, zone)
        val start = ReminderWindow.windowStart(today, current.leadDays)
        val end = ReminderWindow.windowEnd(today, current.leadDays)
        val labels =
            log
                .runLogged("Reading the reminder field labels") {
                    indexDb.fieldDefDao().defs().associate { it.key to it.label }
                }.orEmpty()
        val due =
            log
                .runLogged("Reading the reminder dates") {
                    indexDb.documentDao().reminderRows(current.fieldKeys)
                }.orEmpty()
        // Each watched date field is its own reminder, so a document with a document date and a
        // renewal date notifies for both. The Upcoming list still shows one row per document.
        val candidates =
            due
                .mapNotNull { it.toDue(labels) }
                .filter { it.dueDate in start..end }
                .map { it.toCandidate(today) }
        if (candidates.isEmpty()) return emptyList()
        val claimed =
            log
                .runLogged("Claiming reminders") {
                    lifetime.owned {
                        notifications.claim(
                            candidates.map {
                                ReminderNotificationEntity(it.docId, it.fieldKey, it.dueDate.toString(), now)
                            },
                        )
                    }
                }.orEmpty()
        val keys = claimed.map { Triple(it.docId, it.fieldKey, it.dueDate) }.toSet()
        return candidates.filter { Triple(it.docId, it.fieldKey, it.dueDate.toString()) in keys }
    }

    override suspend fun releaseReminder(candidate: ReminderCandidate) {
        log.runLogged("Releasing a reminder claim") {
            lifetime.owned {
                notifications.release(candidate.docId, candidate.fieldKey, candidate.dueDate.toString())
            }
        }
    }

    /**
     * Emits at local midnight, so the Upcoming groups and the library's due count roll over while
     * the app stays open. The first emission is immediate, so the combine starts at once.
     */
    private fun midnightTicks(): Flow<Unit> =
        flow {
            while (true) {
                val now = clock.nowMillis()
                val zone = currentZone()
                val next =
                    ReminderWindow.triggerAt(
                        ReminderWindow.today(now, zone).plus(DatePeriod(days = 1)),
                        LocalTime(0, 0),
                        zone,
                    )
                delay((next - now).coerceAtLeast(1_000L))
                emit(Unit)
            }
        }.onStart { emit(Unit) }

    private fun currentZone(): TimeZone = TimeZone.currentSystemDefault()

    private fun parseFieldKeys(stored: String?): List<String> =
        stored
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    private fun upcoming(
        dueDates: List<DueDate>,
        today: LocalDate,
    ): List<UpcomingReminder> =
        dueDates
            .groupBy { it.doc.docId }
            .values
            .mapNotNull { group ->
                val earliest = group.minByOrNull { it.dueDate } ?: return@mapNotNull null
                UpcomingReminder(
                    doc = earliest.doc.toDoc(),
                    fieldKey = earliest.fieldKey,
                    fieldLabel = earliest.fieldLabel,
                    dueDate = earliest.dueDate,
                    bucket = ReminderWindow.bucket(earliest.dueDate, today),
                )
            }.sortedWith(
                compareBy(
                    { it.bucket.ordinal },
                    { it.dueDate },
                    { it.doc.displayTitle.lowercase() },
                ),
            )
}

private fun ReminderFieldRow.toDue(labels: Map<String, String>): DueDate? {
    val date = runCatching { LocalDate.parse(fieldValue) }.getOrNull() ?: return null
    return DueDate(
        doc = doc,
        fieldKey = fieldKey,
        fieldLabel = labels[fieldKey] ?: fieldKey,
        dueDate = date,
    )
}

private fun DueDate.toCandidate(today: LocalDate): ReminderCandidate =
    ReminderCandidate(
        docId = doc.docId,
        documentTitle = doc.toDoc().displayTitle,
        fieldKey = fieldKey,
        fieldLabel = fieldLabel,
        dueDate = dueDate,
        bucket = ReminderWindow.bucket(dueDate, today),
    )
