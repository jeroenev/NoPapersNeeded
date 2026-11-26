package dev.npn.core.mail

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The password is kept per library under the account's server and username
 * ([MailAccount.accountKey]): a changed port or security keeps it and the place in the folder with
 * nothing copied, while a changed server or username is asked for the password again, here or on a
 * device the change reaches by sync, and is never sent the old server's password.
 */
class MailAccountKeyTest {
    private val enabled = MailDeviceChoices(enabledHere = true)

    @Test
    fun changingThePortOrSecurityKeepsThePasswordAndThePlace() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture = mailFixture(transport = transport)
            fixture.service.start()
            runCurrent()
            fixture.service.checkNow()

            val moved = testAccount.copy(security = MailSecurity.STARTTLS, port = 143)
            assertEquals(MailSaveOutcome.Saved, fixture.service.saveAccount(moved, null, enabled))
            runCurrent()

            // Nothing moved: the key never named the port or the security.
            assertEquals(mapOf(passwordKey() to "secret"), fixture.credentials.passwords)
            assertTrue(fixture.service.state.value.hasPassword)
            assertEquals(1L, fixture.cursors.of(moved)?.lastUid)
            transport.messages += testMessage(uid = 2, attachments = listOf(testPdf.copy(fileName = "second.pdf")))
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            // Only the new message: the folder is not read from the start again.
            assertEquals(1, report.imported)
            assertEquals("second.pdf", fixture.sink.importedNames.last())
        }

    @Test
    fun aServerChangedOnAnotherDeviceIsAskedForThePasswordAgain() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture = mailFixture(transport = transport)
            fixture.service.start()
            runCurrent()
            assertTrue(fixture.service.state.value.hasPassword)

            val renamed = testAccount.copy(host = "imap.elsewhere.example")
            fixture.store.flow.value = renamed
            runCurrent()

            assertFalse(fixture.service.state.value.hasPassword)
            assertTrue(fixture.service.state.value.passwordKnown)
            // The other server is never sent this one's password: no connection is even opened.
            val opened = transport.opens
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.NO_PASSWORD, report.errors.single().reason)
            assertEquals(opened, transport.opens)
            assertEquals(null, fixture.credentials.passwords[passwordKey(renamed)])
        }

    @Test
    fun aUsernameChangedHereNeedsThePasswordAgainAndDropsTheOldOne() =
        runTest {
            val fixture = mailFixture()
            val other = testAccount.copy(username = "someone-else@example.com")

            assertEquals(MailSaveOutcome.Saved, fixture.service.saveAccount(other, null, enabled))

            assertEquals(emptyMap(), fixture.credentials.passwords)
            assertFalse(fixture.service.state.value.hasPassword)
        }

    @Test
    fun testingAnotherServerWithABlankPasswordDoesNotSendTheSavedOne() =
        runTest {
            val fixture = mailFixture()
            assertEquals(
                MailTestOutcome.Failed(MailFailureReason.NO_PASSWORD),
                fixture.service.testConnection(testAccount.copy(host = "imap.elsewhere.example"), null, false),
            )
            // Another port is the same mailbox: its saved password is the one to test with.
            assertEquals(MailTestOutcome.Ok, fixture.service.testConnection(testAccount.copy(port = 1993), null, false))
        }

    @Test
    fun aPasswordUnderTheEarlierKeyMovesToThisLibrarysOnce() =
        runTest {
            val credentials = FakeCredentials(password = null)
            credentials.passwords[testAccount.legacyAccountKey] = "old secret"
            val fixture = mailFixture(credentials = credentials)
            fixture.service.start()
            runCurrent()

            assertEquals(mapOf(passwordKey() to "old secret"), credentials.passwords)
            assertTrue(fixture.service.state.value.hasPassword)

            // Once: a password under the earlier key later (another library's, say) is left alone.
            assertEquals(MailSaveOutcome.Saved, fixture.service.clearPassword())
            credentials.passwords[testAccount.legacyAccountKey] = "another"
            fixture.service.lookUpPassword()
            runCurrent()
            assertEquals(mapOf(testAccount.legacyAccountKey to "another"), credentials.passwords)
            assertFalse(fixture.service.state.value.hasPassword)
        }

    @Test
    fun aPasswordUnderAnotherServersEarlierKeyIsNotMoved() =
        runTest {
            val credentials = FakeCredentials(password = null)
            val elsewhere = testAccount.copy(host = "imap.elsewhere.example")
            credentials.passwords[elsewhere.legacyAccountKey] = "old secret"
            val fixture = mailFixture(credentials = credentials)
            fixture.service.lookUpPassword()
            runCurrent()

            assertEquals(mapOf(elsewhere.legacyAccountKey to "old secret"), credentials.passwords)
            assertFalse(fixture.service.state.value.hasPassword)
        }

    @Test
    fun theCursorUnderTheEarlierKeyIsKept() =
        runTest {
            val fixture = mailFixture()
            fixture.cursors.save(
                MailCursor(
                    testAccount.legacyAccountKey,
                    testAccount.folder,
                    uidValidity = 7,
                    lastUid = 5,
                    startFrom = testAccount.startFrom,
                ),
            )
            fixture.service.start()
            runCurrent()

            assertEquals(5L, fixture.cursors.of(testAccount)?.lastUid)
        }

    @Test
    fun twoLibrariesOnOneDesktopKeepTheirOwnPasswords() =
        runTest {
            val credentials = FakeCredentials()
            val first = mailFixture(credentials = credentials)
            val second =
                MailService(
                    store = FakeAccountStore(testAccount),
                    credentials = credentials,
                    keyScope = "library-2",
                    cursors = FakeCursors(),
                    transport = FakeTransport(),
                    sink = FakeSink(),
                    pdfRenderer = FakeRenderer(),
                    clock = { NOW },
                    log = dev.npn.core.util.NpnLog.None,
                    scope = backgroundScope,
                )
            assertEquals(MailSaveOutcome.Saved, second.saveAccount(testAccount, "second secret", enabled))

            assertEquals(MailSaveOutcome.Saved, first.service.clearPassword())

            assertEquals(mapOf("library-2/${testAccount.accountKey}" to "second secret"), credentials.passwords)
        }
}
