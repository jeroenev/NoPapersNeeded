package dev.npn.app.platform

import java.io.File

/** Where a desktop reminder goes: the desktop's notification service, or nowhere (false). */
internal fun interface DesktopNotifier {
    /** Show [text]; [onOpen] runs when the user clicks it. False when nothing was shown. */
    fun show(
        text: ReminderText,
        onOpen: () -> Unit,
    ): Boolean
}

/**
 * The calls a Linux desktop's notifications take over the session D-Bus: the freedesktop
 * notification service on a host, the XDG notification portal inside a sandbox. Each answers false
 * (or null) when the bus or the service is not there, rather than throwing.
 */
internal interface NotificationBus {
    /** `org.freedesktop.Notifications.GetCapabilities`; empty when there is no service. */
    fun capabilities(): List<String>

    /**
     * `org.freedesktop.Notifications.Notify` with a `default` action, the notification's id, or null
     * when no service answered.
     */
    fun notify(
        summary: String,
        body: String,
    ): UInt?

    /** `org.freedesktop.portal.Notification.AddNotification` with an `app.open` default action. */
    fun addPortalNotification(
        id: String,
        title: String,
        body: String,
    ): Boolean

    /**
     * Listen for clicks: [onHost] gets a host notification's id, [onPortal] a portal notification's.
     * Started once, the first time a notification is shown.
     */
    fun listen(
        onHost: (UInt) -> Unit,
        onPortal: (String) -> Unit,
    )
}

/**
 * Reminders as freedesktop desktop notifications: GNOME, KDE and the others on X11 or Wayland alike,
 * where AWT's tray is missing or invisible. A Flatpak sandbox ([sandboxed]) cannot see the host's
 * service without a static permission, so it goes through the notification portal, which every
 * sandboxed app may use.
 */
internal class FreedesktopNotifier(
    private val bus: NotificationBus,
    private val sandboxed: Boolean = isFlatpakSandbox(),
) : DesktopNotifier {
    private val opens = LinkedHashMap<String, () -> Unit>()
    private var listening = false
    private var counter = 0

    /** Whether the host's service renders markup in a body, so a title's `&` or `<` must be escaped. */
    private val markup: Boolean by lazy { !sandboxed && "body-markup" in bus.capabilities() }

    @Synchronized
    override fun show(
        text: ReminderText,
        onOpen: () -> Unit,
    ): Boolean {
        val key: String
        if (sandboxed) {
            key = "reminder-${++counter}"
            if (!bus.addPortalNotification(key, text.title, text.body)) return false
        } else {
            val body = if (markup) escapeMarkup(text.body) else text.body
            key = (bus.notify(text.title, body) ?: return false).toString()
        }
        remember(key, onOpen)
        if (!listening) {
            listening = true
            bus.listen(onHost = { open(it.toString()) }, onPortal = ::open)
        }
        return true
    }

    @Synchronized
    private fun remember(
        key: String,
        onOpen: () -> Unit,
    ) {
        opens[key] = onOpen
        // Only the latest few can still be on screen; older clicks are not worth keeping.
        val oldestFirst = opens.keys.iterator()
        while (opens.size > KEPT_NOTIFICATIONS) {
            oldestFirst.next()
            oldestFirst.remove()
        }
    }

    /** Run what the clicked notification [key] opens, once. */
    fun open(key: String) {
        val action = synchronized(this) { opens.remove(key) }
        action?.invoke()
    }

    private companion object {
        const val KEPT_NOTIFICATIONS = 32
    }
}

/** The freedesktop body markup's special characters, escaped: a title is the user's text. */
internal fun escapeMarkup(text: String): String =
    buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                else -> append(char)
            }
        }
    }

/** Whether this process runs inside a Flatpak sandbox, which every sandboxed process can tell from this file. */
internal fun isFlatpakSandbox(): Boolean = File("/.flatpak-info").exists()
