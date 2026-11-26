package dev.npn.core.mail

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a check costs and how it survives trouble: which messages are downloaded at all, which parts
 * of them, how an unreadable message or a lost attachment is passed over, and how a connection that
 * drops is reopened.
 */
class MailServiceDownloadTest {
    private val account = testAccount
    private val pdf = testPdf

    private fun message(
        uid: Long = 1,
        attachments: List<MailAttachment> = listOf(pdf),
        from: String = "sender@example.com",
        subject: String = "Invoice",
        dateMillis: Long = 0,
    ) = testMessage(uid, attachments, from, subject, dateMillis)

    private fun backlog(uids: LongRange) = testBacklog(uids)

    private fun kotlinx.coroutines.test.TestScope.fixture(
        account: MailAccount = testAccount,
        transport: FakeTransport = FakeTransport(),
        credentials: FakeCredentials = FakeCredentials(),
    ) = mailFixture(account, transport, credentials)

    @Test
    fun anUnreadableMessageIsPassedOverAtOnceWithoutStallingLaterMail() =
        runTest {
            // A message whose structure does not parse: reading it again gives the same answer.
            val unreadable =
                message(uid = 1, attachments = emptyList()).copy(
                    readFailure = MailFailureReason.MESSAGE_UNREADABLE,
                    readFailureCause = "ParseException",
                )
            val transport = FakeTransport(messages = listOf(unreadable, message(uid = 2)))
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            assertEquals(1, report.unreadable)
            assertEquals(1, report.imported)
            assertEquals(listOf("invoice.pdf"), fixture.sink.importedNames)
            // The unreadable message keeps its place in the mailbox: no post-action touched it.
            assertEquals(listOf(2L), transport.marked)
            assertEquals(2, fixture.cursors.last().lastUid)
            assertEquals(null, fixture.cursors.last().lastError)
        }

    @Test
    fun anEmailThatLostAnAttachmentImportsTheRestAndStaysInTheMailbox() =
        runTest {
            val partial = message(uid = 1).copy(droppedParts = listOf(MailDroppedPart.TOO_LARGE))
            val transport = FakeTransport(messages = listOf(partial, message(uid = 2)))
            val fixture =
                fixture(
                    account = account.copy(postAction = MailPostAction.MOVE, moveToFolder = "Done"),
                    transport = transport,
                )
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            assertEquals(2, report.imported)
            assertEquals(1, report.droppedAttachments)
            // Moving it would take the oversized attachment with it.
            assertEquals(listOf(2L to "Done"), transport.moved)
            assertEquals(2, fixture.cursors.last().lastUid)
        }

    @Test
    fun aFailingMessageIsRetriedAcrossRestartsThenPassedOver() =
        runTest {
            val transport = FakeTransport(messages = listOf(message(uid = 1), message(uid = 2, subject = "Later")))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport =
                { name ->
                    if (fixture.sink.importedNames.size <=
                        3
                    ) {
                        MailImportResult.Failed
                    } else {
                        MailImportResult.Imported("doc:$name")
                    }
                }
            assertEquals(
                MailFailureReason.IMPORT_FAILED,
                fixture.service
                    .checkNow()
                    .errors
                    .single()
                    .reason,
            )
            assertEquals(1L, fixture.cursors.last().blockedUid)
            assertEquals(1, fixture.cursors.last().blockedAttempts)

            // An app restart keeps the count: the folder is not blocked for another full round.
            val restarted = fixture.newService()
            assertEquals(
                MailFailureReason.IMPORT_FAILED,
                restarted
                    .checkNow()
                    .errors
                    .single()
                    .reason,
            )
            assertEquals(2, fixture.cursors.last().blockedAttempts)
            val third = restarted.checkNow()
            // MailService.MAX_MESSAGE_ATTEMPTS: the third failed check passes it over and reads on.
            assertTrue(third.errors.isEmpty())
            assertEquals(1, third.unreadable)
            assertEquals(1, third.imported)
            assertEquals(listOf(2L), transport.marked)
            assertEquals(2, fixture.cursors.last().lastUid)
            assertEquals(null, fixture.cursors.last().blockedUid)
        }

