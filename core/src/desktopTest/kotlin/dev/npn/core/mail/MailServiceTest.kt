package dev.npn.core.mail

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The service end to end over the fakes: accounts, start points, catch-up, filters and post-actions. */
class MailServiceTest {
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
    fun failedSettingsWriteLeavesThePreviousPassword() =
        runTest {
            val fixture = fixture()
            fixture.store.failSave = true
            kotlin.test.assertFailsWith<IllegalStateException> {
                fixture.service.saveAccount(account.copy(host = "other.example.com"), "new secret", enabledHere = true)
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
            val outcome =
                fixture.service.saveAccount(
                    account.copy(host = "other.example.com"),
                    "secret",
                    enabledHere = true,
                )
            assertIs<MailSaveOutcome.CredentialFailed>(outcome)
            // The account is saved; only the password did not land, so the user can fix the keyring
            // and retry without retyping the whole account.
            assertEquals("other.example.com", fixture.store.current().host)
        }

    @Test
    fun savingWithoutAPasswordLeavesTheStoredOneAlone() =
        runTest {
            val fixture = fixture()
            fixture.service.saveAccount(account.copy(pollIntervalMinutes = 30), null, enabledHere = true)
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

            transport.messages = backlog(1L..150L)
            fixture.now += account.pollIntervalMinutes * 60_000L
            fixture.service.pollIfDue()
            // MAX_PAGES_PER_CHECK (one) page of PAGE_SIZE: the rest waits for the next poll.
            assertEquals(5 + MailTransport.PAGE_SIZE, fixture.sink.importedNames.size)
            assertEquals(2, transport.opens)

            fixture.now += account.pollIntervalMinutes * 60_000L
            fixture.service.pollIfDue()
            assertEquals(150, fixture.sink.importedNames.size)
            assertEquals((1L..150L).map { "invoice-$it.pdf" }, fixture.sink.importedNames)
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

    @Test
    fun anotherAccountDoesNotShowThePreviousAccountsLastCheck() =
        runTest {
            val transport = FakeTransport(messages = backlog(1L..2L))
            val fixture = fixture(transport = transport)
            fixture.service.checkNow()
            runCurrent()
            assertEquals(
                2,
                fixture.service.state.value.lastReport
                    ?.imported,
            )

            fixture.service.saveAccount(
                fixture.store.current().copy(host = "other.example.com"),
                null,
                enabledHere = true,
            )
            runCurrent()
            assertEquals(null, fixture.service.state.value.lastReport)
            assertEquals(null, fixture.service.state.value.lastCheckedAtMillis)
        }
}
