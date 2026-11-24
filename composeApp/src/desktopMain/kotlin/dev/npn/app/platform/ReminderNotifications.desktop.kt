package dev.npn.app.platform

import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.OpenDocumentRequest
import dev.npn.app.ui.common.OpenDocumentRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notification_body
import no_papers_needed.composeapp.generated.resources.reminders_notification_overdue
import no_papers_needed.composeapp.generated.resources.reminders_notification_title
import org.jetbrains.compose.resources.getString
import java.awt.Color
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * A desktop reminder has no notification permission to ask for; the tray is used when the desktop
 * has one and the in-app Upcoming badge otherwise.
 */
actual fun canShowReminderNotifications(): Boolean = true

actual suspend fun showReminderNotification(
    docId: String,
    libraryLocation: String,
    documentTitle: String,
    fieldKey: String,
    fieldLabel: String,
    dueDate: String,
    overdue: Boolean,
): ReminderDelivery {
    val title = getString(Res.string.reminders_notification_title, fieldLabel)
    val body =
        getString(
            if (overdue) Res.string.reminders_notification_overdue else Res.string.reminders_notification_body,
            documentTitle,
            dueDate,
        )
    val tray = withContext(Dispatchers.Swing) { DesktopTray.icon() } ?: return ReminderDelivery.IN_APP
    withContext(Dispatchers.Swing) {
        DesktopTray.lastDocument = OpenDocumentRequest(docId, libraryLocation)
        tray.displayMessage(title, body, TrayIcon.MessageType.INFO)
    }
    return ReminderDelivery.NOTIFIED
}

actual fun scheduleReminderChecks(
    active: Boolean,
    delayMillis: Long,
) = Unit

@Composable
actual fun rememberReminderPermissionRequest(): (() -> Unit)? = null

/**
 * One tray icon for the process. A click (AWT delivers it to the icon's action listener) opens the
 * document the latest message was about. Everything here is AWT-thread-only.
 */
private object DesktopTray {
    private var tray: TrayIcon? = null
    var lastDocument: OpenDocumentRequest? = null

    fun icon(): TrayIcon? {
        tray?.let { return it }
        if (!SystemTray.isSupported()) return null
        val created =
            runCatching {
                TrayIcon(iconImage(), "No papers needed").apply {
                    isImageAutoSize = true
                    addActionListener { lastDocument?.let(OpenDocumentRequests.documents::set) }
                }
            }.getOrNull() ?: return null
        tray =
            runCatching {
                SystemTray.getSystemTray().add(created)
                created
            }.getOrNull()
        return tray
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
