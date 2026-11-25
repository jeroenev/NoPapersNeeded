package dev.npn.core.mail

import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The fakes every MailService test shares: an account store, a keyring, device cursors, a library
 * sink, a PDF renderer and an in-memory IMAP folder, none of which touch a network or a disk.
 */

/** What a batch fetch knows of [this] before any download: its envelope and its parts' names. */
internal fun MailMessage.envelope() =
    MailEnvelope(
        uid = uid,
        from = from,
        to = to,
        subject = subject,
        dateMillis = dateMillis,
        attachments = attachments.map { MailAttachmentInfo(it.fileName, it.contentType) },
        readFailure = readFailure,
        readFailureCause = readFailureCause,
    )

internal const val NOW = 1_000L * MailStartPoint.DAY_MILLIS

internal val testAccount =
    MailAccount(
        host = "mail.example.com",
        username = "someone@example.com",
        postAction = MailPostAction.MARK_READ,
        // Most tests read the whole fake folder; the start-point tests set their own.
        startFrom = MailStartPoint.EVERYTHING,
    )

internal val testPdf = MailAttachment("invoice.pdf", "application/pdf", byteArrayOf(1, 2, 3))

internal fun testMessage(
    uid: Long = 1,
    attachments: List<MailAttachment> = listOf(testPdf),
    from: String = "sender@example.com",
    subject: String = "Invoice",
    dateMillis: Long = 0,
) = MailMessage(uid, from, "me@example.com", subject, dateMillis, "body", attachments)

/** A message per UID in [uids], each with its own attachment name, sent a day apart ending today. */
internal fun testBacklog(uids: LongRange): List<MailMessage> =
    uids.map { uid ->
        testMessage(
            uid = uid,
            attachments = listOf(testPdf.copy(fileName = "invoice-$uid.pdf")),
            dateMillis = NOW - (uids.last - uid) * MailStartPoint.DAY_MILLIS,
        )
    }

