package dev.npn.core.mail

import dev.npn.core.app.MailFacade
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One failure during a check, with the server's own text kept only as an optional detail. */
data class MailCheckError(
    val reason: MailFailureReason,
    val detail: String? = null,
)

/**
 * What one check did. Counts are for messages this device considered; [imported] and [duplicates]
 * count documents (attachments and the optional email PDF together), and [importedDocIds] names
 * the imported ones. [errors] carries the first reason a message or the connection stopped the
 * check; [cancelled] is a check the user stopped, which kept everything it had done. [unreadable]
 * counts emails passed over and left in the mailbox (unreadable, or still failing after
 * [MailService]'s bounded retries), [droppedAttachments] attachments left out of an email that was
 * otherwise imported. Both are this check's; what is still left behind over every check is
 * [MailState.passedOver].
 */
data class MailCheckReport(
    val messagesSeen: Int = 0,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val errors: List<MailCheckError> = emptyList(),
    val checkedAtMillis: Long = 0,
    val importedDocIds: List<String> = emptyList(),
    val cancelled: Boolean = false,
    val unreadable: Int = 0,
    val droppedAttachments: Int = 0,
) {
    val succeeded: Boolean get() = errors.isEmpty()
}

/**
 * How far the running check has come: messages looked at and documents imported so far, how many
 * are left after the cursor (null when the server could not say), and when the last message the
 * cursor passed was sent.
 */
data class MailProgress(
    val messagesSeen: Int = 0,
    val imported: Int = 0,
    val remaining: Int? = null,
    val throughMillis: Long? = null,
)

/** What the settings pane shows for the one account, and what the last check came to. */
data class MailState(
    val account: MailAccount = MailAccount(),
    /** Whether this device checks the account; each device of the library chooses for itself. */
    val enabledHere: Boolean = false,
    val hasPassword: Boolean = false,
    val checking: Boolean = false,
    val lastCheckedAtMillis: Long? = null,
    val lastError: MailFailureReason? = null,
    val lastReport: MailCheckReport? = null,
    /** Whether this device can import email at all: an IMAP transport and a keyring to hold the password. */
    val transportAvailable: Boolean = true,
    val pdfAvailable: Boolean = true,
    /** Set when a password was asked for but the OS secret store could not be used. */
    val credentialProblem: MailSecretFailure? = null,
    /** The running check's progress; null while none runs. */
    val progress: MailProgress? = null,
    /** A Cancel was asked for and the running check stops after the message in hand. */
    val cancelling: Boolean = false,
    /**
     * Older mail is still being worked through: from a first read or a start moved back until a
     * check reaches the end of the folder. Survives a cancel, a failure and a restart.
     */
    val catchingUp: Boolean = false,
    /** When the last message the cursor passed was sent, kept between checks. */
    val throughMillis: Long? = null,
    /** How many messages were left after the cursor when it was last saved, when known. */
    val remaining: Int? = null,
    /**
     * Emails behind the cursor left in the mailbox without being fully imported, over every check
     * so far ([MailCursor.passedOver]); [MailFacade.retryPassedOver] tries them again.
     */
    val passedOver: Int = 0,
) {
    /**
     * Why checks of a configured account are not going through, for Activity's problems: the last
     * check's failure, or a secret store that could not be read. Null while a check runs.
     */
    val problem: MailFailureReason?
        get() =
            when {
                checking -> null
                lastError != null -> lastError
                credentialProblem != null -> MailFailureReason.SECRET_UNAVAILABLE
                else -> null
            }
}

/** What saving an account came to. A journal refusal is the session's own failure and throws. */
sealed interface MailSaveOutcome {
    data object Saved : MailSaveOutcome

    /** A required field is missing, or the choices are inconsistent (a plain connection not allowed). */
    data object AccountInvalid : MailSaveOutcome

    /** The password could not be put in, or removed from, the OS secret store. */
    data class CredentialFailed(
        val reason: MailSecretFailure,
    ) : MailSaveOutcome
}

/** What a connection test came to. */
sealed interface MailTestOutcome {
    data object Ok : MailTestOutcome

