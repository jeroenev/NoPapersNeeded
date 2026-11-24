package dev.npn.core.mail

import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val NOW = 1_000L * MailStartPoint.DAY_MILLIS

class MailServiceTest {
    private val account =
        MailAccount(
            host = "mail.example.com",
            username = "someone@example.com",
            enabled = true,
            postAction = MailPostAction.MARK_READ,
            // Most tests read the whole fake folder; the start-point tests set their own.
            startFrom = MailStartPoint.EVERYTHING,
        )

    private val pdf = MailAttachment("invoice.pdf", "application/pdf", byteArrayOf(1, 2, 3))

    private fun message(
        uid: Long = 1,
        attachments: List<MailAttachment> = listOf(pdf),
        from: String = "sender@example.com",
        subject: String = "Invoice",
        dateMillis: Long = 0,
    ) = MailMessage(uid, from, "me@example.com", subject, dateMillis, "body", attachments)

    /** A message per UID in [uids], each with its own attachment name, sent a day apart ending today. */
    private fun backlog(uids: LongRange): List<MailMessage> =
        uids.map { uid ->
            message(
                uid = uid,
                attachments = listOf(pdf.copy(fileName = "invoice-$uid.pdf")),
                dateMillis = NOW - (uids.last - uid) * MailStartPoint.DAY_MILLIS,
            )
        }

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
        var onImport: (String) -> MailImportResult = { MailImportResult.Imported("doc:$it") }

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

    /**
     * An in-memory folder. Each [open] snapshots UIDNEXT, as an IMAP SELECT does, and counts the
     * connection, so a test can tell one check made exactly one.
     */
    private class FakeTransport(
        var messages: List<MailMessage> = emptyList(),
        var validity: Long = 7,
        var fetchFailure: MailFailureReason? = null,
        var postFailure: MailFailureReason? = null,
        var testFailure: MailFailureReason? = null,
        var openFailure: MailFailureReason? = null,
        var searchFails: Boolean = false,
    ) : MailTransport {
        override val available: Boolean = true
        var opens = 0
        var closes = 0
        var fetches = 0
        val searchedSince = mutableListOf<Long>()
        val marked = mutableListOf<Long>()
        val moved = mutableListOf<Pair<Long, String>>()
        val deleted = mutableListOf<Long>()

        /** Calls made on a connection after it was closed; a correct check makes none. */
        var callsAfterClose = 0

        override suspend fun testConnection(
            account: MailAccount,
            password: String,
        ): MailFailureReason? = testFailure

        override suspend fun open(
            account: MailAccount,
            password: String,
        ): MailOpenResult {
            opens++
            openFailure?.let { return MailOpenResult.Failed(it) }
            return MailOpenResult.Opened(Connection(validity, (messages.maxOfOrNull(MailMessage::uid) ?: 0) + 1))
        }

        inner class Connection(
            override val uidValidity: Long,
            override val uidNext: Long,
        ) : MailConnection {
            private var closed = false

            private fun touch() {
                if (closed) callsAfterClose++
            }

            override suspend fun firstUidSince(sinceMillis: Long): Long? {
                touch()
                searchedSince += sinceMillis
                if (searchFails) return null
                return messages.filter { (it.dateMillis ?: 0) >= sinceMillis }.minOfOrNull(MailMessage::uid) ?: uidNext
            }

            override suspend fun countAfter(uid: Long): Int? {
                touch()
                return messages.count { it.uid in (uid + 1) until uidNext }
            }

            override suspend fun fetchAfter(afterUid: Long): MailFetchResult {
                touch()
                fetches++
                fetchFailure?.let { return MailFetchResult.Failed(it) }
                val start = afterUid + 1
                val windowEnd = minOf(uidNext - 1, start + MailTransport.SCAN_WINDOW - 1)
                val listed = messages.filter { it.uid in start..windowEnd }.sortedBy(MailMessage::uid)
                val taken = listed.take(MailTransport.PAGE_SIZE)
                return MailFetchResult.Success(
                    messages = taken,
                    uidNext = uidNext,
                    scannedThroughUid =
                        if (listed.size > MailTransport.PAGE_SIZE) taken.last().uid else maxOf(afterUid, windowEnd),
                )
            }

            override suspend fun markRead(uid: Long): MailFailureReason? {
                touch()
                marked += uid
                return postFailure
            }

            override suspend fun moveTo(
                uid: Long,
                folder: String,
            ): MailFailureReason? {
                touch()
                moved += uid to folder
                return postFailure
            }

            override suspend fun delete(uid: Long): MailFailureReason? {
                touch()
                deleted += uid
                return postFailure
            }

            override suspend fun close() {
                if (!closed) closes++
                closed = true
            }
        }
    }