    @Test
    fun aDroppedConnectionIsReopenedWithinTheCheck() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..25L), transientFetchFailures = 2)
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            assertEquals(25, report.imported)
            assertEquals(3, transport.opens)
            assertEquals(3, transport.closes)
            assertEquals(0, transport.callsAfterClose)
        }

    @Test
    fun aConnectionThatKeepsDroppingEndsTheCheckAfterBoundedReconnects() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..5L), transientFetchFailures = 10)
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.FETCH_FAILED, report.errors.single().reason)
            // The first connection and MailService.MAX_RECONNECTS fresh ones.
            assertEquals(4, transport.opens)
            assertEquals(4, transport.closes)
            assertEquals(0, fixture.cursors.last().lastUid)
        }

    @Test
    fun messagesWithNothingToImportAreNeverDownloaded() =
        runTest {
            // A mailbox as most are: mail without attachments, and now and then an invoice.
            val plain = (1L..60L).map { message(uid = it, attachments = emptyList(), subject = "Newsletter $it") }
            val docx = message(uid = 61, attachments = listOf(MailAttachment("letter.docx", null, byteArrayOf(1))))
            val transport = FakeTransport(messages = plain + docx + message(uid = 62))
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(62, report.messagesSeen)
            assertEquals(61, report.skipped)
            assertEquals(1, report.imported)
            // Only the one message with a PDF had a part downloaded, and not its text.
            assertEquals(listOf(62L to false), transport.downloads)
            assertEquals(1, transport.fetches)
        }

    @Test
    fun messagesTheFiltersDoNotAskForAreNeverDownloaded() =
        runTest {
            val filtered = account.copy(filters = listOf(MailFilter(subject = "invoice")))
            val messages =
                listOf(
                    message(uid = 1, subject = "Invoice 1"),
                    message(uid = 2, subject = "Holiday photos"),
                    message(uid = 3, subject = "Ticket confirmation"),
                    message(uid = 4, subject = "Invoice 2"),
                )
            val transport = FakeTransport(messages = messages)
            val fixture = fixture(account = filtered, transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(2, report.imported)
            assertEquals(2, report.skipped)
            assertEquals(listOf(1L, 4L), transport.downloads.map { it.first })
        }

    @Test
    fun theTextIsDownloadedOnlyForTheEmailPdf() =
        runTest {
            val transport = FakeTransport(messages = listOf(message(uid = 1, attachments = emptyList())))
            val fixture = fixture(account = account.copy(saveEmailAsPdf = true), transport = transport)
            fixture.service.checkNow()
            assertEquals(listOf(1L to true), transport.downloads)
            assertEquals(
                "body",
                fixture.renderer.rendered
                    .single()
                    .bodyText,
            )
        }

    @Test
    fun theReconnectBudgetStartsAgainAfterEveryPageThatMadeProgress() =
        runTest {
            // Three pages, and the connection dropped three times in a row before each later page:
            // six drops in all, more than one check's budget would allow if it never reset.
            val transport =
                FakeTransport(messages = backlog(1L..250L), failingFetches = setOf(2, 3, 4, 6, 7, 8))
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            assertEquals(250, report.imported)
            assertEquals(7, transport.opens)
            assertEquals(7, transport.closes)
            assertEquals(false, fixture.cursors.last().catchingUp)
        }

    @Test
    fun aConnectionLostMidDownloadReadsTheMessageAgainWithoutSkippingOrRepeating() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..5L), failingDownloads = setOf(3))
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertTrue(report.errors.isEmpty())
            assertEquals(5, report.messagesSeen)
            assertEquals((1L..5L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
            assertEquals((1L..5L).toList(), transport.marked)
            assertEquals(2, transport.opens)
        }
}
