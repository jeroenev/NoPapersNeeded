package dev.npn.core.mail

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A changed host, username or port gives the account a new key ([MailAccount.accountKey]); the
 * password this device saved and its place in the folder move to it, whether the change was made
 * here with the password field left blank or arrived from another device.
 */
class MailAccountKeyTest {
    private val moved = testAccount.copy(security = MailSecurity.STARTTLS, port = 143)

    @Test
    fun changingTheConnectionWithABlankPasswordKeepsThePasswordAndThePlace() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture = mailFixture(transport = transport)
            fixture.service.start()
            runCurrent()
            fixture.service.checkNow()

            assertEquals(MailSaveOutcome.Saved, fixture.service.saveAccount(moved, null, enabledHere = true))
            runCurrent()

            assertEquals(mapOf(moved.accountKey to "secret"), fixture.credentials.passwords)
            assertTrue(fixture.service.state.value.hasPassword)
            assertEquals(1L, fixture.cursors.of(moved)?.lastUid)
            transport.messages += testMessage(uid = 2, attachments = listOf(testPdf.copy(fileName = "second.pdf")))
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            // Only the new message: the moved cursor does not read the folder from the start again.
            assertEquals(1, report.imported)
            assertEquals("second.pdf", fixture.sink.importedNames.last())
        }

    @Test
    fun aChangeFromAnotherDeviceWhileClosedCarriesThePasswordAndThePlaceOver() =
        runTest {
            val fixture = mailFixture()
            fixture.cursors.enabled = false
            fixture.cursors.followed = testAccount.accountKey
            fixture.cursors.save(
                MailCursor(
                    testAccount.accountKey,
                    testAccount.folder,
                    uidValidity = 7,
                    lastUid = 5,
                    startFrom = testAccount.startFrom,
                ),
            )
            val renamed = testAccount.copy(host = "imap.example.com")
            fixture.store.flow.value = renamed

            val service = fixture.newService()
            service.start()
            runCurrent()

            assertEquals(mapOf(renamed.accountKey to "secret"), fixture.credentials.passwords)
            assertEquals(5L, fixture.cursors.of(renamed)?.lastUid)
            assertEquals(renamed.accountKey, fixture.cursors.followed)
            assertTrue(service.state.value.hasPassword)
        }

    @Test
    fun aPasswordTheKeyringCannotMoveIsReported() =
        runTest {
            val fixture = mailFixture()
            fixture.cursors.followed = testAccount.accountKey
            fixture.credentials.failure = MailSecretFailure.UNAVAILABLE

            val outcome = fixture.service.saveAccount(moved, null, enabledHere = true)

            assertEquals(MailSaveOutcome.CredentialFailed(MailSecretFailure.UNAVAILABLE), outcome)
            // Not followed yet: the next start or account change tries the move again.
            assertEquals(testAccount.accountKey, fixture.cursors.followed)
        }

    @Test
    fun testingAChangedConnectionWithABlankPasswordUsesTheSavedOne() =
        runTest {
            val fixture = mailFixture()
            assertEquals(MailTestOutcome.Ok, fixture.service.testConnection(moved, null))
        }
}
