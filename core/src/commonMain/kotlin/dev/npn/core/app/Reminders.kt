package dev.npn.core.app

import dev.npn.core.db.KeyValueDao
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
 * these, and the first two are "due now" for its badge. [SOON] is tomorrow through the lead time,
 * the dates a daily check notifies about ahead of time; [LATER] is past it.
 */
enum class ReminderBucket {
    OVERDUE,
    TODAY,
    SOON,
    LATER,
}

/**
 * The library's reminder settings. Library data, synced through the journal like the trash
 * retention: which date fields to watch and how many days ahead to warn. Whether this device
 * notifies is its own choice ([RemindersFacade.notifiesHere]). [enabled] false is the default, so a
 * library that never opted in does nothing.
 */
data class ReminderSettings(
    val enabled: Boolean = false,
    val fieldKeys: List<String> = emptyList(),
    val leadDays: Int = DEFAULT_LEAD_DAYS,
) {
    /** Whether reminders can produce anything: on, with at least one date field chosen. */
    val isActive: Boolean get() = enabled && fieldKeys.isNotEmpty()

    companion object {
        const val DEFAULT_LEAD_DAYS = 7
        val LEAD_DAY_RANGE = 0..365
    }
}

/**
 * One date on a live document that is due, for the Upcoming list. At most one is kept per document
 * (the earliest of its selected reminder dates still open): the list is about documents, not about
 * every field a document happens to carry.
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
 * daily notification check built from them. The settings and which reminders are done are synced
 * journal data; whether this device notifies, and the record of what it already notified, are its
 * own (`device.db`), so another device notifies once too, or not at all.
 */
interface RemindersFacade {
    val settings: Flow<ReminderSettings>

    suspend fun setEnabled(enabled: Boolean)

    /** Replace the watched fields; keys that are not registered DATE fields are dropped. */
    suspend fun setFieldKeys(keys: List<String>)

    /** Days before a due date at which it enters the notification window, one of [ReminderSettings.LEAD_DAY_RANGE]. */
    suspend fun setLeadDays(days: Int)

    /**
     * Whether this device shows reminder notifications, true until its user turns it off. Kept in
     * `device.db`: a phone may notify while a shared desktop does not.
     */
    val notifiesHere: Flow<Boolean>

    suspend fun setNotifiesHere(notify: Boolean)

    /**
     * The open reminders on live documents, grouped by bucket and date for the Upcoming screen: no
     * date marked done, and no overdue one older than [ReminderWindow.overdueStart].
     */
    fun observeUpcoming(): Flow<List<UpcomingReminder>>

    /**
     * Mark [docId]'s [fieldKey] done for [dueDate] (paid, renewed): it leaves Upcoming and stops
     * notifying, on every device. A later date on the same field is a new reminder.
     */
    suspend fun markDone(
        docId: String,
        fieldKey: String,
        dueDate: LocalDate,
    )

    /** Undo [markDone] while the field still holds [dueDate]. */
    suspend fun markOpen(
        docId: String,
        fieldKey: String,
        dueDate: LocalDate,
    )

    /**
     * How long until the next daily check should run, on this device's clock and time zone; null
     * while reminders are off or this device does not notify. After a check, the next one is the
     * following day, never the same instant again.
     */
    suspend fun nextCheckDelayMillis(): Long?

    /**
     * The open reminders due at this moment that this device has not notified yet, claimed in
     * `device.db` so a second check cannot repeat them. A document's earlier version, before a relink
     * (a bake in place), counts as the document. Empty before [ReminderWindow.CHECK_TIME], and while
     * this device does not notify.
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
    /** The local time of the daily check: a fixed morning time, on every device's own clock. */
    val CHECK_TIME = LocalTime(9, 0)

    /**
     * How far back Upcoming keeps an overdue date still open, unless the lead time reaches further:
     * an archive's years-old invoices must not all be "due" the day reminders are turned on.
     */
    const val OVERDUE_DAYS = 30

