package dev.npn.app.platform

import org.junit.Assume.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real GDBus calls, against a notification service on a private session bus. Opt-in, as it
 * needs one: `NPN_DBUS_NOTIFICATIONS=1` under `dbus-run-session` with a stand-in service that
 * answers `Notify` with id 7 and then reports a click on it (DEVELOPMENT.md, "Desktop
 * notifications"). Never against the developer's own session: it would show a notification there.
 */
class GioNotificationBusTest {
    @Test
    fun aNotificationReachesTheServiceAndItsClickComesBack() {
        val enabled = System.getenv("NPN_DBUS_NOTIFICATIONS") == "1"
        assumeTrue("Set NPN_DBUS_NOTIFICATIONS=1 under dbus-run-session", enabled)
        val bus = assertNotNull(GioNotificationBus.createOrNull(), "GLib's GIO is not installed")
        assertTrue("actions" in bus.capabilities(), bus.capabilities().toString())

        val clicked = CountDownLatch(1)
        var clickedId: UInt? = null
        bus.listen(onHost = {
            clickedId = it
            clicked.countDown()
        }, onPortal = {})
        val id = bus.notify("Reminder: Due date", "Water & power is due on 1 Dec 2026.")

        assertEquals(7u, id)
        assertTrue(clicked.await(10, TimeUnit.SECONDS), "No click came back")
        assertEquals(7u, clickedId)
    }
}
