package dev.npn.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.npn.app.R
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.createAppEnvironment
import dev.npn.app.ui.reminders.ReminderRunOutcome
import dev.npn.app.ui.reminders.deliverDueReminders
import dev.npn.app.ui.reminders.runReminderCheck
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderCandidate
import dev.npn.core.app.ReminderWindow
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.SystemEpochClock
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notification_channel
import org.jetbrains.compose.resources.getString
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "reminders"
private const val WORK_NAME = "npn-reminders"
private const val GROUP_KEY = "dev.npn.reminders"

/** The extras a reminder notification's launch intent carries, read by `MainActivity`. */
const val EXTRA_OPEN_DOCUMENT = "dev.npn.openDocument"
const val EXTRA_OPEN_UPCOMING = "dev.npn.openUpcoming"
const val EXTRA_REMINDER_LIBRARY = "dev.npn.reminderLibrary"

actual fun canShowReminderNotifications(): Boolean {
    val context = FileKit.context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    val channelBlocked =
        context
            .getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(CHANNEL_ID)
            ?.importance == NotificationManager.IMPORTANCE_NONE
    return reminderNotificationsPermitted(
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        channelBlocked,
    )
}

actual val remindersCheckedInApp: Boolean = false

actual suspend fun showReminderNotifications(
    libraryLocation: String,
    reminders: List<ReminderCandidate>,
    texts: ReminderTexts,
): ReminderDelivery {
    val context = FileKit.context.applicationContext
    if (!canShowReminderNotifications()) return ReminderDelivery.NOT_SHOWN
    ensureChannel(context)
    val posted = ArrayList<Pair<String, Notification>>()
    val single = reminders.singleOrNull()
    if (single != null) {
        val open = openDocument(context, libraryLocation, single)
        posted += single.tag(libraryLocation) to reminderNotification(context, texts.of(single), texts.generic(1), open)
    } else if (texts.hideDetails) {
        // The app lock hides the library: one notification that something is due, nothing more.
        val generic = texts.generic(reminders.size)
        posted += summaryTag(libraryLocation) to
            reminderNotification(context, generic, generic, openUpcoming(context, libraryLocation))
    } else {
        for (reminder in reminders) {
            posted += reminder.tag(libraryLocation) to
                reminderNotification(
                    context,
                    texts.of(reminder),
                    texts.generic(1),
                    openDocument(context, libraryLocation, reminder),
                    group = true,
                )
        }
        val summary = texts.summary(reminders)
        posted += summaryTag(libraryLocation) to
            reminderNotification(
                context,
                summary,
                texts.generic(reminders.size),
                openUpcoming(context, libraryLocation),
                group = true,
                summaryLines = summary.body.lines(),
            )
    }
    val manager = NotificationManagerCompat.from(context)
    for ((tag, notification) in posted) {
        @SuppressLint("MissingPermission") // Checked by canShowReminderNotifications above.
        manager.notify(tag, 0, notification)
    }
    return ReminderDelivery.NOTIFIED
}

private fun ReminderCandidate.tag(libraryLocation: String): String =
    reminderNotificationTag("$libraryLocation:$docId", fieldKey, dueDate.toString())

private fun summaryTag(libraryLocation: String): String = "reminders:$libraryLocation"

/**
 * One reminder notification, or the group's summary with [summaryLines]. [shown] is what it says
 * unlocked; the lock screen gets [public] instead, which names nothing in the library.
 */
private fun reminderNotification(
    context: Context,
    shown: ReminderText,
    public: ReminderText,
    open: PendingIntent,
    group: Boolean = false,
    summaryLines: List<String>? = null,
): Notification {
    val publicVersion =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(public.title)
            .setContentText(public.body)
            .build()
    val builder =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(shown.title)
            .setContentText(summaryLines?.firstOrNull() ?: shown.body)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
    if (summaryLines != null) {
        builder.setStyle(NotificationCompat.InboxStyle().also { style -> summaryLines.forEach(style::addLine) })
        builder.setGroupSummary(true)
    } else {
        builder.setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
    }
    if (group) builder.setGroup(GROUP_KEY)
    return builder.build()
}

/**
 * The app's launch intent, explicit and pinned to this package: it names the library's path, so no
 * other app may resolve it. Without a launcher activity there is nothing to open, and the throw
 * releases the claims.
 */
private fun launchIntent(context: Context): Intent {
    val launch =
        checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
            "No launcher activity to open a reminder with"
        }
    launch.setPackage(context.packageName)
    launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    return launch
}