    data class Failed(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailTestOutcome
}

/** The account store the service reads and writes: the library's journal setting. */
interface MailAccountStore {
    val account: Flow<MailAccount>

    fun current(): MailAccount

    suspend fun save(account: MailAccount)
}

/**
 * Imports attachments (and optionally the email itself) from one IMAP account. The transport and
 * the secret store are seams: a test replaces both and never touches the network or a keyring.
 *
 * The check is single-flight and holds one connection from its first page to its last post-action.
 * A message is fully handled only when every supported attachment is imported or already present
 * and its post-action succeeded; otherwise its cursor is not advanced, so the next check tries it
 * again, while earlier messages are not fetched twice. The cursor is saved after every page, so a
 * cancel, a closed library or a crash loses at most the page in hand, and that page is read again.
 *
 * Where a device starts reading is the account's [MailStartPoint]. Placing the cursor there starts
 * a catch-up, which reads page after page until the folder is exhausted; a routine poll otherwise
 * reads at most [MAX_PAGES_PER_CHECK] pages and leaves the rest for the next one.
 */
internal class MailService(
    private val store: MailAccountStore,
    private val credentials: MailCredentialStore,
    private val cursors: MailCursorStore,
    private val transport: MailTransport,
    sink: MailDocumentSink,
    pdfRenderer: MailPdfRenderer,
    private val clock: EpochClock,
    private val log: NpnLog,
    private val scope: CoroutineScope,
) : MailFacade {
    private data class Runtime(
        val enabledHere: Boolean = false,
        val hasPassword: Boolean = false,
        val checking: Boolean = false,
        val cursor: MailCursor? = null,
        val lastReport: MailCheckReport? = null,
        val credentialProblem: MailSecretFailure? = null,
        val progress: MailProgress? = null,
        val cancelRequested: Boolean = false,
    )

    private val importer = MailMessageImporter(sink, pdfRenderer, log)
    private val runtime = MutableStateFlow(Runtime())
    private val checkMutex = Mutex()
    private val imports = MutableSharedFlow<List<String>>(extraBufferCapacity = IMPORT_EVENT_BUFFER)

    private var started = false

    /** Whether this device can import at all: IMAP, and a keyring for the password. */
    private val supported: Boolean get() = transport.available && credentials.available

    override val state: StateFlow<MailState> =
        combine(store.account, runtime) { account, run ->
            MailState(
                account = account,
                enabledHere = run.enabledHere,
                hasPassword = run.hasPassword,
                checking = run.checking,
                lastCheckedAtMillis = run.cursor?.lastCheckedAtMillis,
                lastError = run.cursor?.lastError,
                lastReport = run.lastReport,
                transportAvailable = supported,
                pdfAvailable = pdfRenderer.available,
                credentialProblem = run.credentialProblem,
                progress = run.progress,
                cancelling = run.checking && run.cancelRequested,
                catchingUp = run.cursor?.catchingUp == true,
                throughMillis = run.cursor?.throughMillis,
                remaining = run.cursor?.remaining,
                passedOver = run.cursor?.passedOver?.size ?: 0,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            MailState(transportAvailable = supported, pdfAvailable = pdfRenderer.available),
        )

    override val importedDocuments: SharedFlow<List<String>> = imports.asSharedFlow()

    /**
     * Follow the account and poll while the app is open. Idempotent; the loop is a child of the
     * caller's scope, so closing the library ends it and no work runs once the app is closed.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            log.runLogged("Following the email account") {
                val enabled = cursors.enabledHere()
                runtime.update { it.copy(enabledHere = enabled) }
                store.account.collect { account ->
                    refreshCredentials(account)
                    reloadCursor(account)
                }
            }
        }
        scope.launch {
            while (isActive) {
                log.runLogged("Checking email") { pollIfDue() }
                delay(POLL_TICK_MILLIS)
            }
        }
    }

    override suspend fun saveAccount(
        account: MailAccount,
        password: String?,
        enabledHere: Boolean,
    ): MailSaveOutcome {
        val normalized = account.normalized()
        if (!normalized.isConfigured) return MailSaveOutcome.AccountInvalid
        val oldKey = store.current().accountKey
        // Save the account first: a keyring failure then leaves the account visible and fixable,
        // rather than a password stored for a host the journal never accepted.
        store.save(normalized)
        cursors.setEnabledHere(enabledHere)
        runtime.update { it.copy(enabledHere = enabledHere) }
        if (password != null) {
            val result =
                if (password.isEmpty()) {
                    credentials.clear(normalized.accountKey)
                } else {
                    credentials.store(normalized.accountKey, password)
                }
            if (result is MailSecretResult.Failed) return MailSaveOutcome.CredentialFailed(result.reason)
        }
        // A changed host or username makes a new key; forget the old password rather than leave it
        // in the keyring under an account nothing reads.
        if (oldKey.isNotBlank() && oldKey != normalized.accountKey) clearQuietly(oldKey)
        refreshCredentials(normalized)
        reloadCursor(normalized)
        return MailSaveOutcome.Saved
    }

    override suspend fun clearPassword(): MailSaveOutcome {
        val account = store.current()
        return when (val result = credentials.clear(account.accountKey)) {
            is MailSecretResult.Success -> {
                runtime.update { it.copy(hasPassword = false, credentialProblem = null) }
                MailSaveOutcome.Saved
            }

            is MailSecretResult.Failed -> {
                runtime.update { it.copy(credentialProblem = result.reason) }
                MailSaveOutcome.CredentialFailed(result.reason)
            }
        }
    }

    override suspend fun testConnection(
        account: MailAccount,
        password: String?,
    ): MailTestOutcome {
        val normalized = account.normalized()
        if (normalized.security == MailSecurity.PLAIN && !normalized.allowPlain) {
            return MailTestOutcome.Failed(MailFailureReason.PLAIN_REFUSED)
        }
        if (!normalized.isConfigured) return MailTestOutcome.Failed(MailFailureReason.NOT_CONFIGURED)
        if (!supported) return MailTestOutcome.Failed(MailFailureReason.UNSUPPORTED)
        val secret =
            password?.takeIf(String::isNotEmpty) ?: storedPassword(normalized)
                ?: return MailTestOutcome.Failed(MailFailureReason.NO_PASSWORD)
        return when (val reason = transport.testConnection(normalized, secret)) {
            null -> MailTestOutcome.Ok
            else -> MailTestOutcome.Failed(reason)
        }
    }

    /**
     * Check now, whatever the interval and enabled switch say; a user asking is enough. The check
     * runs in the service's own scope, so a caller that stops waiting (a screen left mid catch-up)
     * does not stop it; [cancelCheck] does.
     */
    override suspend fun checkNow(): MailCheckReport = checkInScope(retry = false)

    /**
     * Check now, first reading again every email passed over so far ([MailState.passedOver]): one
     * that imports now leaves the list, one that still cannot stays on it.
     */
    override suspend fun retryPassedOver(): MailCheckReport = checkInScope(retry = true)

    private suspend fun checkInScope(retry: Boolean): MailCheckReport {
        val account = store.current()
        // Caught inside, so a check that throws reaches this caller rather than the session's scope.
        return scope
            .async { runCatchingCancellable { check(account, routine = false, retry = retry) } }
            .await()
            .getOrThrow()
    }

    override fun cancelCheck() {
        runtime.update { if (it.checking) it.copy(cancelRequested = true) else it }
    }

    /** One tick of the poller: check when the account is enabled and its interval has passed. */
    internal suspend fun pollIfDue() {
        if (!supported) return
        val account = store.current()
        if (!account.isConfigured || !cursors.enabledHere()) return
        val cursor = cursors.get(account.accountKey, account.folder)
        val last = cursor?.lastCheckedAtMillis
        val intervalMillis = account.pollIntervalMinutes.coerceAtLeast(MailAccount.MIN_POLL_MINUTES) * 60_000L
        if (last != null && clock.nowMillis() - last < intervalMillis) return
        check(account, routine = true)
    }

    private suspend fun check(
        account: MailAccount,
        routine: Boolean,
        retry: Boolean = false,
    ): MailCheckReport =
        checkMutex.withLock {
            runtime.update { it.copy(checking = true, cancelRequested = false, progress = MailProgress()) }
            try {
                checkLocked(account, routine, retry)
            } finally {
                runtime.update { it.copy(checking = false, cancelRequested = false, progress = null) }
            }
        }

    private suspend fun checkLocked(
        account: MailAccount,
        routine: Boolean,
        retry: Boolean,
    ): MailCheckReport {
        val now = clock.nowMillis()
        val empty = MailCheckReport(checkedAtMillis = now)
        val password =
            when (val loaded = preflight(account)?.let(PasswordLoad::Refused) ?: loadPassword(account)) {
                is PasswordLoad.Refused -> return stop(account, empty, MailCheckError(loaded.reason), now)
                is PasswordLoad.Loaded -> loaded.password
            }
        val link = Link(account, password)
        return try {
            when (val opened = link.open()) {
                is MailOpenResult.Failed -> stop(account, empty, MailCheckError(opened.reason, opened.detail), now)
                is MailOpenResult.Opened -> read(account, link, routine, retry, now)
            }
        } finally {
            withContext(NonCancellable) { link.close() }
        }
    }

    private sealed interface PasswordLoad {
        data class Loaded(
            val password: String,
        ) : PasswordLoad

        data class Refused(
            val reason: MailFailureReason,
        ) : PasswordLoad
    }

    /** Load the password from the secret store; a missing one or an unusable store is typed. */
    private suspend fun loadPassword(account: MailAccount): PasswordLoad {
        val password =
            when (val stored = credentials.load(account.accountKey)) {
                is MailSecretResult.Success -> {
                    stored.value?.takeIf(String::isNotBlank)
                }

                is MailSecretResult.Failed -> {
                    runtime.update { it.copy(hasPassword = false, credentialProblem = stored.reason) }
                    return PasswordLoad.Refused(MailFailureReason.SECRET_UNAVAILABLE)
                }
            }
        if (password == null) {
            runtime.update { it.copy(hasPassword = false) }
            return PasswordLoad.Refused(MailFailureReason.NO_PASSWORD)
        }
        runtime.update { it.copy(hasPassword = true, credentialProblem = null) }
        return PasswordLoad.Loaded(password)
    }

    /**
     * The check's one connection, and the way to a fresh one when it drops: a reconnect replaces
     * [current], and closing closes whichever is open.
     */
    private inner class Link(
        private val account: MailAccount,
        private val password: String,
    ) {
        private var connection: MailConnection? = null

        val current: MailConnection get() = checkNotNull(connection) { "Read before the folder was opened" }

        suspend fun open(): MailOpenResult =
            transport.open(account, password).also { opened ->
                if (opened is MailOpenResult.Opened) connection = opened.connection
            }

        suspend fun close() {
            connection?.close()
            connection = null
        }
    }

    /** The checks that need no secret store and no network, in the order their refusals matter. */
    private fun preflight(account: MailAccount): MailFailureReason? =
        when {
            account.security == MailSecurity.PLAIN && !account.allowPlain -> MailFailureReason.PLAIN_REFUSED
            !account.isConfigured -> MailFailureReason.NOT_CONFIGURED
            !transport.available -> MailFailureReason.UNSUPPORTED
            else -> null
        }

    /**
     * Place the cursor, read the passed-over mail again when [retry] asks, then the pages after the
     * cursor ([readPages]).
     */
    private suspend fun read(
        account: MailAccount,
        link: Link,
        routine: Boolean,
        retry: Boolean,
        now: Long,
    ): MailCheckReport {
        val stored = cursors.get(account.accountKey, account.folder)
        val placed =
            MailCursorPlacement.place(account, stored, link.current, now)
                ?: return stop(
                    account,
                    MailCheckReport(checkedAtMillis = now),
                    MailCheckError(MailFailureReason.FETCH_FAILED),
                    now,
                )
        val remainingAtStart = link.current.countAfter(placed.lastUid)
        val cursor = placed.copy(remaining = remainingAtStart)
        if (cursor != stored) save(cursor)
        val start = MailCheckReport(checkedAtMillis = now)
        if (!retry || cursor.passedOver.isEmpty()) {
            return readPages(account, link, routine, Position(cursor, start, remainingAtStart), now)
        }
        val retried = retryPassedOver(account, link.current, cursor, start)
        save(retried.cursor)
        val error =
            retried.error
                ?: return readPages(
                    account,
                    link,
                    routine,
                    // The retried mail is counted as seen, but lies behind the cursor, not in what is left.
                    Position(retried.cursor, retried.report, remainingAtStart?.plus(retried.report.messagesSeen)),
                    now,
                )
        return finish(
            retried.cursor.copy(lastCheckedAtMillis = now, lastError = error.reason),
            retried.report.copy(errors = listOf(error)),
        )
    }

    /** Where a check stands before its pages: the cursor, what it did so far, and what was left. */
    private class Position(
        val cursor: MailCursor,
        val report: MailCheckReport,
        val remainingAtStart: Int?,
    )

    /**
     * Read pages over the connection until the folder is exhausted, a message fails, the user
     * cancels or, for a routine poll that is not catching up, [MAX_PAGES_PER_CHECK] pages were read.
     */
    private suspend fun readPages(
        account: MailAccount,
        link: Link,
        routine: Boolean,
        from: Position,
        now: Long,
    ): MailCheckReport {
        var cursor = from.cursor
        var report = from.report
        val remainingAtStart = from.remainingAtStart
        val pageLimit = if (routine && !cursor.catchingUp) MAX_PAGES_PER_CHECK else Int.MAX_VALUE
        var error: MailCheckError? = null
        var exhausted = false
        // Set once no further page may be read: the folder is exhausted, a page failed or stalled.
        var done = false
        var pages = 0
        // Reconnects since the last page that moved the cursor: a connection the server drops every
        // few minutes of a long catch-up is reopened every time, while a dead server ends the check.
        var reconnects = 0
        while (!done && pages < pageLimit && !cancelRequested()) {
            pages++
            val previous = cursor.lastUid
            var lost: MailCheckError? = null
            when (val fetched = link.current.fetchAfter(previous)) {
                is MailFetchResult.Failed -> {
                    lost = MailCheckError(fetched.reason, fetched.detail)
                }

                is MailFetchResult.Success -> {
                    val page = readPage(account, link.current, fetched, report, cursor, remainingAtStart)
                    report = page.report
                    cursor = page.cursor
                    error = page.error
                    lost = page.lost
                    exhausted = page.exhausted
                    done = page.done
                }
            }
            if (cursor.lastUid > previous) reconnects = 0
            if (lost != null) {
                val cause = lost.detail ?: "no cause"
                log.warn("Email fetch after UID ${cursor.lastUid} failed: ${lost.reason.name} ($cause)")
                val retry = lost.reason in RECONNECTABLE && reconnects < MAX_RECONNECTS
                if (retry && reconnect(link, cursor, ++reconnects)) {
                    pages--
                } else {
                    error = lost
                    done = true
                }
            }
        }
        val cancelled = cancelRequested() && error == null && !exhausted
        return finish(
            cursor.copy(lastCheckedAtMillis = now, lastError = error?.reason),
            report.copy(errors = listOfNotNull(error), cancelled = cancelled),
        )
    }

    /**
     * Replace a connection that failed a fetch with a fresh one, after a short pause. False when no
     * new connection opens, or the folder came back renumbered: the check then ends, and the next
     * one places the cursor again.
     */
    private suspend fun reconnect(
        link: Link,
        cursor: MailCursor,
        attempt: Int,
    ): Boolean {
        log.info("Reconnecting to the mail server ($attempt of $MAX_RECONNECTS)")
        delay(RECONNECT_DELAY_MILLIS)
        withContext(NonCancellable) { link.close() }
        return when (val opened = link.open()) {
            is MailOpenResult.Failed -> {
                log.warn(
                    "Reconnecting to the mail server failed: ${opened.reason.name} (${opened.detail ?: "no cause"})",
                )
                false
            }

            is MailOpenResult.Opened -> {
                opened.connection.uidValidity == cursor.uidValidity
            }
        }
    }

    private fun cancelRequested(): Boolean = runtime.value.cancelRequested

    private class Retried(
        val report: MailCheckReport,
        val cursor: MailCursor,
        val error: MailCheckError?,
    )

    /**
     * Read the passed-over messages again. One the folder no longer holds leaves the list; one that
     * imports fully now leaves it too; one that still cannot is counted and stays. A lost
     * connection ends the retry with what it did saved; the rest wait for the next retry.
     */
    private suspend fun retryPassedOver(
        account: MailAccount,
        connection: MailConnection,
        start: MailCursor,
        startReport: MailCheckReport,
    ): Retried {
        val described =
            when (val fetched = connection.describe(start.passedOver)) {
                is MailFetchResult.Failed -> return Retried(
                    startReport,
                    start,
                    MailCheckError(fetched.reason, fetched.detail),
                )

                is MailFetchResult.Success -> fetched.messages.sortedBy(MailEnvelope::uid)
            }
        val held = described.mapTo(HashSet(), MailEnvelope::uid)
        var cursor = start.copy(passedOver = start.passedOver.filter { it in held })
        var report = startReport
        for (message in described) {
            if (cancelRequested()) break
            val considered = importer.consider(account, connection, message)
            val outcome = considered.outcome
            if (outcome is MessageOutcome.Lost) return Retried(report, cursor, outcome.error)
            report =
                report
                    .copy(
                        messagesSeen = report.messagesSeen + 1,
                        droppedAttachments = report.droppedAttachments + considered.dropped.size,
                    ).tallied(outcome)
            if (outcome is MessageOutcome.Failed) report = report.copy(unreadable = report.unreadable + 1)
            val imported = outcome !is MessageOutcome.Unreadable && outcome !is MessageOutcome.Failed
            if (imported && considered.dropped.isEmpty()) {
                cursor = cursor.copy(passedOver = cursor.passedOver - message.uid)
            }
        }
        log.info("Retried ${described.size} passed-over emails; ${cursor.passedOver.size} still left in the mailbox")
        return Retried(report, cursor, null)
    }

    private data class PageOutcome(
        val report: MailCheckReport,
        val cursor: MailCursor,
        val error: MailCheckError?,
        /** A cancel stopped the page before its last message. */
        val interrupted: Boolean,
        /** The connection failed under a download: the page stops before that message, to reconnect. */
        val lost: MailCheckError? = null,
        /** The page reached the end of the folder. */
        val exhausted: Boolean = false,
        /** No further page may be read: the folder is exhausted, a message failed, or the page stalled. */
        val done: Boolean = false,
    )

    /**
     * Handle one fetched page and move the cursor past it: to its last handled message, or past
     * the whole scanned window when every message in it was handled. Saves the cursor.
     */
    @Suppress("LongParameterList") // The page's inputs: account, connection, the fetch, and the running tallies.
    private suspend fun readPage(
        account: MailAccount,
        connection: MailConnection,
        fetched: MailFetchResult.Success,
        report: MailCheckReport,
        cursor: MailCursor,
        remainingAtStart: Int?,
    ): PageOutcome {
        val previous = cursor.lastUid
        val messages = fetched.messages.filter { it.uid > previous }.sortedBy(MailEnvelope::uid)
        val page = processMessages(account, connection, messages, report, cursor, remainingAtStart)
        var moved = page.cursor
        var exhausted = false
        var done = page.error != null
        if (page.error == null && page.lost == null && !page.interrupted) {
            // An empty or short window still moves the cursor to what it scanned, so a run of
            // deleted UIDs is crossed instead of being read again forever.
            moved = moved.copy(lastUid = maxOf(moved.lastUid, fetched.scannedThroughUid))
            exhausted = fetched.scannedThroughUid >= fetched.uidNext - 1
            // A page that moved nothing would be read again forever: stop, still catching up.
            done = exhausted || moved.lastUid <= previous
        }
        if (exhausted) moved = moved.copy(catchingUp = false, remaining = 0)
        save(moved)
        return page.copy(cursor = moved, exhausted = exhausted, done = done)
    }

    /**
     * One page of messages: each skipped or fully handled message advances the cursor. A message
     * that cannot be read is passed over at once, as reading it again gives the same answer. Any
     * other failure stops at the message before it, so the next check retries that one and nothing
     * earlier, until [MAX_MESSAGE_ATTEMPTS] checks (counted in the cursor, across restarts) have
     * failed on it. A passed-over message gets no post-action and stays in the mailbox. A cancel
     * stops before the next message.
     */
    @Suppress("LongParameterList") // The page's inputs: account, connection, messages, and the running tallies.
    private suspend fun processMessages(
        account: MailAccount,
        connection: MailConnection,
        messages: List<MailEnvelope>,
        startReport: MailCheckReport,
        startCursor: MailCursor,
        remainingAtStart: Int?,
    ): PageOutcome {
        var report = startReport
        var cursor = startCursor
        var error: MailCheckError? = null
        var index = 0
        while (index < messages.size && error == null) {
            if (cancelRequested()) return PageOutcome(report, cursor, null, interrupted = true)
            val message = messages[index]
            index++
            val considered = importer.consider(account, connection, message)
            val outcome = considered.outcome
            if (outcome is MessageOutcome.Lost) {
                return PageOutcome(report, cursor, null, interrupted = false, lost = outcome.error)
            }
            val dropped = considered.dropped
            report =
                report
                    .copy(
                        messagesSeen = report.messagesSeen + 1,
                        droppedAttachments =
                            report.droppedAttachments + dropped.size,
                    ).tallied(outcome)
            if (dropped.isNotEmpty()) {
                log.warn(
                    "Email UID ${message.uid}: left out ${dropped.joinToString { it.name }}; it stays in the mailbox",
                )
            }
            if (outcome is MessageOutcome.Unreadable) {
                log.warn(
                    "Email UID ${message.uid} could not be read (${outcome.cause ?: "no cause"}); left in the mailbox",
                )
            }
            var gaveUp = false
            if (outcome is MessageOutcome.Failed) {
                val attempts = if (cursor.blockedUid == message.uid) cursor.blockedAttempts + 1 else 1
                if (attempts >= MAX_MESSAGE_ATTEMPTS) {
                    gaveUp = true
                    // A message that keeps failing (a vanished UID, an undecodable image, a post-
                    // action the server refuses) is passed over instead of stopping the folder.
                    log.warn(
                        "Email UID ${message.uid} passed over after $attempts failed checks: ${outcome.reason.name}",
                    )
                    report = report.copy(unreadable = report.unreadable + 1)
                } else {
                    log.warn("Email UID ${message.uid} failed (${outcome.reason.name}); retried next check")
                    cursor = cursor.copy(blockedUid = message.uid, blockedAttempts = attempts)
                    error = MailCheckError(outcome.reason, outcome.detail)
                }
            }
            if (error == null) {
                cursor =
                    cursor.copy(
                        lastUid = message.uid,
                        throughMillis = message.receivedMillis ?: message.dateMillis ?: cursor.throughMillis,
                        blockedUid = null,
                        blockedAttempts = 0,
                    )
                // Left in the mailbox not fully imported: kept on the list the user can retry.
                if (gaveUp || outcome is MessageOutcome.Unreadable || dropped.isNotEmpty()) {
                    cursor = cursor.passingOver(message.uid)
                }
            }
            val remaining = remainingAtStart?.let { (it - report.messagesSeen).coerceAtLeast(0) }
            cursor = cursor.copy(remaining = remaining)
            runtime.update {
                it.copy(
                    progress = MailProgress(report.messagesSeen, report.imported, remaining, cursor.throughMillis),
                )
            }
        }
        return PageOutcome(report, cursor, error, interrupted = false)
    }

    /** End a check that could not read the folder: the stored cursor keeps its place and takes [error]. */
    private suspend fun stop(
        account: MailAccount,
        report: MailCheckReport,
        error: MailCheckError,
        now: Long,
    ): MailCheckReport {
        val cursor = cursors.get(account.accountKey, account.folder) ?: MailCursor(account.accountKey, account.folder)
        return finish(
            cursor.copy(lastCheckedAtMillis = now, lastError = error.reason),
            report.copy(errors = report.errors + error),
        )
    }

    private suspend fun save(cursor: MailCursor) {
        cursors.save(cursor)
        runtime.update { it.copy(cursor = cursor) }
    }

    private suspend fun finish(
        cursor: MailCursor,
        report: MailCheckReport,
    ): MailCheckReport {
        cursors.save(cursor)
        runtime.update { it.copy(cursor = cursor, lastReport = report) }
        val error = cursor.lastError
        if (error != null) {
            log.warn("Email import check failed: ${error.name}")
        } else {
            log.info("Email import check finished")
        }
        if (report.importedDocIds.isNotEmpty()) imports.tryEmit(report.importedDocIds)
        return report
    }

    private suspend fun refreshCredentials(account: MailAccount) {
        if (account.host.isBlank() && account.username.isBlank()) {
            // The untouched default account: looking up an empty key would only show a keyring
            // error before the user has configured anything.
            runtime.update { it.copy(hasPassword = false, credentialProblem = null) }
            return
        }
        when (val stored = credentials.load(account.accountKey)) {
            is MailSecretResult.Success -> {
                runtime.update { it.copy(hasPassword = !stored.value.isNullOrBlank(), credentialProblem = null) }
            }

            is MailSecretResult.Failed -> {
                runtime.update { it.copy(hasPassword = false, credentialProblem = stored.reason) }
            }
        }
    }

    private suspend fun reloadCursor(account: MailAccount) {
        val cursor = cursors.get(account.accountKey, account.folder)
        runtime.update { run ->
            // The last report is the folder's it was made for; another account or folder starts blank.
            val sameFolder = run.cursor?.accountKey == account.accountKey && run.cursor.folder == account.folder
            run.copy(cursor = cursor, lastReport = run.lastReport.takeIf { sameFolder })
        }
    }

    private suspend fun storedPassword(account: MailAccount): String? =
        when (val result = credentials.load(account.accountKey)) {
            is MailSecretResult.Success -> {
                result.value?.takeIf(String::isNotBlank)
            }

            is MailSecretResult.Failed -> {
                runtime.update { it.copy(credentialProblem = result.reason) }
                null
            }
        }

    private suspend fun clearQuietly(accountKey: String) {
        runCatchingCancellable { credentials.clear(accountKey) }.onFailure {
            log.warn("Removing an old email password failed", it)
        }
    }

    private companion object {
        /** How often the poller wakes to see whether an account is due; not the account's interval. */
        const val POLL_TICK_MILLIS = 30_000L

        /**
         * A routine poll's cap on pages; the cursor resumes from where it stopped. A catch-up and a
         * check the user asked for read until the folder is exhausted instead.
         */
        const val MAX_PAGES_PER_CHECK = 1

        /** After this many failed checks, a message is passed over rather than stopping the folder. */
        const val MAX_MESSAGE_ATTEMPTS = 3

        /**
         * Fresh connections a check may open in a row without the cursor moving in between, before
         * it gives up until the next poll. Any page that makes progress resets the count.
         */
        const val MAX_RECONNECTS = 3

        /** The pause before a reconnect, so a server that just dropped us is not hit at once. */
        const val RECONNECT_DELAY_MILLIS = 2_000L

        /** Fetch failures a fresh connection can cure; a refused login or a missing folder it cannot. */
        val RECONNECTABLE = setOf(MailFailureReason.FETCH_FAILED, MailFailureReason.CONNECTION_FAILED)

        /** Unread import events kept for a slow collector; a check is minutes apart. */
        const val IMPORT_EVENT_BUFFER = 8
    }
}
