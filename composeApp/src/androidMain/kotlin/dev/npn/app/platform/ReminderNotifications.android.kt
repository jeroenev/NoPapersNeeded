package dev.npn.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.createAppEnvironment
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notification_body
import no_papers_needed.composeapp.generated.resources.reminders_notification_channel
import no_papers_needed.composeapp.generated.resources.reminders_notification_overdue
import no_papers_needed.composeapp.generated.resources.reminders_notification_title
import org.jetbrains.compose.resources.getString
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "reminders"
private const val WORK_NAME = "npn-reminders"
private const val MAX_REMINDER_ATTEMPTS = 2

/** The extra a reminder notification's launch intent carries, read by `MainActivity`. */
const val EXTRA_OPEN_DOCUMENT = "dev.npn.openDocument"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context
                .getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_ID)
                ?.importance == NotificationManager.IMPORTANCE_NONE
        } else {
            false
        }
    return reminderNotificationsPermitted(
        NotificationManagerCompat.from(context).areNotificationsEnabled(),
        channelBlocked,
    )
}

actual suspend fun showReminderNotification(
    docId: String,
    libraryLocation: String,
    documentTitle: String,
    fieldKey: String,
    fieldLabel: String,
    dueDate: String,
    overdue: Boolean,
): ReminderDelivery {
    val context = FileKit.context.applicationContext
    if (!canShowReminderNotifications()) return ReminderDelivery.NOT_PERMITTED
    ensureChannel(context)

    val title = getString(Res.string.reminders_notification_title, fieldLabel)
    val body =
        getString(
            if (overdue) Res.string.reminders_notification_overdue else Res.string.reminders_notification_body,
            documentTitle,
            dueDate,
        )
    // Explicit, and pinned to this package: the intent names a document and the library's path, so
    // no other app may resolve it. Without a launcher activity there is nothing to open, and the
    // throw releases the claim.
    val launch =
        checkNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)) {
            "No launcher activity to open a reminder with"
        }
    launch.setPackage(context.packageName)
    launch.putExtra(EXTRA_OPEN_DOCUMENT, docId)
    launch.putExtra(EXTRA_REMINDER_LIBRARY, libraryLocation)
    launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val reminderTag = reminderNotificationTag("$libraryLocation:$docId", fieldKey, dueDate)
    launch.data =
        android.net.Uri
            .Builder()
            .scheme("npn-reminder")
            .authority("open")
            .appendPath(libraryLocation)
            .appendPath(docId)
            .appendPath(fieldKey)
            .appendPath(dueDate)
            .build()
    val reminderId = reminderTag.hashCode()
    val pending =
        PendingIntent.getActivity(
            context,
            reminderId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    val notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
    @SuppressLint("MissingPermission") // Checked by canShowReminderNotifications above.
    NotificationManagerCompat.from(context).notify(reminderTag, 0, notification)
    return ReminderDelivery.NOTIFIED
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
    // A one-shot job, re-enqueued for the next local time after each run. A fixed 24-hour periodic
    // job would drift before the configured wall-clock time across DST or a time-zone change and
    // then keep skipping its own check.
    val request =
        OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
    work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
}

@Composable
actual fun rememberReminderPermissionRequest(): (() -> Unit)? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || canShowReminderNotifications()) return null
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) AndroidLog.warn("Reminder notifications were not permitted")
        }
    return { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

/**
 * Recompute a pending check when the device's local clock or time zone changes. Only a check that
 * is scheduled: with reminders off (every library that never opted in) there is none, and running
 * the worker would open the library and rescan it in the background for nothing.
 */
class ReminderTimeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (!isReminderTimeChange(intent.action)) return
        val work = WorkManager.getInstance(context.applicationContext)
        val result = goAsync()
        val infos = work.getWorkInfosForUniqueWork(WORK_NAME)
        infos.addListener(
            {
                try {
                    val states = runCatching { infos.get().map { it.state } }.getOrDefault(emptyList())
                    if (reminderCheckScheduled(states)) {
                        val request = OneTimeWorkRequestBuilder<ReminderWorker>().build()
                        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
                    }
                } finally {
                    result.finish()
                }
            },
            Runnable::run,
        )
    }
}

/** Whether the unique reminder work holds a check still to run, which a clock change must move. */
internal fun reminderCheckScheduled(states: List<WorkInfo.State>): Boolean = WorkInfo.State.ENQUEUED in states

/**
 * The daily reminder check WorkManager runs while the app is not open. It opens the persisted
 * library when the process was started for this work alone, waits for its initial rescan so the
 * index knows the dates, then shows what the library's reminder settings say is due.
 */
@Keep // WorkManager instantiates it by name after storing the request.
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val env = ReminderRuntime.orCreate(applicationContext)
        val session =
            env.session.value
                ?: runCatchingCancellable {
                    env.openPersistedLibrary()
                    env.session.value
                }.getOrElse {
                    AndroidLog.warn("Opening the library for reminders failed; retrying", it)
                    return Result.retry()
                } ?: return Result.success()
        val location =
            env.settings.settings
                .first()
                .libraryLocation ?: return Result.success()
        if (!canShowReminderNotifications()) {
            val next = session.reminders.nextCheckDelayMillis()
            scheduleReminderChecks(active = next != null, delayMillis = next ?: 0L)
            return Result.success()
        }
        return runCatchingCancellable {
            check(session.awaitInitialRescan()) { "The initial rescan failed" }
            if (env.session.value === session && env.settings.settings
                    .first()
                    .libraryLocation == location
            ) {
                deliverDueReminders(session, location)
            }
        }.fold(
            onSuccess = {
                // The one-shot job is consumed; the next check is the next configured local time.
                val next = session.reminders.nextCheckDelayMillis()
                scheduleReminderChecks(active = next != null, delayMillis = next ?: 0L)
                Result.success()
            },
            onFailure = {
                AndroidLog.warn("Checking reminders failed; retrying", it)
                // A one-shot job has no attempt cap; do not keep waking the device forever when
                // the library itself is gone. Leave a fresh daily check for a later recovery.
                if (runAttemptCount >= MAX_REMINDER_ATTEMPTS) {
                    val next = session.reminders.nextCheckDelayMillis()
                    scheduleReminderChecks(active = next != null, delayMillis = next ?: 0L)
                    Result.success()
                } else {
                    Result.retry()
                }
            },
        )
    }
}

/** The in-app check and the worker share this: claim, show, and release what could not be shown. */
internal suspend fun deliverDueReminders(
    session: LibrarySession,
    libraryLocation: String,
) = withContext(NonCancellable) {
    val due =
        AndroidLog
            .runLogged("Reading the reminders due now") {
                session.reminders.claimDueReminders()
            }.orEmpty()
    for (candidate in due) {
        val delivery =
            AndroidLog.runLogged("Showing a reminder for ${candidate.documentTitle}") {
                showReminderNotification(
                    docId = candidate.docId,
                    libraryLocation = libraryLocation,
                    documentTitle = candidate.documentTitle,
                    fieldKey = candidate.fieldKey,
                    fieldLabel = candidate.fieldLabel,
                    dueDate = candidate.dueDate.toString(),
                    overdue = candidate.bucket == dev.npn.core.app.ReminderBucket.OVERDUE,
                )
            }
        when (delivery) {
            null -> {
                session.reminders.releaseReminder(candidate)
            }

            ReminderDelivery.NOT_PERMITTED -> {
                session.reminders.releaseReminder(candidate)
                return@withContext
            }

            ReminderDelivery.NOTIFIED, ReminderDelivery.IN_APP -> {}
        }
    }
}

private suspend fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(Res.string.reminders_notification_channel),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
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