private fun openDocument(
    context: Context,
    libraryLocation: String,
    reminder: ReminderCandidate,
): PendingIntent {
    val launch = launchIntent(context)
    launch.putExtra(EXTRA_OPEN_DOCUMENT, reminder.docId)
    launch.putExtra(EXTRA_REMINDER_LIBRARY, libraryLocation)
    // A distinct data URI per reminder keeps each PendingIntent its own.
    launch.data =
        Uri
            .Builder()
            .scheme("npn-reminder")
            .authority("open")
            .appendPath(libraryLocation)
            .appendPath(reminder.docId)
            .appendPath(reminder.fieldKey)
            .appendPath(reminder.dueDate.toString())
            .build()
    return PendingIntent.getActivity(
        context,
        reminder.tag(libraryLocation).hashCode(),
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

private fun openUpcoming(
    context: Context,
    libraryLocation: String,
): PendingIntent {
    val launch = launchIntent(context)
    launch.putExtra(EXTRA_OPEN_UPCOMING, true)
    launch.putExtra(EXTRA_REMINDER_LIBRARY, libraryLocation)
    launch.data =
        Uri
            .Builder()
            .scheme("npn-reminder")
            .authority("upcoming")
            .appendPath(libraryLocation)
            .build()
    return PendingIntent.getActivity(
        context,
        summaryTag(libraryLocation).hashCode(),
        launch,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

actual fun scheduleReminderChecks(
    active: Boolean,
    delayMillis: Long,
) {
    val work = WorkManager.getInstance(FileKit.context.applicationContext)
    if (!active) {
        work.cancelUniqueWork(WORK_NAME)
        return
    }
    // A one-shot job, re-planned for the next morning after each run. A fixed 24-hour periodic job
    // would drift off the local time across DST or a time-zone change; each run re-reads both.
    val request =
        OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
    work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

@Composable
actual fun rememberNotificationSettingsAction(): (() -> Unit)? {
    val context = LocalContext.current
    val openSettings = {
        val intent =
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return openSettings
    // Once the user has refused the prompt twice, Android answers it at once with a refusal: the
    // app's notification settings are then the only way left.
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) openSettings()
        }
    return {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (granted) openSettings() else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

actual fun formatReminderDate(date: LocalDate): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date.toJavaLocalDate())

/**
 * The daily reminder check, run by WorkManager whether or not the app is open ([runReminderCheck]):
 * the permission first, then the persisted library, opened when the process was started for this
 * work alone, its initial rescan so the index knows the dates, what is due, and the next morning's
 * run.
 */
@Keep // WorkManager instantiates it by name after storing the request.
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val env = ReminderRuntime.orCreate(applicationContext)
        val outcome =
            runReminderCheck(
                attempt = runAttemptCount,
                permitted = ::canShowReminderNotifications,
                open = {
                    env.session.value ?: run {
                        env.openPersistedLibrary()
                        env.session.value
                    }
                },
                check = { session -> deliverFor(env, session) },
                plan = { session ->
                    // Without a session the morning time is still known: it is fixed.
                    val next =
                        if (session == null) {
                            val now = SystemEpochClock.nowMillis()
                            ReminderWindow.delayUntilNextTrigger(now, TimeZone.currentSystemDefault())
                        } else {
                            session.reminders.nextCheckDelayMillis()
                        }
                    scheduleReminderChecks(active = next != null, delayMillis = next ?: 0L)
                },
                log = AndroidLog,
            )
        return if (outcome == ReminderRunOutcome.RETRY) Result.retry() else Result.success()
    }

    private suspend fun deliverFor(
        env: AppEnvironment,
        session: LibrarySession,
    ) {
        val location =
            env.settings.settings
                .first()
                .libraryLocation ?: return
        check(session.awaitInitialRescan()) { "The initial rescan failed" }
        // The library may have been closed or switched while the rescan ran.
        if (env.session.value !== session || env.settings.settings
                .first()
                .libraryLocation != location
        ) {
            return
        }
        deliverDueReminders(session, location, ReminderTexts(env.lock.state.value.enabled))
    }
}

private suspend fun ensureChannel(context: Context) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(Res.string.reminders_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
    manager.createNotificationChannel(channel)
}

/**
 * The process-wide environment, shared with `NpnApplication` even when a worker is the first
 * component to ask for it: the UI must find the same session and databases the worker opened.
 */
internal object ReminderRuntime {
    @Volatile private var installed: AppEnvironment? = null

    fun existing(): AppEnvironment? = installed

    /** Create the process-wide environment once, under one lock, even when a worker and the UI race. */
    fun create(build: () -> AppEnvironment): AppEnvironment =
        synchronized(this) { installed ?: build().also { installed = it } }

    fun orCreate(context: Context): AppEnvironment = existing() ?: createAppEnvironment(context.applicationContext)
}
