package dev.npn.core.mail

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.activation.DataHandler
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import kotlinx.coroutines.test.runTest
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The production transport against a real IMAP server (GreenMail, in-process): what the fakes of
 * the service tests take for granted about Jakarta Mail, the UID listing, the bulk description,
 * reading without marking read, and the post-actions, is checked here against the protocol.
 */
class JakartaMailTransportTest {
    private val greenMail = GreenMail(ServerSetupTest.IMAP.dynamicPort())
    private val transport = JakartaMailTransport()

    private lateinit var account: MailAccount

    @BeforeTest
    fun startServer() {
        greenMail.start()
        greenMail.setUser(ADDRESS, USER, PASSWORD)
        account =
            MailAccount(
                host = "127.0.0.1",
                port = greenMail.imap.port,
                security = MailSecurity.PLAIN,
                allowPlain = true,
                username = USER,
            )
    }

    @AfterTest
    fun stopServer() {
        greenMail.stop()
    }

    @Test
    fun aPageDescribesMessagesAndADownloadReadsTheAttachmentWithoutMarkingItRead() =
        runTest {
            deliver(subject = "Invoice", pdf = PDF)
            deliver(subject = "Hello")

            val connection = open(account)
            try {
                val page = assertIs<MailFetchResult.Success>(connection.fetchAfter(0))
                assertEquals(listOf("Invoice", "Hello"), page.messages.map { it.subject })
                assertEquals(listOf("invoice.pdf"), page.messages[0].attachments.map { it.fileName })
                assertEquals(emptyList(), page.messages[1].attachments)
                assertTrue(page.scannedThroughUid >= page.messages.last().uid)

                val downloaded = assertIs<MailDownloadResult.Success>(connection.download(page.messages[0].uid, false))
                assertContentEquals(
                    PDF,
                    downloaded.message.attachments
                        .single()
                        .bytes,
                )
            } finally {
                connection.close()
            }
            assertFalse(flags("INBOX").single { it.subject == "Invoice" }.isSet(Flags.Flag.SEEN))
        }

    @Test
    fun describingByUidLeavesOutWhatTheFolderNoLongerHolds() =
        runTest {
            deliver(subject = "One", pdf = PDF)
            val connection = open(account)
            try {
                val uid = assertIs<MailFetchResult.Success>(connection.fetchAfter(0)).messages.single().uid
                val described = assertIs<MailFetchResult.Success>(connection.describe(listOf(uid, uid + 50)))
                assertEquals(listOf(uid), described.messages.map { it.uid })
            } finally {
                connection.close()
            }
        }

    @Test
    fun movingToTheTrashTakesTheMessageOutOfTheFolderIntoTheTrash() =
        runTest {
            createFolder("Trash")
            deliver(subject = "Invoice", pdf = PDF)
            val connection = open(account.copy(postAction = MailPostAction.DELETE))
            try {
                val uid = assertIs<MailFetchResult.Success>(connection.fetchAfter(0)).messages.single().uid
                assertEquals(null, connection.delete(uid))
            } finally {
                connection.close()
            }
            assertEquals(emptyList(), flags("INBOX"))
            assertEquals(listOf("Invoice"), flags("Trash").map { it.subject })
        }

    @Test
    fun movingToAFolderTakesTheMessageThere() =
        runTest {
            createFolder("Paperwork")
            deliver(subject = "Invoice", pdf = PDF)
            val connection = open(account.copy(postAction = MailPostAction.MOVE, moveToFolder = "Paperwork"))
            try {
                val uid = assertIs<MailFetchResult.Success>(connection.fetchAfter(0)).messages.single().uid
                assertEquals(null, connection.moveTo(uid, "Paperwork"))
            } finally {
                connection.close()
            }
            assertEquals(emptyList(), flags("INBOX"))
            assertEquals(listOf("Invoice"), flags("Paperwork").map { it.subject })
        }

    /** A post-action the server cannot carry out fails the open, before anything is imported. */
    @Test
    fun aMissingMoveTargetOrTrashFailsTheOpen() =
        runTest {
            val move =
                transport.open(
                    account.copy(postAction = MailPostAction.MOVE, moveToFolder = "Nowhere"),
                    PASSWORD,
                )
            assertEquals(MailFailureReason.MOVE_FOLDER_NOT_FOUND, assertIs<MailOpenResult.Failed>(move).reason)

            val delete = transport.open(account.copy(postAction = MailPostAction.DELETE), PASSWORD)
            assertEquals(MailFailureReason.TRASH_NOT_FOUND, assertIs<MailOpenResult.Failed>(delete).reason)

            assertEquals(
                MailFailureReason.TRASH_NOT_FOUND,
                transport.testConnection(account.copy(postAction = MailPostAction.DELETE), PASSWORD),
            )
        }

    @Test
    fun aWrongPasswordIsAnAuthenticationFailure() =
        runTest {
            assertEquals(MailFailureReason.AUTHENTICATION_FAILED, transport.testConnection(account, "wrong"))
            assertEquals(null, transport.testConnection(account, PASSWORD))
        }

    private suspend fun open(account: MailAccount): MailConnection =
        assertIs<MailOpenResult.Opened>(transport.open(account, PASSWORD)).connection

    private fun deliver(
        subject: String,
        pdf: ByteArray? = null,
    ) {
        val message =
            MimeMessage(Session.getInstance(Properties())).apply {
                setFrom(InternetAddress("sender@example.com"))
                setRecipients(Message.RecipientType.TO, ADDRESS)
                this.subject = subject
                val text = MimeBodyPart().apply { setText("Hello", "UTF-8") }
                setContent(
                    MimeMultipart().apply {
                        addBodyPart(text)
                        if (pdf != null) {
                            addBodyPart(
                                MimeBodyPart().apply {
                                    dataHandler = DataHandler(ByteArrayDataSource(pdf, "application/pdf"))
                                    fileName = "invoice.pdf"
                                    disposition = "attachment"
                                },
                            )
                        }
                    },
                )
                saveChanges()
            }
        greenMail.userManager.getUser(USER).deliver(message)
    }

    private fun <T> withStore(block: (Store) -> T): T {
        val store = Session.getInstance(Properties()).getStore("imap")
        store.connect("127.0.0.1", greenMail.imap.port, USER, PASSWORD)
        return try {
            block(store)
        } finally {
            store.close()
        }
    }

    private fun createFolder(name: String) = withStore { store -> store.getFolder(name).create(Folder.HOLDS_MESSAGES) }

    /** What [name] holds now, as subjects and flags, read without changing anything. */
    private fun flags(name: String): List<Seen> =
        withStore { store ->
            val folder = store.getFolder(name)
            folder.open(Folder.READ_ONLY)
            try {
                folder.messages.map { Seen(it.subject, it.flags) }
            } finally {
                folder.close(false)
            }
        }

    private class Seen(
        val subject: String,
        val flags: Flags,
    ) {
        fun isSet(flag: Flags.Flag): Boolean = flags.contains(flag)
    }

    private companion object {
        const val ADDRESS = "someone@example.com"
        const val USER = "someone"
        const val PASSWORD = "secret"
        val PDF = "%PDF-1.4 test".toByteArray()
    }
}
