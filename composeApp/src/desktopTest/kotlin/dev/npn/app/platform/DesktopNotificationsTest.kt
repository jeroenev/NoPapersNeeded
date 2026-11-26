package dev.npn.app.platform

import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.ReminderCandidate
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopNotificationsTest {
    /** A session bus that records what it is asked, with or without a notification service on it. */
    private class FakeBus(
        private val service: Boolean = true,
        private val capabilities: List<String> = listOf("body", "actions"),
    ) : NotificationBus {
        val host = mutableListOf<Pair<String, String>>()
        val portal = mutableListOf<Triple<String, String, String>>()
        var onHost: ((UInt) -> Unit)? = null
        var onPortal: ((String) -> Unit)? = null
        private var nextId = 1u

        override fun capabilities(): List<String> = if (service) capabilities else emptyList()

        override fun notify(
            summary: String,
            body: String,
        ): UInt? {
            if (!service) return null
            host += summary to body
            return nextId++
        }

        override fun addPortalNotification(
            id: String,
            title: String,
            body: String,
        ): Boolean {
            if (!service) return false
            portal += Triple(id, title, body)
            return true
        }

        override fun listen(
            onHost: (UInt) -> Unit,
            onPortal: (String) -> Unit,
        ) {
            this.onHost = onHost
            this.onPortal = onPortal
        }
    }

    private val text = ReminderText("Reminder: Due date", "Water & power <bill> is due on Dec 1, 2026.")

    @Test
    fun aHostNotificationGoesToTheFreedesktopServiceAndItsClickOpens() {
        val bus = FakeBus()
        var opened = 0
        assertTrue(FreedesktopNotifier(bus, sandboxed = false).show(text) { opened++ })
        assertEquals(listOf(text.title to text.body), bus.host)
        bus.onHost?.invoke(1u)
        assertEquals(1, opened)
        // A click on something no longer known, or a second click, opens nothing.
        bus.onHost?.invoke(1u)
        bus.onHost?.invoke(9u)
        assertEquals(1, opened)
    }

    @Test
    fun aSandboxedAppGoesThroughThePortal() {
        val bus = FakeBus()
        var opened = 0
        assertTrue(FreedesktopNotifier(bus, sandboxed = true).show(text) { opened++ })
        assertEquals(emptyList(), bus.host)
        val (id, title, body) = bus.portal.single()
        assertEquals(text.title to text.body, title to body)
        bus.onPortal?.invoke(id)
        assertEquals(1, opened)
    }

    @Test
    fun noNotificationServiceShowsNothingSoTheClaimIsKept() {
        assertFalse(FreedesktopNotifier(FakeBus(service = false), sandboxed = false).show(text) {})
        assertFalse(FreedesktopNotifier(FakeBus(service = false), sandboxed = true).show(text) {})
    }

    @Test
    fun aServiceThatRendersMarkupGetsTheTitleEscaped() {
        val bus = FakeBus(capabilities = listOf("body", "body-markup"))
        FreedesktopNotifier(bus, sandboxed = false).show(text) {}
        assertEquals("Water &amp; power &lt;bill&gt; is due on Dec 1, 2026.", bus.host.single().second)
    }

    @Test
    fun withTheAppLockOnNotificationsNameNothingInTheLibrary() =
        runTest {
            val reminder =
                ReminderCandidate(
                    "doc",
                    "Water bill",
                    "invoiceDueDate",
                    "Due date",
                    LocalDate(2026, 12, 1),
                    ReminderBucket.SOON,
                )
            val shown = ReminderTexts(hideDetails = false) { "1 Dec 2026" }
            val hidden = ReminderTexts(hideDetails = true) { "1 Dec 2026" }

            val detailed = shown.of(reminder)
            assertTrue("Water bill" in detailed.body && "1 Dec 2026" in detailed.body, detailed.toString())
            assertTrue("Due date" in detailed.title, detailed.toString())
            for (generic in listOf(hidden.of(reminder), hidden.summary(listOf(reminder, reminder)), shown.generic(1))) {
                val all = generic.title + generic.body
                assertFalse("Water bill" in all || "Due date" in all || "2026" in all, generic.toString())
            }
            val summary = shown.summary(listOf(reminder, reminder.copy(documentTitle = "Rent")))
            assertEquals(2, summary.body.lines().size)
        }
}
