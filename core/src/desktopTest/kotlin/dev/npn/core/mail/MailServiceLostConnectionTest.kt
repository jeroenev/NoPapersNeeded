package dev.npn.core.mail

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A message whose download drops the connection every time, on every fresh connection, must not
 * stop the folder for good: each check it ends counts against it, and after a few it is passed over
 * like a message that keeps failing, and listed for a retry. A network that is down is not the
 * message's fault and counts nothing.
 */
class MailServiceLostConnectionTest {
    private fun messages() =
        listOf(
            testMessage(uid = 1, attachments = listOf(testPdf.copy(fileName = "one.pdf"))),
            testMessage(uid = 2, attachments = listOf(testPdf.copy(fileName = "two.pdf"))),
            testMessage(uid = 3, attachments = listOf(testPdf.copy(fileName = "three.pdf"))),
        )

    @Test
    fun aMessageThatDropsEveryConnectionIsPassedOverAfterAFewChecks() =
        runTest {
            val transport = FakeTransport(messages = messages(), droppingUids = setOf(2L))
            val fixture = mailFixture(transport = transport)

            repeat(2) { check ->
                val report = fixture.service.checkNow()
                assertEquals(MailFailureReason.FETCH_FAILED, report.errors.single().reason)
                val cursor = fixture.cursors.last()
                assertEquals(1L, cursor.lastUid, "check ${check + 1}")
                assertEquals(2L, cursor.blockedUid)
                assertEquals(check + 1, cursor.blockedAttempts)
            }

            val third = fixture.service.checkNow()
            assertEquals(1, third.unreadable)
            assertEquals(listOf(2L), fixture.cursors.last().passedOver)
            assertEquals(2L, fixture.cursors.last().lastUid)

            // The folder goes on past it, and it stays listed until a retry imports it.
            val fourth = fixture.service.checkNow()
            assertTrue(fourth.errors.isEmpty())
            assertEquals(listOf("one.pdf", "three.pdf"), fixture.sink.importedNames)
            assertEquals(3L, fixture.cursors.last().lastUid)
            assertEquals(listOf(2L), fixture.cursors.last().passedOver)

            transport.droppingUids = emptySet()
            fixture.service.retryPassedOver()
            assertEquals(emptyList(), fixture.cursors.last().passedOver)
            assertEquals("two.pdf", fixture.sink.importedNames.last())
        }

    @Test
    fun aServerThatCannotBeReachedAgainCountsNothingAgainstTheMessage() =
        runTest {
            // The download drops the connection, and no new one opens: the network, not the message.
            val transport = FakeTransport(messages = messages(), droppingUids = setOf(2L), opensAllowed = 1)
            val fixture = mailFixture(transport = transport)

            repeat(5) {
                fixture.service.checkNow()
                transport.opensAllowed = transport.opens + 1
            }

            val cursor = fixture.cursors.last()
            assertEquals(1L, cursor.lastUid)
            assertEquals(null, cursor.blockedUid)
            assertEquals(emptyList(), cursor.passedOver)
        }
}
