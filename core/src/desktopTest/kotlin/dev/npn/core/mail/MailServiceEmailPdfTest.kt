package dev.npn.core.mail

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An email read again (a renumbered folder, a start moved back, another device) is known by its
 * Message-ID: its PDF is not made and imported a second time, even when the renderer would now make
 * other bytes of it.
 */
class MailServiceEmailPdfTest {
    private val account = testAccount.copy(saveEmailAsPdf = true)

    private fun email(uid: Long = 1) =
        testMessage(uid = uid, attachments = emptyList(), dateMillis = NOW).copy(messageId = "abc@mail.example.com")

    @Test
    fun anEmailReadAgainIsNotMadeIntoASecondPdf() =
        runTest {
            val transport = FakeTransport(messages = listOf(email()))
            val fixture = mailFixture(account = account, transport = transport)
            assertEquals(1, fixture.service.checkNow().imported)

            // The folder renumbered: the same email under a new UID, and a renderer that changed.
            transport.validity = 8
            transport.messages = listOf(email(uid = 1))
            fixture.renderer.bytes = byteArrayOf(4, 5, 6)
            val again = fixture.service.checkNow()

            assertEquals(0, again.imported)
            assertEquals(1, again.duplicates)
            assertEquals(1, fixture.renderer.rendered.size)
            assertEquals(1, fixture.sink.importedNames.size)
            // Handled: the post-action ran on it again, as for any duplicate.
            assertEquals(listOf(1L, 1L), transport.marked)
        }

    @Test
    fun anEmailWhosePdfTheUserRemovedStaysInTheMailbox() =
        runTest {
            val transport = FakeTransport(messages = listOf(email()))
            val fixture = mailFixture(account = account, transport = transport)
            fixture.sink.messageIds["abc@mail.example.com"] = MailImportResult.Removed

            val report = fixture.service.checkNow()

            assertEquals(0, report.imported)
            assertEquals(1, report.duplicates)
            assertEquals(emptyList(), fixture.renderer.rendered)
            assertEquals(emptyList(), transport.marked)
        }

    @Test
    fun anEmailWithoutAMessageIdIsMadeIntoAPdfAsBefore() =
        runTest {
            val transport = FakeTransport(messages = listOf(email().copy(messageId = null)))
            val fixture = mailFixture(account = account, transport = transport)
            assertEquals(1, fixture.service.checkNow().imported)
            assertEquals(emptyMap(), fixture.sink.messageIds)
        }
}
