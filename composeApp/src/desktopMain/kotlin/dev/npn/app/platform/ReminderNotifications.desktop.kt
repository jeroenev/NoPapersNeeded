package dev.npn.app.platform

import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.OpenDocumentRequest
import dev.npn.app.ui.common.OpenDocumentRequests
import dev.npn.core.app.ReminderCandidate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toJavaLocalDate
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.Timer

/** A desktop has no notification permission to ask for. */
actual fun canShowReminderNotifications(): Boolean = true

actual val remindersCheckedInApp: Boolean = true

actual suspend fun showReminderNotifications(
    libraryLocation: String,
    reminders: List<ReminderCandidate>,
    texts: ReminderTexts,
): ReminderDelivery {
    val single = reminders.singleOrNull()
    val text = if (single != null) texts.of(single) else texts.summary(reminders)
    val open: () -> Unit =
        if (single != null) {
            { OpenDocumentRequests.documents.set(OpenDocumentRequest(single.docId, libraryLocation)) }
        } else {
            { OpenDocumentRequests.upcoming.set(libraryLocation) }
        }
    val shown = desktopNotifier.show(text, open)
    return if (shown) ReminderDelivery.NOTIFIED else ReminderDelivery.NOT_SHOWN
}

actual fun scheduleReminderChecks(
    active: Boolean,
    delayMillis: Long,
) = Unit

@Composable
actual fun rememberNotificationSettingsAction(): (() -> Unit)? = null

actual fun formatReminderDate(date: LocalDate): String =
    DateTimeFormatter
        .ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(date.toJavaLocalDate())

/**
 * Linux desktops get freedesktop notifications over D-Bus ([FreedesktopNotifier]): AWT's tray is
 * missing on GNOME, Wayland and in a Flatpak, and where it exists a message there can vanish unseen.
 * Elsewhere (Windows, macOS) AWT's tray shows a message, from an icon that leaves again.
 */
private val desktopNotifier: BlockingNotifier by lazy {
    val linux =
        System
            .getProperty("os.name")
            .orEmpty()
            .lowercase()
            .contains("linux")
    BlockingNotifier(
        if (linux) {
            GioNotificationBus.createOrNull()?.let(::FreedesktopNotifier) ?: DesktopNotifier { _, _ -> false }
        } else {
            TrayMessageNotifier
        },
    )
}

/** [notifier]'s calls block (a D-Bus round trip, the AWT thread), so they run on [io]. */
private class BlockingNotifier(
    private val notifier: DesktopNotifier,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun show(
        text: ReminderText,
        onOpen: () -> Unit,
    ): Boolean = withContext(io) { notifier.show(text, onOpen) }
}

/**
 * A tray message for a desktop without freedesktop notifications. The icon is added for the message
 * and removed once it is clicked or after [SHOWN_MS], so the app does not keep a tray icon of its own.
 */
private object TrayMessageNotifier : DesktopNotifier {
    private const val SHOWN_MS = 60_000

    override fun show(
        text: ReminderText,
        onOpen: () -> Unit,
    ): Boolean {
        var shown = false
        SwingUtilities.invokeAndWait {
            if (!SystemTray.isSupported()) return@invokeAndWait
            val tray = SystemTray.getSystemTray()
            val icon = TrayIcon(iconImage(), "No Papers Needed").apply { isImageAutoSize = true }
            val added = runCatching { tray.add(icon) }.isSuccess
            if (!added) return@invokeAndWait
            icon.addActionListener {
                onOpen()
                tray.remove(icon)
            }
            icon.displayMessage(text.title, text.body, TrayIcon.MessageType.INFO)
            Timer(SHOWN_MS) { tray.remove(icon) }.apply { isRepeats = false }.start()
            shown = true
        }
        return shown
    }

    private fun iconImage(): BufferedImage {
        val fromApp =
            Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")?.use {
                runCatching { ImageIO.read(it) }.getOrNull()
            }
        if (fromApp != null) return fromApp
        // A tray icon needs an image even in a host that shipped none: a small neutral square.
        return BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).apply {
            val graphics = createGraphics()
            graphics.color = Color(0x33, 0x66, 0x99)
            graphics.fillRect(0, 0, 16, 16)
            graphics.dispose()
        }
    }
}