    /** Which group [dueDate] belongs to today; [ReminderBucket.SOON] is tomorrow through today + [leadDays]. */
    fun bucket(
        dueDate: LocalDate,
        today: LocalDate,
        leadDays: Int,
    ): ReminderBucket =
        when {
            dueDate < today -> ReminderBucket.OVERDUE
            dueDate == today -> ReminderBucket.TODAY
            dueDate <= windowEnd(today, leadDays) -> ReminderBucket.SOON
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
        timeOfDay: LocalTime = CHECK_TIME,
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
     * Whether a check at [nowMillis] may notify: from [timeOfDay] of today on. Before it, a check
     * that happens to run (startup, a settings change) stays quiet and waits.
     */
    fun checkAllowed(
        nowMillis: Long,
        zone: TimeZone,
        timeOfDay: LocalTime = CHECK_TIME,
    ): Boolean = localTime(nowMillis, zone) >= timeOfDay

    /** The last date a notification window covers: [today] plus [leadDays] days. */
    fun windowEnd(
        today: LocalDate,
        leadDays: Int,
    ): LocalDate = today.plus(DatePeriod(days = leadDays.coerceIn(ReminderSettings.LEAD_DAY_RANGE)))

    /**
     * The first date a notification window covers: [today] minus [leadDays] days, so the years-old
     * invoices of an archive do not all notify on the day reminders are first enabled.
     */
    fun windowStart(
        today: LocalDate,
        leadDays: Int,
    ): LocalDate = today.plus(DatePeriod(days = -leadDays.coerceIn(ReminderSettings.LEAD_DAY_RANGE)))

    /**
     * The oldest overdue date Upcoming and its badge still show: [OVERDUE_DAYS] back, or as far as
     * the notification window reaches when the lead time is longer, so a date that notified stays
     * listed until it is done.
     */
    fun overdueStart(
        today: LocalDate,
        leadDays: Int,
    ): LocalDate = minOf(windowStart(today, leadDays), today.plus(DatePeriod(days = -OVERDUE_DAYS)))
}

/** A due date read out of the index, before it becomes an [UpcomingReminder] or a notification. */
private data class DueDate(
    val doc: dev.npn.core.db.DocumentEntity,
    val fieldKey: String,
    val fieldLabel: String,
    val dueDate: LocalDate,
) {
    val key: ReminderKey get() = ReminderKey(doc.docId, fieldKey, dueDate.toString())
}

/** One reminder: a document's watched field and the date it holds, canonical `yyyy-MM-dd`. */
private data class ReminderKey(
    val docId: String,
    val fieldKey: String,
    val dueDate: String,
)

/** The `device.db` key of [RemindersFacade.notifiesHere]. */
internal const val NOTIFIES_HERE_KEY = "reminders.notifiesHere"

/** The synced setting that marks [docId]'s [fieldKey] done ([LibrarySettingKeys.REMINDER_DONE_PREFIX]). */
internal fun reminderDoneKey(
    docId: String,
    fieldKey: String,
): String = LibrarySettingKeys.REMINDER_DONE_PREFIX + docId + ":" + fieldKey

/** A done mark read back from its setting; null for any other key. A document id holds no `:`. */
private fun reminderDoneMark(
    key: String,
    dueDate: String?,
): ReminderKey? {
    if (dueDate == null || !key.startsWith(LibrarySettingKeys.REMINDER_DONE_PREFIX)) return null
    val rest = key.removePrefix(LibrarySettingKeys.REMINDER_DONE_PREFIX)
    val docId = rest.substringBefore(':', "")
    val fieldKey = rest.substringAfter(':', "")
    if (docId.isEmpty() || fieldKey.isEmpty()) return null
    return ReminderKey(docId, fieldKey, dueDate)
}

/** The journal's settings implementation of [RemindersFacade]. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class JournalReminders(
    private val coordinator: JournalCoordinator,
    private val indexDb: dev.npn.core.db.NpnDatabase,
    private val notifications: ReminderNotificationDao,
    private val deviceKv: KeyValueDao,
    private val lifetime: SessionLifetime,
    private val clock: EpochClock,
    private val log: NpnLog,
    private val currentZone: () -> TimeZone = TimeZone::currentSystemDefault,
) : RemindersFacade {
    private val raw: Flow<ReminderSettings> =
        combine(
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_ENABLED),
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_FIELDS),
            coordinator.settingFlow(LibrarySettingKeys.REMINDER_LEAD_DAYS),
        ) { enabled, fields, lead ->
            ReminderSettings(
                enabled = enabled.toBoolean(),
                fieldKeys = parseFieldKeys(fields),
                leadDays =
                    lead?.toIntOrNull()?.takeIf { it in ReminderSettings.LEAD_DAY_RANGE }
                        ?: ReminderSettings.DEFAULT_LEAD_DAYS,
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

    override val notifiesHere: Flow<Boolean> =
        deviceKv.observe(NOTIFIES_HERE_KEY).map { it?.toBooleanStrictOrNull() ?: true }.distinctUntilChanged()

    /** Every done mark the journal holds, as it holds them: keyed by the document they were marked on. */
    private val doneMarks: Flow<List<ReminderKey>> =
        coordinator
            .settings()
            .map { all -> all.mapNotNull { (key, value) -> reminderDoneMark(key, value) } }
            .distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        // The filtered set, not the raw stored list: a watched field removed or retyped away from
        // DATE leaves the raw list looking non-empty while reminders can do nothing.
        if (enabled && settings.first().fieldKeys.isEmpty()) {
            // Only the seeded due date is a safe default. Other date fields may describe
            // when a document was issued, so leave them for the user to choose.
            val dateFields =
                indexDb
                    .fieldDefDao()
                    .defs()
                    .filter { it.type.isDate }
                    .map { it.key }
            val seeded = FieldKeys.INVOICE_DUE_DATE.takeIf { it in dateFields }
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

    override suspend fun setNotifiesHere(notify: Boolean) {
        lifetime.owned { deviceKv.put(NOTIFIES_HERE_KEY, notify.toString()) }
    }

    override suspend fun markDone(
        docId: String,
        fieldKey: String,
        dueDate: LocalDate,
    ) {
        lifetime.owned<Unit> { coordinator.putSetting(reminderDoneKey(docId, fieldKey), dueDate.toString()) }
    }

    override suspend fun markOpen(
        docId: String,
        fieldKey: String,
        dueDate: LocalDate,
    ) {
        val key = reminderDoneKey(docId, fieldKey)
        // Only the mark for this date: a newer one, from another device, stays.
        if (coordinator.setting(key) != dueDate.toString()) return
        lifetime.owned<Unit> { coordinator.putSetting(key, null) }
    }

    override fun observeUpcoming(): Flow<List<UpcomingReminder>> =
        settings.flatMapLatest { current ->
            if (!current.isActive) {
                flowOf(emptyList())
            } else {
                combine(
                    indexDb.documentDao().observeReminderRows(current.fieldKeys),
                    indexDb.fieldDefDao().observeAll(),
                    doneMarks,
                    localDateTicks(clock, currentZone),
                ) { rows, defs, marks, _ ->
                    val labels = defs.mapNotNull { it.toFieldDef() }.associate { it.key to it.label }
                    val today = ReminderWindow.today(clock.nowMillis(), currentZone())
                    val due = rows.mapNotNull { it.toDue(labels) }
                    val done = resolveDone(marks, due.mapTo(HashSet()) { it.doc.docId })
                    upcoming(due.filter { it.key !in done }, today, current.leadDays)
                }
            }
        }

    override suspend fun nextCheckDelayMillis(): Long? {
        val current = settings.first()
        if (!current.isActive || !notifiesHere.first()) return null
        return ReminderWindow.delayUntilNextTrigger(clock.nowMillis(), currentZone())
    }

    override suspend fun claimDueReminders(): List<ReminderCandidate> {
        val current = settings.first()
        if (!current.isActive || !notifiesHere.first()) return emptyList()
        val zone = currentZone()
        val now = clock.nowMillis()
        if (!ReminderWindow.checkAllowed(now, zone)) return emptyList()
        val today = ReminderWindow.today(now, zone)
        val start = ReminderWindow.windowStart(today, current.leadDays)
        val end = ReminderWindow.windowEnd(today, current.leadDays)
        val labels =
            log
                .runLogged("Reading the reminder field labels") {
                    indexDb.fieldDefDao().defs().associate { it.key to it.label }
                }.orEmpty()
        val rows =
            log
                .runLogged("Reading the reminder dates") {
                    indexDb.documentDao().reminderRows(current.fieldKeys)
                }.orEmpty()
        // Each watched date field is its own reminder, so a document with a document date and a
        // renewal date notifies for both. The Upcoming list still shows one row per document.
        val inWindow = rows.mapNotNull { it.toDue(labels) }.filter { it.dueDate in start..end }
        val marks = log.runLogged("Reading the done reminders") { doneMarks.first() }.orEmpty()
        val done = resolveDone(marks, inWindow.mapTo(HashSet()) { it.doc.docId })
        val open = inWindow.filter { it.key !in done }
        if (open.isEmpty()) return emptyList()
        val shownBefore = shownBeforeRelink(open.map { it.key })
        val claimed =
            log
                .runLogged("Claiming reminders") {
                    lifetime.owned {
                        notifications.claim(
                            open.map { ReminderNotificationEntity(it.doc.docId, it.fieldKey, it.key.dueDate, now) },
                        )
                    }
                }.orEmpty()
        val keys = claimed.mapTo(HashSet()) { ReminderKey(it.docId, it.fieldKey, it.dueDate) }
        // A reminder a relinked document's earlier version showed is claimed for the new one above,
        // so later checks skip it without asking the journal, and is not shown again.
        return open
            .filter { it.key in keys && it.key !in shownBefore }
            .map { it.toCandidate(today, current.leadDays) }
    }

    override suspend fun releaseReminder(candidate: ReminderCandidate) {
        log.runLogged("Releasing a reminder claim") {
            lifetime.owned {
                notifications.release(candidate.docId, candidate.fieldKey, candidate.dueDate.toString())
            }
        }
    }

    /**
     * Which of [due]'s reminders this device already showed for the document they were on before a
     * relink: a bake in place gives a document new bytes, so a new id, and its dates come with it.
     */
    private suspend fun shownBeforeRelink(due: List<ReminderKey>): Set<ReminderKey> {
        val current = due.mapTo(HashSet()) { it.docId }
        val earlier =
            log
                .runLogged("Reading the reminders already shown") {
                    notifications.onDates(due.map { it.dueDate }.distinct())
                }.orEmpty()
                .filter { it.docId !in current }
        if (earlier.isEmpty()) return emptySet()
        val successors = successors(earlier.mapTo(HashSet()) { it.docId })
        val wanted = due.toHashSet()
        return earlier
            .mapNotNull { claim ->
                successors[claim.docId]?.let { ReminderKey(it, claim.fieldKey, claim.dueDate) }
            }.filterTo(HashSet()) { it in wanted }
    }

    /**
     * The done marks that apply to [live] documents: those marked on the document itself, and those
     * marked on an earlier version a relink superseded, carried onto the document it became.
     */
    private suspend fun resolveDone(
        marks: List<ReminderKey>,
        live: Set<String>,
    ): Set<ReminderKey> {
        val (direct, elsewhere) = marks.partition { it.docId in live }
        if (elsewhere.isEmpty()) return direct.toHashSet()
        val successors = successors(elsewhere.mapTo(HashSet()) { it.docId })
        val carried =
            elsewhere.mapNotNull { mark ->
                successors[mark.docId]?.takeIf { it in live }?.let { mark.copy(docId = it) }
            }
        return (direct + carried).toHashSet()
    }

    /**
     * What each of [docIds] became through its relink chain, for those a relink superseded; empty
     * when the journal cannot be read now (closed, or a pass failing), which leaves their reminders
     * as their own.
     */
    private suspend fun successors(docIds: Set<String>): Map<String, String> =
        log
            .runLogged("Following relinks for reminders") {
                coordinator.serialized { journal ->
                    docIds
                        .associateWith { journal.successorOf(it) }
                        .filter { (old, new) -> old != new }
                }
            }.orEmpty()

    private fun parseFieldKeys(stored: String?): List<String> =
        stored
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()

    /**
     * One row per document, its earliest open date, leaving out overdue dates older than
     * [ReminderWindow.overdueStart]: a stale date must neither stay "due" for ever nor hide the
     * document's next one.
     */
    private fun upcoming(
        open: List<DueDate>,
        today: LocalDate,
        leadDays: Int,
    ): List<UpcomingReminder> {
        val oldest = ReminderWindow.overdueStart(today, leadDays)
        return open
            .filter { it.dueDate >= oldest }
            .groupBy { it.doc.docId }
            .values
            .map { group ->
                val earliest = group.minBy { it.dueDate }
                UpcomingReminder(
                    doc = earliest.doc.toDoc(),
                    fieldKey = earliest.fieldKey,
                    fieldLabel = earliest.fieldLabel,
                    dueDate = earliest.dueDate,
                    bucket = ReminderWindow.bucket(earliest.dueDate, today, leadDays),
                )
            }.sortedWith(
                compareBy(
                    { it.bucket.ordinal },
                    { it.dueDate },
                    { it.doc.displayTitle.lowercase() },
                ),
            )
    }
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

private fun DueDate.toCandidate(
    today: LocalDate,
    leadDays: Int,
): ReminderCandidate =
    ReminderCandidate(
        docId = doc.docId,
        documentTitle = doc.toDoc().displayTitle,
        fieldKey = fieldKey,
        fieldLabel = fieldLabel,
        dueDate = dueDate,
        bucket = ReminderWindow.bucket(dueDate, today, leadDays),
    )

/** Refresh on local-date or time-zone changes, including after a suspended device resumes. */
internal fun localDateTicks(
    clock: EpochClock,
    currentZone: () -> TimeZone,
): Flow<Pair<LocalDate, TimeZone>> =
    flow {
        while (true) {
            val now = clock.nowMillis()
            val zone = currentZone()
            val today = ReminderWindow.today(now, zone)
            emit(today to zone)
            val next = ReminderWindow.triggerAt(today.plus(DatePeriod(days = 1)), LocalTime(0, 0), zone)
            delay((next - now).coerceIn(1_000L, 60_000L))
        }
    }.distinctUntilChanged()
