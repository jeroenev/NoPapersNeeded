package dev.npn.core.mail

import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MailServiceTest {
    private val account =
        MailAccount(
            host = "mail.example.com",
            username = "someone@example.com",
            enabled = true,
            postAction = MailPostAction.MARK_READ,
        )

    private val pdf = MailAttachment("invoice.pdf", "application/pdf", byteArrayOf(1, 2, 3))

    private fun message(
        uid: Long = 1,
        attachments: List<MailAttachment> = listOf(pdf),
        from: String = "sender@example.com",
        subject: String = "Invoice",
    ) = MailMessage(uid, from, "me@example.com", subject, 0, "body", attachments)

    private class FakeAccountStore(
        initial: MailAccount,
    ) : MailAccountStore {
        val flow = MutableStateFlow(initial)
        var failSave = false
        override val account: Flow<MailAccount> = flow

        override fun current(): MailAccount = flow.value

        override suspend fun save(account: MailAccount) {
            check(!failSave)
            flow.value = account
        }
    }

    private class FakeCredentials(
        var password: String? = "secret",
        var failure: MailSecretFailure? = null,
    ) : MailCredentialStore {
        override val available: Boolean get() = failure != MailSecretFailure.UNSUPPORTED

        override suspend fun store(
            accountKey: String,
            password: String,
        ): MailSecretResult<Unit> {
            failure?.let { return MailSecretResult.Failed(it) }
            this.password = password
            return MailSecretResult.Success(Unit)
        }

        override suspend fun load(accountKey: String): MailSecretResult<String?> {
            failure?.let { return MailSecretResult.Failed(it) }
            return MailSecretResult.Success(password)
        }

        override suspend fun clear(accountKey: String): MailSecretResult<Unit> {
            failure?.let { return MailSecretResult.Failed(it) }
            password = null
            return MailSecretResult.Success(Unit)
        }
    }

    private class FakeCursors : MailCursorStore {
        val saved = mutableMapOf<Pair<String, String>, MailCursor>()

        override suspend fun get(
            accountKey: String,
            folder: String,
        ): MailCursor? = saved[accountKey to folder]

        override suspend fun save(cursor: MailCursor) {
            saved[cursor.accountKey to cursor.folder] = cursor
        }

        fun last(): MailCursor = saved.values.single()
    }

    private class FakeSink : MailDocumentSink {
        val importedNames = mutableListOf<String>()
        var onImport: (String) -> MailImportResult = { MailImportResult.IMPORTED }

        override suspend fun importPdf(
            bytes: ByteArray,
            suggestedName: String,
        ): MailImportResult = record(suggestedName)

        override suspend fun importImage(
            bytes: ByteArray,
            suggestedName: String,
        ): MailImportResult = record(suggestedName)

        private fun record(name: String): MailImportResult {
            importedNames += name
            return onImport(name)
        }
    }

    private class FakeRenderer(
        var bytes: ByteArray? = byteArrayOf(9, 9, 9),
    ) : MailPdfRenderer {
        override val available: Boolean = true
        val rendered = mutableListOf<MailMessage>()

        override suspend fun render(message: MailMessage): ByteArray? {
            rendered += message
            return bytes
        }
    }

    private class FakeTransport(
        var messages: List<MailMessage> = emptyList(),
        var validity: Long = 7,
        var fetchFailure: MailFailureReason? = null,
        var postFailure: MailFailureReason? = null,
        var testFailure: MailFailureReason? = null,
    ) : MailTransport {
        override val available: Boolean = true
        var fetches = 0
        val marked = mutableListOf<Long>()
        val moved = mutableListOf<Pair<Long, String>>()
        val deleted = mutableListOf<Long>()

        override suspend fun testConnection(
            account: MailAccount,
            password: String,
        ): MailFailureReason? = testFailure

        override suspend fun fetchMessages(
            account: MailAccount,
            password: String,
            knownUidValidity: Long?,
            afterUid: Long,
        ): MailFetchResult {
            fetches++
            fetchFailure?.let { return MailFetchResult.Failed(it) }
            val start = if (knownUidValidity == validity) afterUid + 1 else 1
            val maxUid = messages.maxOfOrNull(MailMessage::uid) ?: 0
            val uidNext = maxUid + 1
            return MailFetchResult.Success(
                uidValidity = validity,
                messages =
                    messages
                        .filter { it.uid in start..minOf(uidNext - 1, start + MailTransport.PAGE_SIZE - 1) }
                        .sortedBy(MailMessage::uid)
                        .take(MailTransport.PAGE_SIZE),
                uidNext = uidNext,
                scannedThroughUid = maxOf(start - 1, minOf(uidNext - 1, start + MailTransport.PAGE_SIZE - 1)),
            )
        }

        override suspend fun markRead(
            account: MailAccount,
            password: String,
            uid: Long,
        ): MailFailureReason? {
            marked += uid
            return postFailure
        }

        override suspend fun moveTo(
            account: MailAccount,
            password: String,
            uid: Long,
            folder: String,
        ): MailFailureReason? {
            moved += uid to folder
            return postFailure
        }

        override suspend fun delete(
            account: MailAccount,
            password: String,
            uid: Long,
        ): MailFailureReason? {
            deleted += uid
            return postFailure
        }
    }

    private class Fixture(
        account: MailAccount,
        transport: FakeTransport = FakeTransport(),
        credentials: FakeCredentials = FakeCredentials(),
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val store = FakeAccountStore(account)
        val cursors = FakeCursors()
        val sink = FakeSink()
        val renderer = FakeRenderer()
        val transport = transport
        val credentials = credentials
        val service =
            MailService(
                store = store,
                credentials = credentials,
                cursors = cursors,
                transport = transport,
                sink = sink,
                pdfRenderer = renderer,
                clock = EpochClock { 1_000 },
                log = NpnLog.None,
                scope = scope,
            )
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        account: MailAccount = this@MailServiceTest.account,
        transport: FakeTransport = FakeTransport(),
        credentials: FakeCredentials = FakeCredentials(),
    ) = Fixture(account.copy(enabled = true), transport, credentials, backgroundScope)

    @Test
    fun failedSettingsWriteLeavesThePreviousPassword() =
        runTest {
            val fixture = fixture()
            fixture.store.failSave = true
            kotlin.test.assertFailsWith<IllegalStateException> {
                fixture.service.saveAccount(account.copy(host = "other.example.com"), "new secret")
            }
            assertEquals("secret", fixture.credentials.password)
            assertEquals(account, fixture.store.current())
        }

    @Test
    fun emptyUidWindowsEventuallyReachSparseMessages() =
        runTest {
            val pageSize = MailTransport.PAGE_SIZE.toLong()
            val messageUid = 2 * pageSize + 1
            val transport = FakeTransport(messages = listOf(message(uid = messageUid)))
            val fixture = fixture(transport = transport)
            fixture.service.checkNow()
            assertEquals(pageSize, fixture.cursors.last().lastUid)
            fixture.service.checkNow()
            assertEquals(2 * pageSize, fixture.cursors.last().lastUid)
            fixture.service.checkNow()
            assertEquals(listOf(messageUid), transport.marked)
            assertEquals(messageUid, fixture.cursors.last().lastUid)
        }

    @Test
    fun aPdfIsImportedOnceAndTheCursorMovesPastIt() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture = fixture(transport = transport)
            val first = fixture.service.checkNow()
            assertEquals(1, first.messagesSeen)
            assertEquals(1, first.matched)
            assertEquals(1, first.imported)
            assertEquals(0, first.skipped)
            assertTrue(first.errors.isEmpty())
            assertEquals(listOf("invoice.pdf"), fixture.sink.importedNames)
            assertEquals(listOf(1L), transport.marked)
            assertEquals(1, fixture.cursors.last().lastUid)
            assertEquals(7, fixture.cursors.last().uidValidity)
            assertEquals(null, fixture.cursors.last().lastError)

            val second = fixture.service.checkNow()
            assertEquals(0, second.messagesSeen)
            assertEquals(1, fixture.sink.importedNames.size)
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun anImportFailureSkipsThePostActionAndRetriesTheSameMessage() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport = { MailImportResult.FAILED }
            val failed = fixture.service.checkNow()
            assertEquals(MailFailureReason.IMPORT_FAILED, failed.errors.single().reason)
            assertTrue(transport.marked.isEmpty())
            assertEquals(0, fixture.cursors.last().lastUid)
            assertEquals(MailFailureReason.IMPORT_FAILED, fixture.cursors.last().lastError)

            fixture.sink.onImport = { MailImportResult.IMPORTED }
            val retried = fixture.service.checkNow()
            assertTrue(retried.errors.isEmpty())
            assertEquals(1, retried.imported)
            assertEquals(listOf(1L), transport.marked)
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun aDuplicateAttachmentStillLetsThePostActionRun() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport = { MailImportResult.DUPLICATE }
            val report = fixture.service.checkNow()
            assertEquals(1, report.duplicates)
            assertEquals(0, report.imported)
            assertEquals(listOf(1L), transport.marked)
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun aPostActionFailureKeepsTheMessageForTheNextCheck() =
        runTest {
            val transport =
                FakeTransport(messages = listOf(message()), postFailure = MailFailureReason.POST_ACTION_FAILED)
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(1, report.imported)
            assertEquals(MailFailureReason.POST_ACTION_FAILED, report.errors.single().reason)
            assertEquals(0, fixture.cursors.last().lastUid)
            assertEquals(listOf(1L), transport.marked)

            transport.postFailure = null
            fixture.sink.onImport = { MailImportResult.DUPLICATE }
            val retried = fixture.service.checkNow()
            assertTrue(retried.errors.isEmpty())
            assertEquals(1, retried.duplicates)
            assertEquals(listOf(1L, 1L), transport.marked)
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun aMessageWithoutASupportedAttachmentIsSkippedAndPassedOver() =
        runTest {
            val docx =
                MailAttachment(
                    "letter.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    byteArrayOf(1),
                )
            val transport = FakeTransport(messages = listOf(message(attachments = listOf(docx))))
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(1, report.skipped)
            assertEquals(0, report.imported)
            assertTrue(transport.marked.isEmpty())
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun aFilteredOutMessageIsSkippedAndPassedOver() =
        runTest {
            val filtered = account.copy(filters = listOf(MailFilter(subject = "statement")))
            val transport = FakeTransport(messages = listOf(message(subject = "Invoice")))
            val fixture = fixture(account = filtered, transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(1, report.skipped)
            assertEquals(0, report.matched)
            assertTrue(transport.marked.isEmpty())
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun theEmailPdfIsImportedWithItsStableNameAndThePostActionRuns() =
        runTest {
            val plain = message(attachments = emptyList())
            val account =
                account.copy(
                    saveEmailAsPdf = true,
                    postAction = MailPostAction.MOVE,
                    moveToFolder = "Processed",
                )
            val transport = FakeTransport(messages = listOf(plain))
            val fixture = fixture(account = account, transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(1, report.imported)
            assertEquals(listOf(emailPdfFileName(plain)), fixture.sink.importedNames)
            assertEquals(listOf(1L to "Processed"), transport.moved)
            assertEquals(1, fixture.renderer.rendered.size)
        }

    @Test
    fun aMissingPasswordIsTypedAndNeverReachesTheTransport() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture = fixture(transport = transport, credentials = FakeCredentials(password = null))
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.NO_PASSWORD, report.errors.single().reason)
            assertEquals(0, transport.fetches)
            assertEquals(MailFailureReason.NO_PASSWORD, fixture.cursors.last().lastError)
        }

    @Test
    fun aSecretStoreFailureIsTypedAndNeverReachesTheTransport() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture =
                fixture(
                    transport = transport,
                    credentials = FakeCredentials(failure = MailSecretFailure.UNAVAILABLE),
                )
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.SECRET_UNAVAILABLE, report.errors.single().reason)
            assertEquals(0, transport.fetches)
        }

    @Test
    fun anUnapprovedPlainConnectionIsRefusedBeforeAnyTransportCall() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val plain = account.copy(security = MailSecurity.PLAIN, allowPlain = false)
            val fixture = fixture(account = plain, transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.PLAIN_REFUSED, report.errors.single().reason)
            assertEquals(0, transport.fetches)
        }

    @Test
    fun aNewUidValidityReadsTheFolderFromTheStartAgain() =
        runTest {
            val transport = FakeTransport(messages = listOf(message(uid = 5)))
            val fixture = fixture(transport = transport)
            fixture.service.checkNow()
            assertEquals(5, fixture.cursors.last().lastUid)

            transport.validity = 8
            transport.messages = listOf(message(uid = 1, subject = "A new mailbox"))
            val report = fixture.service.checkNow()
            assertEquals(1, report.imported)
            assertEquals(1, fixture.cursors.last().lastUid)
            assertEquals(8, fixture.cursors.last().uidValidity)
        }

    @Test
    fun savingWithAnUnavailableSecretStoreKeepsTheAccountButReportsTheKeyring() =
        runTest {
            val fixture = fixture(credentials = FakeCredentials(failure = MailSecretFailure.UNAVAILABLE))
            val outcome = fixture.service.saveAccount(account.copy(host = "other.example.com"), "secret")
            assertIs<MailSaveOutcome.CredentialFailed>(outcome)
            // The account is saved; only the password did not land, so the user can fix the keyring
            // and retry without retyping the whole account.
            assertEquals("other.example.com", fixture.store.current().host)
        }

    @Test
    fun savingWithoutAPasswordLeavesTheStoredOneAlone() =
        runTest {
            val fixture = fixture()
            fixture.service.saveAccount(account.copy(pollIntervalMinutes = 30), null)
            assertEquals(30, fixture.store.current().pollIntervalMinutes)
            assertEquals("secret", fixture.credentials.password)
        }
}
