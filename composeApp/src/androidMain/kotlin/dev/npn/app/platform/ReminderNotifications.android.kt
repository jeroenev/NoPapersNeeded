package dev.npn.app.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.npn.app.di.AppEnvironment
import dev.npn.app.di.createAppEnvironment
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.AndroidLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn
import io.github.vinceglb.filekit.FileKit
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notification_body
import no_papers_needed.composeapp.generated.resources.reminders_notification_channel
import no_papers_needed.composeapp.generated.resources.reminders_notification_overdue
import no_papers_needed.composeapp.generated.resources.reminders_notification_title
import org.jetbrains.compose.resources.getString
import java.util.concurrent.TimeUnit

private const val CHANNEL_ID = "reminders"
private const val WORK_NAME = "npn-reminders"

/** The extra a reminder notification's launch intent carries, read by `MainActivity`. */
const val EXTRA_OPEN_DOCUMENT = "dev.npn.openDocument"

actual fun canShowReminderNotifications(): Boolean {
    val context = FileKit.context.applicationContext
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return false
    }
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
}

actual suspend fun showReminderNotification(
    docId: String,
    documentTitle: String,
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
    val launch =
        (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            putExtra(EXTRA_OPEN_DOCUMENT, docId)
            flags = flags or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    val pending =
        PendingIntent.getActivity(
            context,
            docId.hashCode(),
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
    NotificationManagerCompat.from(context).notify(("$docId:$fieldLabel").hashCode(), notification)
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
    val request =
        PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
    work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
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
 * The daily reminder check WorkManager runs while the app is not open. It opens the persisted
 * library when the process was started for this work alone, waits for its initial rescan so the
 * index knows the dates, then shows what the library's reminder settings say is due.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!canShowReminderNotifications()) return Result.success()
        val env = ReminderRuntime.orCreate(applicationContext)
        val session =
            env.session.value ?: runCatchingCancellable {
                env.openPersistedLibrary()
                env.session.value
            }.getOrNull() ?: return Result.success()
        return runCatchingCancellable {
            session.awaitInitialRescan()
            deliverDueReminders(session)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                AndroidLog.warn("Checking reminders failed; retrying", it)
                Result.retry()
            },
        )
    }
}

/** The in-app check and the worker share this: claim, show, and let the platform say it could not. */
internal suspend fun deliverDueReminders(session: LibrarySession) {
    val due = session.reminders.claimDueReminders()
    for (candidate in due) {
        val delivery =
            showReminderNotification(
                docId = candidate.docId,
                documentTitle = candidate.documentTitle,
                fieldLabel = candidate.fieldLabel,
                dueDate = candidate.dueDate.toString(),
                overdue = candidate.bucket == dev.npn.core.app.ReminderBucket.OVERDUE,
            )
        if (delivery == ReminderDelivery.NOT_PERMITTED) return
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

    fun install(environment: AppEnvironment): AppEnvironment {
        installed = environment
        return environment
    }

    fun orCreate(context: Context): AppEnvironment =
        installed ?: synchronized(this) {
            installed ?: createAppEnvironment(context.applicationContext)
        }
}
