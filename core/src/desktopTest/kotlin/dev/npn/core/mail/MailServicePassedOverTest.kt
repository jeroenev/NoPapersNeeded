package dev.npn.core.mail

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a check leaves behind, and who checks at all: mail passed over stays listed until a retry
 * imports it, a server's refusal is retried rather than taken for an unreadable email, a document
 * the user removed keeps its email where it is, and only a device whose user turned import on reads
 * the mailbox.
 */
class MailServicePassedOverTest {
    @Test
    fun aRefusedDownloadIsRetriedOnLaterChecksBeforeItIsPassedOver() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)), refusedUids = setOf(1))
            val fixture = mailFixture(transport = transport)

            val first = fixture.service.checkNow()

            assertEquals(MailFailureReason.FETCH_FAILED, first.errors.single().reason)
            assertEquals(0, first.unreadable)
            assertEquals(0, fixture.cursors.last().lastUid)
            assertEquals(emptyList(), fixture.cursors.last().passedOver)

            fixture.service.checkNow()
            val third = fixture.service.checkNow()

            assertEquals(1, third.unreadable)
            assertEquals(1, fixture.cursors.last().lastUid)
            assertEquals(listOf(1L), fixture.cursors.last().passedOver)
            assertTrue(transport.marked.isEmpty())
        }

    @Test
    fun passedOverMailStaysListedAfterLaterChecksAndARetryImportsIt() =
        runTest {
            val transport =
                FakeTransport(messages = listOf(testMessage(uid = 1), testMessage(uid = 2)), refusedUids = setOf(1))
            val fixture = mailFixture(transport = transport)
            repeat(3) { fixture.service.checkNow() }
            runCurrent()
            assertEquals(1, fixture.service.state.value.passedOver)

            // A later check that passes nothing over keeps the list: it is not the last report's.
            val later = fixture.service.checkNow()
            runCurrent()
            assertEquals(0, later.unreadable)
            assertEquals(1, fixture.service.state.value.passedOver)

            transport.refusedUids = emptySet()
            val retried = fixture.service.retryPassedOver()
            runCurrent()

            assertEquals(listOf(1L), transport.described.single())
            assertEquals(1, retried.imported)
            assertEquals(listOf(2L, 1L), transport.marked)
            assertEquals(emptyList(), fixture.cursors.last().passedOver)
            assertEquals(0, fixture.service.state.value.passedOver)
        }

    @Test
    fun aRetryOfMailThatStillCannotBeReadKeepsItListed() =
        runTest {
            val unreadable =
                testMessage(
                    uid = 1,
                ).copy(readFailure = MailFailureReason.MESSAGE_UNREADABLE, readFailureCause = "ParseException")
            val transport = FakeTransport(messages = listOf(unreadable))
            val fixture = mailFixture(transport = transport)
            fixture.service.checkNow()
            assertEquals(listOf(1L), fixture.cursors.last().passedOver)

            val retried = fixture.service.retryPassedOver()

            assertEquals(1, retried.unreadable)
            assertEquals(listOf(1L), fixture.cursors.last().passedOver)
        }

    @Test
    fun aRetryForgetsMailNoLongerInTheFolder() =
        runTest {
            val unreadable = testMessage(uid = 1).copy(readFailure = MailFailureReason.MESSAGE_UNREADABLE)
            val transport = FakeTransport(messages = listOf(unreadable, testMessage(uid = 2)))
            val fixture = mailFixture(transport = transport)
            fixture.service.checkNow()
            transport.messages = transport.messages.drop(1)

            fixture.service.retryPassedOver()

            assertEquals(emptyList(), fixture.cursors.last().passedOver)
        }

    @Test
    fun anEmailMissingAnAttachmentIsListedToo() =
        runTest {
            val message = testMessage(uid = 1).copy(droppedParts = listOf(MailDroppedPart.TOO_LARGE))
            val transport = FakeTransport(messages = listOf(message))
            val fixture = mailFixture(transport = transport)

            fixture.service.checkNow()

            assertEquals(listOf(1L), fixture.cursors.last().passedOver)
            assertTrue(transport.marked.isEmpty())
        }

    @Test
    fun aRenumberedFolderForgetsThePassedOverList() =
        runTest {
            val unreadable = testMessage(uid = 1).copy(readFailure = MailFailureReason.MESSAGE_UNREADABLE)
            val transport = FakeTransport(messages = listOf(unreadable))
            val fixture = mailFixture(transport = transport)
            fixture.service.checkNow()
            transport.validity = 8
            transport.messages = listOf(testMessage(uid = 1))

            fixture.service.checkNow()

            assertEquals(emptyList(), fixture.cursors.last().passedOver)
        }

    @Test
    fun aDocumentTheUserRemovedLeavesItsEmailInTheMailbox() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture = mailFixture(transport = transport)
            fixture.sink.onImport = { MailImportResult.Removed }

            val report = fixture.service.checkNow()

            assertEquals(1, report.duplicates)
            assertTrue(report.errors.isEmpty())
            assertTrue(transport.marked.isEmpty())
            // Not a failure: the cursor moves on and the email is not offered for a retry.
            assertEquals(1, fixture.cursors.last().lastUid)
            assertEquals(emptyList(), fixture.cursors.last().passedOver)
        }

    @Test
    fun aDeviceWhoseUserDidNotTurnImportOnNeverPolls() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture = mailFixture(transport = transport)
            fixture.cursors.enabled = false

            fixture.service.pollIfDue()

            assertEquals(0, transport.opens)

            fixture.service.saveAccount(testAccount, null, MailDeviceChoices(enabledHere = true))
            fixture.service.pollIfDue()

            assertTrue(fixture.cursors.enabled)
            assertEquals(1, transport.opens)
        }

    @Test
    fun withoutAKeyringThisDeviceCannotImport() =
        runTest {
            val transport = FakeTransport(messages = listOf(testMessage(uid = 1)))
            val fixture =
                mailFixture(
                    transport = transport,
                    credentials = FakeCredentials(failure = MailSecretFailure.UNSUPPORTED),
                )
            runCurrent()

            fixture.service.pollIfDue()

            assertFalse(fixture.service.state.value.transportAvailable)
            assertEquals(0, transport.opens)
        }

    @Test
    fun importedThroughNamesTheDateTheServerReceivedTheMail() =
        runTest {
            val sent = 5 * MailStartPoint.DAY_MILLIS
            val received = 9 * MailStartPoint.DAY_MILLIS
            val message = testMessage(uid = 1, dateMillis = sent)
            val transport = FakeTransport(messages = listOf(message), receivedMillis = mapOf(1L to received))
            val fixture = mailFixture(transport = transport)

            fixture.service.checkNow()

            assertEquals(received, fixture.cursors.last().throughMillis)
        }
}