    private class Fixture(
        account: MailAccount,
        transport: FakeTransport = FakeTransport(),
        credentials: FakeCredentials = FakeCredentials(),
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        var now = NOW
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
                clock = EpochClock { now },
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
    fun emptyUidWindowsAreCrossedWithinOneCheck() =
        runTest {
            // A mailbox whose early mail was deleted: two whole scan windows hold nothing.
            val messageUid = 2 * MailTransport.SCAN_WINDOW + 1
            val transport = FakeTransport(messages = listOf(message(uid = messageUid)))
            val fixture = fixture(transport = transport)
            fixture.service.checkNow()
            assertEquals(listOf(messageUid), transport.marked)
            assertEquals(messageUid, fixture.cursors.last().lastUid)
            assertEquals(3, transport.fetches)
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
            fixture.sink.onImport = { MailImportResult.Failed }
            val failed = fixture.service.checkNow()
            assertEquals(MailFailureReason.IMPORT_FAILED, failed.errors.single().reason)
            assertTrue(transport.marked.isEmpty())
            assertEquals(0, fixture.cursors.last().lastUid)
            assertEquals(MailFailureReason.IMPORT_FAILED, fixture.cursors.last().lastError)

            fixture.sink.onImport = { MailImportResult.Imported("doc:$it") }
            val retried = fixture.service.checkNow()
            assertTrue(retried.errors.isEmpty())
            assertEquals(1, retried.imported)
            assertEquals(listOf(1L), transport.marked)
            assertEquals(1, fixture.cursors.last().lastUid)
        }

    @Test
    fun anUnreadableMessageIsRetriedThenPassedOverWithoutStallingLaterMail() =
        runTest {
            // An attachment over the cap: the transport reads this one message as a failure.
            val unreadable =
                message(uid = 1, attachments = emptyList()).copy(readFailure = MailFailureReason.FETCH_FAILED)
            val transport = FakeTransport(messages = listOf(unreadable, message(uid = 2)))
            val fixture = fixture(transport = transport)
            // MailService.MAX_MESSAGE_ATTEMPTS: the fifth failed check passes the message over.
            repeat(4) {
                val report = fixture.service.checkNow()
                assertEquals(MailFailureReason.FETCH_FAILED, report.errors.single().reason)
                assertEquals(0, fixture.cursors.last().lastUid)
            }
            assertTrue(fixture.sink.importedNames.isEmpty())

            val passed = fixture.service.checkNow()
            assertEquals(1, passed.imported)
            assertEquals(listOf("invoice.pdf"), fixture.sink.importedNames)
            // The unreadable message keeps its place in the mailbox: no post-action ever touched it.
            assertEquals(listOf(2L), transport.marked)
            assertEquals(2, fixture.cursors.last().lastUid)
        }

    @Test
    fun aDuplicateAttachmentStillLetsThePostActionRun() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport = { MailImportResult.Duplicate }
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
            fixture.sink.onImport = { MailImportResult.Duplicate }
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
            assertEquals(0, transport.opens)
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
            assertEquals(0, transport.opens)
        }

