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
 * check; [cancelled] is a check the user stopped, which kept everything it had done.
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
    val hasPassword: Boolean = false,
    val checking: Boolean = false,
    val lastCheckedAtMillis: Long? = null,
    val lastError: MailFailureReason? = null,
    val lastReport: MailCheckReport? = null,
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
    private val sink: MailDocumentSink,
    private val pdfRenderer: MailPdfRenderer,
    private val clock: EpochClock,
    private val log: NpnLog,
    private val scope: CoroutineScope,
) : MailFacade {
    private data class Runtime(
        val hasPassword: Boolean = false,
        val checking: Boolean = false,
        val cursor: MailCursor? = null,
        val lastReport: MailCheckReport? = null,
        val credentialProblem: MailSecretFailure? = null,
        val progress: MailProgress? = null,
        val cancelRequested: Boolean = false,
    )

    private val runtime = MutableStateFlow(Runtime())
    private val checkMutex = Mutex()
    private val imports = MutableSharedFlow<List<String>>(extraBufferCapacity = IMPORT_EVENT_BUFFER)

    /** Failed attempts per message this session, so one bad mail cannot wedge the whole folder. */
    private val failedAttempts = mutableMapOf<String, Int>()
    private var started = false

    override val state: StateFlow<MailState> =
        combine(store.account, runtime) { account, run ->
            MailState(
                account = account,
                hasPassword = run.hasPassword,
                checking = run.checking,
                lastCheckedAtMillis = run.cursor?.lastCheckedAtMillis,
                lastError = run.cursor?.lastError,
                lastReport = run.lastReport,
                transportAvailable = transport.available,
                pdfAvailable = pdfRenderer.available,
                credentialProblem = run.credentialProblem,
                progress = run.progress,
                cancelling = run.checking && run.cancelRequested,
                catchingUp = run.cursor?.catchingUp == true,
                throughMillis = run.cursor?.throughMillis,
                remaining = run.cursor?.remaining,
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            MailState(transportAvailable = transport.available, pdfAvailable = pdfRenderer.available),
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
    ): MailSaveOutcome {
        val normalized = account.normalized()
        if (!normalized.isConfigured) return MailSaveOutcome.AccountInvalid
        val oldKey = store.current().accountKey
        // Save the account first: a keyring failure then leaves the account visible and fixable,
        // rather than a password stored for a host the journal never accepted.
        store.save(normalized)
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
        if (!transport.available) return MailTestOutcome.Failed(MailFailureReason.UNSUPPORTED)
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
    override suspend fun checkNow(): MailCheckReport {
        val account = store.current()
        // Caught inside, so a check that throws reaches this caller rather than the session's scope.
        return scope.async { runCatchingCancellable { check(account, routine = false) } }.await().getOrThrow()
    }

    override fun cancelCheck() {
        runtime.update { if (it.checking) it.copy(cancelRequested = true) else it }
    }

    /** One tick of the poller: check when the account is enabled and its interval has passed. */
    internal suspend fun pollIfDue() {
        if (!transport.available) return
        val account = store.current()
        if (!account.enabled || !account.isConfigured) return
        val cursor = cursors.get(account.accountKey, account.folder)
        val last = cursor?.lastCheckedAtMillis
        val intervalMillis = account.pollIntervalMinutes.coerceAtLeast(MailAccount.MIN_POLL_MINUTES) * 60_000L
        if (last != null && clock.nowMillis() - last < intervalMillis) return
        check(account, routine = true)
    }

    private suspend fun check(
        account: MailAccount,
        routine: Boolean,
    ): MailCheckReport =
        checkMutex.withLock {
            runtime.update { it.copy(checking = true, cancelRequested = false, progress = MailProgress()) }
            try {
                checkLocked(account, routine)
            } finally {
                runtime.update { it.copy(checking = false, cancelRequested = false, progress = null) }
            }
        }

    private suspend fun checkLocked(
        account: MailAccount,
        routine: Boolean,
    ): MailCheckReport {
        val now = clock.nowMillis()
        val opened = preflight(account)?.let { MailOpenResult.Failed(it) } ?: openWithPassword(account)
        return when (opened) {
            is MailOpenResult.Failed -> {
                stop(account, MailCheckReport(checkedAtMillis = now), MailCheckError(opened.reason, opened.detail), now)
            }

            is MailOpenResult.Opened -> {
                try {
                    read(account, opened.connection, routine, now)
                } finally {
                    withContext(NonCancellable) { opened.connection.close() }
                }
            }
        }
    }

    /** Load the password from the secret store and open the folder with it; a refusal is typed. */
    private suspend fun openWithPassword(account: MailAccount): MailOpenResult {
        val password =
            when (val stored = credentials.load(account.accountKey)) {
                is MailSecretResult.Success -> {
                    stored.value?.takeIf(String::isNotBlank)
                }

                is MailSecretResult.Failed -> {
                    runtime.update { it.copy(hasPassword = false, credentialProblem = stored.reason) }
                    return MailOpenResult.Failed(MailFailureReason.SECRET_UNAVAILABLE)
                }
            }
        if (password == null) {
            runtime.update { it.copy(hasPassword = false) }
            return MailOpenResult.Failed(MailFailureReason.NO_PASSWORD)
        }
        runtime.update { it.copy(hasPassword = true, credentialProblem = null) }
        return transport.open(account, password)
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
     * Read pages over [connection] until the folder is exhausted, a message fails, the user cancels
     * or, for a routine poll that is not catching up, [MAX_PAGES_PER_CHECK] pages were read.
     */
    private suspend fun read(
        account: MailAccount,
        connection: MailConnection,
        routine: Boolean,
        now: Long,
    ): MailCheckReport {
        val stored = cursors.get(account.accountKey, account.folder)
        var cursor =
            place(account, stored, connection, now)
                ?: return stop(
                    account,
                    MailCheckReport(checkedAtMillis = now),
                    MailCheckError(MailFailureReason.FETCH_FAILED),
                    now,
                )
        val remainingAtStart = connection.countAfter(cursor.lastUid)
        cursor = cursor.copy(remaining = remainingAtStart)
        if (cursor != stored) save(cursor)
        val pageLimit = if (routine && !cursor.catchingUp) MAX_PAGES_PER_CHECK else Int.MAX_VALUE
        var report = MailCheckReport(checkedAtMillis = now)
        var error: MailCheckError? = null
        var exhausted = false
        // Set once no further page may be read: the folder is exhausted, a page failed or stalled.
        var done = false
        var pages = 0
        while (!done && pages < pageLimit && !cancelRequested()) {
            pages++
            when (val fetched = connection.fetchAfter(cursor.lastUid)) {
                is MailFetchResult.Failed -> {
                    error = MailCheckError(fetched.reason, fetched.detail)
                    done = true
                }

                is MailFetchResult.Success -> {
                    val previous = cursor.lastUid
                    val messages = fetched.messages.filter { it.uid > previous }.sortedBy(MailMessage::uid)
                    val page = processMessages(account, connection, messages, report, cursor, remainingAtStart)
                    report = page.report
                    cursor = page.cursor
                    error = page.error
                    done = error != null
                    if (error == null && !page.interrupted) {
                        // An empty or short window still moves the cursor to what it scanned, so a
                        // run of deleted UIDs is crossed instead of being read again forever.
                        cursor = cursor.copy(lastUid = maxOf(cursor.lastUid, fetched.scannedThroughUid))
                        exhausted = fetched.scannedThroughUid >= fetched.uidNext - 1
                        // A page that moved nothing would be read again forever: stop, still catching up.
                        done = exhausted || cursor.lastUid <= previous
                    }
                    if (exhausted) cursor = cursor.copy(catchingUp = false, remaining = 0)
                    save(cursor)
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
     * The cursor this check reads from: the stored one, or one placed at the account's start point
     * when this device has not read the folder yet, the server renumbered it, or the start point
     * changed. Null when the server could not say where the start point is.
     *
     * A start moved further back moves the cursor back (the older mail is read again; dedupe by
     * hash keeps documents already in the library from being added twice); one moved later only
     * moves a cursor that is still behind it forward, past mail the user chose not to import.
     */
    private suspend fun place(
        account: MailAccount,
        stored: MailCursor?,
        connection: MailConnection,
        now: Long,
    ): MailCursor? {
        val start = account.startFrom
        val validity = connection.uidValidity
        return when {
            stored?.uidValidity == null -> {
                val first = firstUid(connection, start, now) ?: return null
                (stored ?: MailCursor(account.accountKey, account.folder)).copy(
                    uidValidity = validity,
                    lastUid = first - 1,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = null,
                )
            }

            stored.uidValidity != validity -> {
                // A renumbered folder: the old UIDs mean nothing. Read from the start point, or from
                // a day before the last check when that is earlier, so mail that arrived since the
                // last check is not lost with the old numbering.
                val sinceLastCheck = stored.lastCheckedAtMillis?.minus(MailStartPoint.DAY_MILLIS)
                val first = firstUid(connection, start, now, sinceLastCheck) ?: return null
                stored.copy(
                    uidValidity = validity,
                    lastUid = first - 1,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = null,
                )
            }

            stored.startFrom != start -> {
                val placed = (firstUid(connection, start, now) ?: return null) - 1
                val previous = stored.startFrom
                val lastUid =
                    if (previous != null && start.reachesFurtherBackThan(previous)) {
                        minOf(stored.lastUid, placed)
                    } else {
                        // A cursor from before start points is treated like a later start: it never
                        // reads back past where it already was.
                        maxOf(stored.lastUid, placed)
                    }
                stored.copy(
                    lastUid = lastUid,
                    startFrom = start,
                    catchingUp = true,
                    throughMillis = stored.throughMillis.takeIf { lastUid == stored.lastUid },
                )
            }

            else -> {
                stored
            }
        }
    }

    /** The first UID [start] reads, or the earlier one [alsoSince] reaches; null when the server failed. */
    private suspend fun firstUid(
        connection: MailConnection,
        start: MailStartPoint,
        now: Long,
        alsoSince: Long? = null,
    ): Long? {
        if (start == MailStartPoint.EVERYTHING) return 1
        val since = listOfNotNull(start.sinceMillis(now), alsoSince).minOrNull() ?: return connection.uidNext
        return connection.firstUidSince(since)
    }

    private fun cancelRequested(): Boolean = runtime.value.cancelRequested

    private data class PageOutcome(
        val report: MailCheckReport,
        val cursor: MailCursor,
        val error: MailCheckError?,
        /** A cancel stopped the page before its last message. */
        val interrupted: Boolean,
    )

    /**
     * One page of messages: each skipped or fully handled message advances the cursor; the first
     * failure stops at the message before it, so the next check retries that one and nothing
     * earlier. A cancel stops before the next message.
     */
    @Suppress("LongParameterList") // The page's inputs: account, connection, messages, and the running tallies.
    private suspend fun processMessages(
        account: MailAccount,
        connection: MailConnection,
        messages: List<MailMessage>,
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
            report = report.copy(messagesSeen = report.messagesSeen + 1)
            val supported = message.attachments.filter { it.supportedKind() != null }
            val readFailure = message.readFailure
            val outcome: MessageOutcome? =
                when {
                    // A message the transport could not read fails without touching the library or
                    // the mailbox, and takes the same bounded retries as any other failure.
                    readFailure != null -> {
                        MessageOutcome.Failed(readFailure, importedDocIds = emptyList(), duplicates = 0)
                    }

                    !account.matches(message) -> {
                        report = report.copy(skipped = report.skipped + 1)
                        null
                    }

                    supported.isEmpty() && !account.saveEmailAsPdf -> {
                        report = report.copy(matched = report.matched + 1, skipped = report.skipped + 1)
                        null
                    }

                    else -> {
                        report = report.copy(matched = report.matched + 1)
                        handle(account, connection, message, supported)
                    }
                }
            when (outcome) {
                null -> {}

                is MessageOutcome.Handled -> {
                    report = report.counting(outcome.importedDocIds, outcome.duplicates)
                }

                is MessageOutcome.Failed -> {
                    report = report.counting(outcome.importedDocIds, outcome.duplicates)
                    val key = "${account.accountKey}|${account.folder}|${message.uid}"
                    val attempts = (failedAttempts[key] ?: 0) + 1
                    if (attempts >= MAX_MESSAGE_ATTEMPTS) {
                        // A permanently bad message (a vanished UID, an undecodable image, an
                        // attachment over the cap) is passed over after bounded retries instead of
                        // stopping the folder; it gets no post-action, so it stays in the mailbox.
                        failedAttempts.remove(key)
                        log.warn(
                            "Skipping email UID ${message.uid} after $attempts failed attempts: " +
                                outcome.reason.name,
                        )
                    } else {
                        failedAttempts[key] = attempts
                        error = MailCheckError(outcome.reason, outcome.detail)
                    }
                }
            }
            if (error == null) {
                cursor = cursor.copy(lastUid = message.uid, throughMillis = message.dateMillis ?: cursor.throughMillis)
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

    private fun MailCheckReport.counting(
        docIds: List<String>,
        duplicates: Int,
    ): MailCheckReport =
        copy(
            imported = imported + docIds.size,
            importedDocIds = importedDocIds + docIds,
            duplicates = this.duplicates + duplicates,
        )

    private suspend fun handle(
        account: MailAccount,
        connection: MailConnection,
        message: MailMessage,
        supported: List<MailAttachment>,
    ): MessageOutcome {
        val imported = mutableListOf<String>()
        var duplicates = 0
        var failure: MailFailureReason? = null

        fun record(result: MailImportResult) {
            when (result) {
                is MailImportResult.Imported -> imported += result.docId
                MailImportResult.Duplicate -> duplicates++
                MailImportResult.Failed -> failure = MailFailureReason.IMPORT_FAILED
            }
        }

        var index = 0
        while (index < supported.size && failure == null) {
            val attachment = supported[index]
            index++
            when (attachment.supportedKind()) {
                MailAttachmentKind.PDF -> {
                    record(sink.importPdf(attachment.bytes, attachment.fileName))
                    if (failure == null) log.info("Imported an email attachment as ${attachment.fileName}")
                }

                MailAttachmentKind.IMAGE -> {
                    record(sink.importImage(attachment.bytes, imageAttachmentPdfName(attachment.fileName)))
                }

                null -> {}
            }
        }
        if (failure == null && account.saveEmailAsPdf) {
            val pdf =
                runCatchingCancellable { pdfRenderer.render(message) }.getOrElse { thrown ->
                    log.warn("Rendering an email as a PDF failed", thrown)
                    null
                }
            if (pdf == null) {
                failure = MailFailureReason.PDF_RENDER_FAILED
            } else {
                record(sink.importPdf(pdf, emailPdfFileName(message)))
            }
        }
        if (failure == null) {
            failure =
                when (account.postAction) {
                    MailPostAction.KEEP -> null
                    MailPostAction.MARK_READ -> connection.markRead(message.uid)
                    MailPostAction.MOVE -> connection.moveTo(message.uid, account.moveToFolder)
                    MailPostAction.DELETE -> connection.delete(message.uid)
                }
        }
        val reason = failure
        return if (reason == null) {
            MessageOutcome.Handled(imported, duplicates)
        } else {
            // The attachments are in the library; only the message action is left. The cursor stays
            // before this message, so the retry sees duplicates and tries the action again.
            MessageOutcome.Failed(reason, importedDocIds = imported, duplicates = duplicates)
        }
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

    private sealed interface MessageOutcome {
        data class Handled(
            val importedDocIds: List<String>,
            val duplicates: Int,
        ) : MessageOutcome

        data class Failed(
            val reason: MailFailureReason,
            val importedDocIds: List<String>,
            val duplicates: Int,
            val detail: String? = null,
        ) : MessageOutcome
    }

    private companion object {
        /** How often the poller wakes to see whether an account is due; not the account's interval. */
        const val POLL_TICK_MILLIS = 30_000L

        /**
         * A routine poll's cap on pages; the cursor resumes from where it stopped. A catch-up and a
         * check the user asked for read until the folder is exhausted instead.
         */
        const val MAX_PAGES_PER_CHECK = 5

        /** After this many failed tries, a message is passed over rather than stopping the folder. */
        const val MAX_MESSAGE_ATTEMPTS = 5

        /** Unread import events kept for a slow collector; a check is minutes apart. */
        const val IMPORT_EVENT_BUFFER = 8
    }
}