internal class FakeAccountStore(
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

/** A keyring of one password per account key; [password] is the one of [testAccount]'s key. */
internal class FakeCredentials(
    password: String? = "secret",
    var failure: MailSecretFailure? = null,
) : MailCredentialStore {
    val passwords = mutableMapOf<String, String>()

    init {
        if (password != null) passwords[testAccount.accountKey] = password
    }

    var password: String?
        get() = passwords[testAccount.accountKey]
        set(value) {
            if (value == null) passwords.remove(testAccount.accountKey) else passwords[testAccount.accountKey] = value
        }

    override val available: Boolean get() = failure != MailSecretFailure.UNSUPPORTED

    override suspend fun store(
        accountKey: String,
        password: String,
    ): MailSecretResult<Unit> {
        failure?.let { return MailSecretResult.Failed(it) }
        passwords[accountKey] = password
        return MailSecretResult.Success(Unit)
    }

    override suspend fun load(accountKey: String): MailSecretResult<String?> {
        failure?.let { return MailSecretResult.Failed(it) }
        return MailSecretResult.Success(passwords[accountKey])
    }

    override suspend fun clear(accountKey: String): MailSecretResult<Unit> {
        failure?.let { return MailSecretResult.Failed(it) }
        passwords.remove(accountKey)
        return MailSecretResult.Success(Unit)
    }
}

internal class FakeCursors(
    var enabled: Boolean = true,
) : MailCursorStore {
    val saved = mutableMapOf<Pair<String, String>, MailCursor>()
    var followed: String? = null

    override suspend fun enabledHere(): Boolean = enabled

    override suspend fun setEnabledHere(enabled: Boolean) {
        this.enabled = enabled
    }

    override suspend fun followedKey(): String? = followed

    override suspend fun setFollowedKey(accountKey: String) {
        followed = accountKey
    }

    override suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursor? = saved[accountKey to folder]

    override suspend fun save(cursor: MailCursor) {
        saved[cursor.accountKey to cursor.folder] = cursor
    }

    fun last(): MailCursor = saved.values.single()

    fun of(account: MailAccount): MailCursor? = saved[account.accountKey to account.folder]
}

internal class FakeSink : MailDocumentSink {
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

internal class FakeRenderer(
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
internal class FakeTransport(
    var messages: List<MailMessage> = emptyList(),
    var validity: Long = 7,
    var fetchFailure: MailFailureReason? = null,
    var postFailure: MailFailureReason? = null,
    var testFailure: MailFailureReason? = null,
    var openFailure: MailFailureReason? = null,
    var searchFails: Boolean = false,
    /** Fetches that fail as a dropped connection would, before fetches work again. */
    var transientFetchFailures: Int = 0,
    /** Which fetches (1-based, over the transport's lifetime) fail as a dropped connection would. */
    var failingFetches: Set<Int> = emptySet(),
    /** Which downloads (1-based, over the transport's lifetime) fail as a dropped connection would. */
    var failingDownloads: Set<Int> = emptySet(),
    /** UIDs whose download the server refuses (an IMAP NO), every time until removed. */
    var refusedUids: Set<Long> = emptySet(),
    /** When the server received a message, where it differs from when it was sent. */
    var receivedMillis: Map<Long, Long> = emptyMap(),
) : MailTransport {
    private fun MailMessage.described(): MailEnvelope =
        envelope().let { envelope -> receivedMillis[uid]?.let { envelope.copy(receivedMillis = it) } ?: envelope }

    /** Every download asked for, as (UID, with its text), in order: what a check cost in bodies. */
    val downloads = mutableListOf<Pair<Long, Boolean>>()
    private var downloadCalls = 0
    override val available: Boolean = true
    var opens = 0
    var closes = 0
    var fetches = 0
    val searchedSince = mutableListOf<Long>()
    val marked = mutableListOf<Long>()
    val moved = mutableListOf<Pair<Long, String>>()
    val deleted = mutableListOf<Long>()
    val described = mutableListOf<List<Long>>()

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
            if (transientFetchFailures > 0 || fetches in failingFetches) {
                if (transientFetchFailures > 0) transientFetchFailures--
                return MailFetchResult.Failed(MailFailureReason.FETCH_FAILED, "FolderClosedException")
            }
            val start = afterUid + 1
            val windowEnd = minOf(uidNext - 1, start + MailTransport.SCAN_WINDOW - 1)
            val listed = messages.filter { it.uid in start..windowEnd }.sortedBy(MailMessage::uid)
            val taken = listed.take(MailTransport.PAGE_SIZE)
            return MailFetchResult.Success(
                messages = taken.map { it.described() },
                uidNext = uidNext,
                scannedThroughUid =
                    if (listed.size > MailTransport.PAGE_SIZE) taken.last().uid else maxOf(afterUid, windowEnd),
            )
        }

        override suspend fun describe(uids: List<Long>): MailFetchResult {
            touch()
            described += uids
            fetchFailure?.let { return MailFetchResult.Failed(it) }
            val held = messages.filter { it.uid in uids }.sortedBy(MailMessage::uid)
            return MailFetchResult.Success(held.map { it.described() }, uidNext, uids.maxOrNull() ?: 0)
        }

        override suspend fun download(
            uid: Long,
            withBody: Boolean,
        ): MailDownloadResult {
            touch()
            downloadCalls++
            if (downloadCalls in failingDownloads) {
                return MailDownloadResult.Failed(MailFailureReason.FETCH_FAILED, "FolderClosedException")
            }
            if (uid in
                refusedUids
            ) {
                return MailDownloadResult.Refused(MailFailureReason.FETCH_FAILED, "CommandFailedException")
            }
            downloads += uid to withBody
            val message = messages.single { it.uid == uid }
            return MailDownloadResult.Success(if (withBody) message else message.copy(bodyText = ""))
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

internal class MailFixture(
    account: MailAccount,
    transport: FakeTransport = FakeTransport(),
    credentials: FakeCredentials = FakeCredentials(),
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var now = NOW
    val store = FakeAccountStore(account)
    val cursors = FakeCursors(enabled = true)
    val sink = FakeSink()
    val renderer = FakeRenderer()
    val transport = transport
    val credentials = credentials
    private val scope = scope
    val service = newService()

    /** The service as a restarted app makes it: nothing in memory, the same device storage. */
    fun newService() =
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

/** A service over [transport] and fresh fakes, run on the test's background scope. */
internal fun kotlinx.coroutines.test.TestScope.mailFixture(
    account: MailAccount = testAccount,
    transport: FakeTransport = FakeTransport(),
    credentials: FakeCredentials = FakeCredentials(),
) = MailFixture(account, transport, credentials, backgroundScope)