    @Test
    fun anUnapprovedPlainConnectionIsRefusedBeforeAnyTransportCall() =
        runTest {
            val transport = FakeTransport(messages = listOf(message()))
            val plain = account.copy(security = MailSecurity.PLAIN, allowPlain = false)
            val fixture = fixture(account = plain, transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.PLAIN_REFUSED, report.errors.single().reason)
            assertEquals(0, transport.opens)
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

    @Test
    fun newMailOnlyStartsAtTheNextUidAndReadsNothingOlder() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..20L))
            val fixture = fixture(account = account.copy(startFrom = MailStartPoint.NEW_ONLY), transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(0, report.messagesSeen)
            assertEquals(20, fixture.cursors.last().lastUid)
            assertTrue(transport.searchedSince.isEmpty())

            transport.messages = backlog(1L..22L)
            val next = fixture.service.checkNow()
            assertEquals(listOf("invoice-21.pdf", "invoice-22.pdf"), fixture.sink.importedNames)
            assertEquals(2, next.imported)
        }

    @Test
    fun theLastThirtyDaysAreFoundBySearchAndReadFromThere() =
        runTest {
            // One message a day for 100 days: the last 30 days hold the newest 31 (day granularity).
            val transport = FakeTransport(messages = backlog(1L..100L))
            val fixture =
                fixture(account = account.copy(startFrom = MailStartPoint.LAST_30_DAYS), transport = transport)
            fixture.service.checkNow()
            assertEquals(listOf(NOW - 30 * MailStartPoint.DAY_MILLIS), transport.searchedSince)
            assertEquals((70L..100L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
            val cursor = fixture.cursors.last()
            assertEquals(MailStartPoint.LAST_30_DAYS, cursor.startFrom)
            assertEquals(false, cursor.catchingUp)
        }

    @Test
    fun aFirstPollCatchesUpThroughTheWholeBacklogOverOneConnection() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..120L))
            val fixture = fixture(transport = transport)
            fixture.service.pollIfDue()
            // Far more than a routine poll's five pages, in one check and one login.
            assertEquals(120, fixture.sink.importedNames.size)
            assertEquals((1L..120L).toList(), transport.marked)
            assertEquals(1, transport.opens)
            assertEquals(1, transport.closes)
            assertEquals(0, transport.callsAfterClose)
            val cursor = fixture.cursors.last()
            assertEquals(120, cursor.lastUid)
            assertEquals(false, cursor.catchingUp)
            assertEquals(0, cursor.remaining)
            assertEquals(NOW, cursor.throughMillis)
        }

    @Test
    fun routinePollsAfterACatchUpReadAFewPagesAtATime() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..5L))
            val fixture = fixture(transport = transport)
            fixture.service.pollIfDue()
            assertEquals(5, fixture.sink.importedNames.size)

            transport.messages = backlog(1L..85L)
            fixture.now += account.pollIntervalMinutes * 60_000L
            fixture.service.pollIfDue()
            // MAX_PAGES_PER_CHECK pages of PAGE_SIZE: the rest waits for the next poll.
            assertEquals(5 + 5 * MailTransport.PAGE_SIZE, fixture.sink.importedNames.size)
            assertEquals(2, transport.opens)

            fixture.now += account.pollIntervalMinutes * 60_000L
            fixture.service.pollIfDue()
            assertEquals(85, fixture.sink.importedNames.size)
            assertEquals((1L..85L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
        }

    @Test
    fun aCheckCancelledMidBacklogResumesWhereItStoppedWithoutSkipping() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..40L))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport = { name ->
                if (name == "invoice-15.pdf") fixture.service.cancelCheck()
                MailImportResult.Imported("doc:$name")
            }
            val cancelled = fixture.service.checkNow()
            assertTrue(cancelled.cancelled)
            assertTrue(cancelled.errors.isEmpty())
            assertEquals(15, cancelled.imported)
            val stopped = fixture.cursors.last()
            assertEquals(15, stopped.lastUid)
            assertEquals(true, stopped.catchingUp)
            assertEquals(1, transport.closes)

            // A routine poll after the cancel still reads every page: the catch-up is not over.
            fixture.now += account.pollIntervalMinutes * 60_000L
            fixture.service.pollIfDue()
            assertEquals((1L..40L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
            assertEquals((1L..40L).toList(), transport.marked)
            assertEquals(false, fixture.cursors.last().catchingUp)
        }

    @Test
    fun movingTheStartFurtherBackReadsTheOlderMailAgain() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..100L))
            val fixture =
                fixture(account = account.copy(startFrom = MailStartPoint.LAST_30_DAYS), transport = transport)
            fixture.service.checkNow()
            assertEquals(31, fixture.sink.importedNames.size)

            fixture.store.flow.value = fixture.store.current().copy(startFrom = MailStartPoint.EVERYTHING)
            fixture.sink.onImport = { name ->
                val older = name.removePrefix("invoice-").removeSuffix(".pdf").toLong() < 70
                if (older) MailImportResult.Imported("doc:$name") else MailImportResult.Duplicate
            }
            val report = fixture.service.checkNow()
            assertEquals(69, report.imported)
            assertEquals(31, report.duplicates)
            assertEquals(100, fixture.cursors.last().lastUid)
            assertEquals(MailStartPoint.EVERYTHING, fixture.cursors.last().startFrom)
        }

    @Test
    fun movingTheStartLaterSkipsOnlyMailTheCursorHasNotReached() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..100L))
            val fixture = fixture(transport = transport)
            fixture.sink.onImport = { name ->
                if (name == "invoice-10.pdf") fixture.service.cancelCheck()
                MailImportResult.Imported("doc:$name")
            }
            fixture.service.checkNow()
            assertEquals(10, fixture.cursors.last().lastUid)

            fixture.store.flow.value = fixture.store.current().copy(startFrom = MailStartPoint.LAST_30_DAYS)
            fixture.sink.onImport = { MailImportResult.Imported("doc:$it") }
            fixture.service.checkNow()
            assertEquals(
                (1L..10L).map { "invoice-$it.pdf" } + (70L..100L).map { "invoice-$it.pdf" },
                fixture.sink.importedNames,
            )
        }

    @Test
    fun aCursorFromBeforeStartPointsIsNeverMovedBack() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..100L))
            val fixture =
                fixture(account = account.copy(startFrom = MailStartPoint.LAST_30_DAYS), transport = transport)
            fixture.cursors.save(MailCursor(account.accountKey, account.folder, uidValidity = 7, lastUid = 80))
            fixture.service.checkNow()
            assertEquals((81L..100L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
            assertEquals(MailStartPoint.LAST_30_DAYS, fixture.cursors.last().startFrom)
        }

    @Test
    fun aRenumberedFolderIsReadFromADayBeforeTheLastCheck() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..10L))
            val fixture = fixture(account = account.copy(startFrom = MailStartPoint.NEW_ONLY), transport = transport)
            fixture.service.checkNow()
            assertTrue(fixture.sink.importedNames.isEmpty())

            // The server renumbers, and two messages arrived since the last check: "new mail only"
            // alone would start past them.
            fixture.now += MailStartPoint.DAY_MILLIS
            transport.validity = 8
            transport.messages =
                backlog(1L..12L).map { it.copy(dateMillis = it.dateMillis!! + MailStartPoint.DAY_MILLIS) }
            fixture.service.checkNow()
            assertEquals(listOf(NOW - MailStartPoint.DAY_MILLIS), transport.searchedSince)
            assertEquals((10L..12L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
            assertEquals(8, fixture.cursors.last().uidValidity)
        }

    @Test
    fun aStartTheServerCannotFindLeavesTheCursorUnplaced() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..10L), searchFails = true)
            val fixture = fixture(account = account.copy(startFrom = MailStartPoint.LAST_YEAR), transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.FETCH_FAILED, report.errors.single().reason)
            assertEquals(null, fixture.cursors.last().uidValidity)
            assertEquals(1, transport.closes)
            assertTrue(fixture.sink.importedNames.isEmpty())
        }

    @Test
    fun aConnectionThatCannotOpenIsTyped() =
        runTest {
            val transport =
                FakeTransport(messages = backlog(1L..3L), openFailure = MailFailureReason.AUTHENTICATION_FAILED)
            val fixture = fixture(transport = transport)
            val report = fixture.service.checkNow()
            assertEquals(MailFailureReason.AUTHENTICATION_FAILED, report.errors.single().reason)
            assertEquals(MailFailureReason.AUTHENTICATION_FAILED, fixture.cursors.last().lastError)
            runCurrent()
            assertEquals(MailFailureReason.AUTHENTICATION_FAILED, fixture.service.state.value.problem)
        }

    @Test
    fun aCheckThatImportedAnnouncesItsDocuments() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..3L))
            val fixture = fixture(transport = transport)
            val events = mutableListOf<List<String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                fixture.service.importedDocuments.collect { events += it }
            }
            val report = fixture.service.checkNow()
            assertEquals((1..3).map { "doc:invoice-$it.pdf" }, report.importedDocIds)
            assertEquals(listOf(report.importedDocIds), events)

            // A check that imported nothing says nothing.
            fixture.service.checkNow()
            assertEquals(1, events.size)
        }
}
