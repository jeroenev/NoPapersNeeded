package dev.npn.core.mail

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What routine checks may do on their own: never retry a password the server refused, and never
 * read the keyring on a device that does not check the account (a locked keyring would ask to be
 * unlocked at every start).
 */
class MailServicePollingTest {
    private val interval = testAccount.pollIntervalMinutes * 60_000L

    @Test
    fun aRefusedPasswordStopsRoutineChecksUntilThePasswordChanges() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            transport.openFailure = MailFailureReason.AUTHENTICATION_FAILED
            val fixture = mailFixture(transport = transport)

            fixture.service.pollIfDue()
            assertEquals(1, transport.opens)
            runCurrent()
            assertTrue(fixture.service.state.value.loginRefused)

            fixture.now += 10 * interval
            fixture.service.pollIfDue()
            assertEquals(1, transport.opens)

            // Check now is the user asking: it still tries.
            fixture.service.checkNow()
            assertEquals(2, transport.opens)

            transport.openFailure = null
            val saved = fixture.service.saveAccount(testAccount, "right secret", MailDeviceChoices(enabledHere = true))
            assertEquals(MailSaveOutcome.Saved, saved)
            runCurrent()
            assertFalse(fixture.service.state.value.loginRefused)
            fixture.now += 10 * interval
            fixture.service.pollIfDue()
            assertEquals(3, transport.opens)
            assertEquals(1, fixture.sink.importedNames.size)
        }

    @Test
    fun aRefusedPasswordStaysRefusedAcrossARestart() =
        runTest {
            val transport = FakeTransport(openFailure = MailFailureReason.AUTHENTICATION_FAILED)
            val fixture = mailFixture(transport = transport)
            fixture.service.pollIfDue()

            val restarted = fixture.newService()
            restarted.start()
            runCurrent()
            assertTrue(restarted.state.value.loginRefused)
            fixture.now += 10 * interval
            restarted.pollIfDue()
            assertEquals(1, transport.opens)
        }

    @Test
    fun aChangedAccountResumesRoutineChecks() =
        runTest {
            val transport = FakeTransport(openFailure = MailFailureReason.AUTHENTICATION_FAILED)
            val fixture = mailFixture(transport = transport)
            fixture.service.pollIfDue()
            assertEquals(1, transport.opens)

            // Another security can be what the server wanted: the same password is worth one more try.
            fixture.store.flow.value = testAccount.copy(security = MailSecurity.STARTTLS, port = 143)
            fixture.now += 10 * interval
            fixture.service.pollIfDue()
            assertEquals(2, transport.opens)
        }

    @Test
    fun aDeviceThatDoesNotCheckNeverReadsTheKeyringUntilThePaneAsks() =
        runTest {
            val fixture = mailFixture()
            fixture.cursors.enabled = false
            val service = fixture.newService()
            service.start()
            runCurrent()
            fixture.now += 10 * interval
            service.pollIfDue()
            fixture.store.flow.value = testAccount.copy(folder = "Invoices")
            runCurrent()

            assertEquals(emptyList(), fixture.credentials.loads)
            assertFalse(service.state.value.passwordKnown)

            service.lookUpPassword()
            runCurrent()
            assertTrue(fixture.credentials.loads.isNotEmpty())
            assertTrue(service.state.value.passwordKnown)
            assertTrue(service.state.value.hasPassword)
        }
}
