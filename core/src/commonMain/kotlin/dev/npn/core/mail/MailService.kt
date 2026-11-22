package dev.npn.core.mail

import dev.npn.core.app.MailFacade
import dev.npn.core.util.EpochClock
import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One failure during a check, with the server's own text kept only as an optional detail. */
data class MailCheckError(
    val reason: MailFailureReason,
    val detail: String? = null,
)

/**
 * What one check did. Counts are for messages this device considered; [imported] and [duplicates]
 * count documents (attachments and the optional email PDF together). [errors] carries the first
 * reason a message or the connection stopped the check.
 */
data class MailCheckReport(
    val messagesSeen: Int = 0,
    val matched: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val errors: List<MailCheckError> = emptyList(),
    val checkedAtMillis: Long = 0,
) {
    val succeeded: Boolean get() = errors.isEmpty()
}

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
)

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
 * The check is single-flight. A message is fully handled only when every supported attachment is
 * imported or already present and its post-action succeeded; otherwise its cursor is not advanced,
 * so the next check tries it again, while earlier messages are not fetched twice.
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
    )

    private val runtime = MutableStateFlow(Runtime())
    private val checkMutex = Mutex()

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
            )
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            MailState(transportAvailable = transport.available, pdfAvailable = pdfRenderer.available),
        )

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

    /** Check now, whatever the interval and enabled switch say; a user asking is enough. */
    override suspend fun checkNow(): MailCheckReport {
        val account = store.current()
        return check(account)
    }

    private suspend fun pollIfDue() {
        if (!transport.available) return
        val account = store.current()
        if (!account.enabled || !account.isConfigured) return
        val cursor = cursors.get(account.accountKey, account.folder)
        val last = cursor?.lastCheckedAtMillis
        val intervalMillis = account.pollIntervalMinutes.coerceAtLeast(MailAccount.MIN_POLL_MINUTES) * 60_000L
        if (last != null && clock.nowMillis() - last < intervalMillis) return
        check(account)
    }

    private suspend fun check(account: MailAccount): MailCheckReport =
        checkMutex.withLock {
            runtime.update { it.copy(checking = true) }
            try {
                checkLocked(account)
            } finally {
                runtime.update { it.copy(checking = false) }
            }
        }

    private suspend fun checkLocked(account: MailAccount): MailCheckReport {
        val now = clock.nowMillis()
        val empty = MailCheckReport(checkedAtMillis = now)
        preflight(account)?.let { reason -> return stop(account, empty, reason, now) }
        val password =
            when (val stored = credentials.load(account.accountKey)) {
                is MailSecretResult.Success -> {
                    stored.value?.takeIf(String::isNotBlank)
                }

                is MailSecretResult.Failed -> {
                    runtime.update { it.copy(hasPassword = false, credentialProblem = stored.reason) }
                    return stop(account, empty, MailFailureReason.SECRET_UNAVAILABLE, now)
                }
            }
        if (password == null) {
            runtime.update { it.copy(hasPassword = false) }
            return stop(account, empty, MailFailureReason.NO_PASSWORD, now)
        }
        runtime.update { it.copy(hasPassword = true, credentialProblem = null) }
        return fetchPages(account, password, cursors.get(account.accountKey, account.folder), now)
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
     * Read pages until the folder is exhausted or a message fails. A full page is followed by
     * another from the advanced cursor; [MAX_PAGES_PER_CHECK] bounds one check, and the next poll
     * continues from where this one stopped.
     */
    private suspend fun fetchPages(
        account: MailAccount,
        password: String,
        cursor: MailCursor?,
        now: Long,
    ): MailCheckReport {
        var lastUid = if (cursor?.uidValidity != null) cursor.lastUid else 0
        var uidValidity: Long? = cursor?.uidValidity
        var report = MailCheckReport(checkedAtMillis = now)
        var pages = 0
        var result: MailCheckReport? = null
        while (pages < MAX_PAGES_PER_CHECK && result == null) {
            pages++
            when (val fetched = transport.fetchMessages(account, password, uidValidity, lastUid)) {
                is MailFetchResult.Failed -> {
                    result =
                        finish(
                            account,
                            report.copy(errors = report.errors + MailCheckError(fetched.reason, fetched.detail)),
                            uidValidity,
                            lastUid,
                            fetched.reason,
                            now,
                        )
                }

                is MailFetchResult.Success -> {
                    val reset = uidValidity != null && uidValidity != fetched.uidValidity
                    if (uidValidity == null || reset) lastUid = 0
                    uidValidity = fetched.uidValidity
                    val previous = lastUid
                    val messages = fetched.messages.filter { it.uid > lastUid }.sortedBy(MailMessage::uid)
                    if (messages.isEmpty()) {
                        // An empty window advances to what it scanned, so a UID gap is crossed
                        // instead of being retried forever. An empty folder stays where it is.
                        lastUid = maxOf(lastUid, fetched.scannedThroughUid)
                        result = finish(account, report, uidValidity, lastUid, null, now)
                    } else {
                        val page = processMessages(account, password, messages, report, lastUid)
                        report = page.report
                        if (page.error != null) {
                            result = finish(account, report, uidValidity, page.lastUid, page.error, now)
                        } else {
                            lastUid = maxOf(page.lastUid, fetched.scannedThroughUid)
                            val exhausted = fetched.scannedThroughUid >= fetched.uidNext - 1
                            if (exhausted || lastUid <= previous) {
                                result = finish(account, report, uidValidity, lastUid, null, now)
                            }
                        }
                    }
                }
            }
        }
        return result ?: finish(account, report, uidValidity, lastUid, null, now)
    }

    private data class PageOutcome(
        val report: MailCheckReport,
        val lastUid: Long,
        val error: MailFailureReason?,
    )

    /**
     * One page of messages: each skipped or fully handled message advances [lastUid]; the first
     * failure stops at the message before it, so the next check retries that one and nothing earlier.
     */
    private suspend fun processMessages(
        account: MailAccount,
        password: String,
        messages: List<MailMessage>,
        startReport: MailCheckReport,
        startUid: Long,
    ): PageOutcome {
        var report = startReport
        var lastUid = startUid
        var error: MailFailureReason? = null
        var index = 0
        while (index < messages.size && error == null) {
            val message = messages[index]
            index++
            report = report.copy(messagesSeen = report.messagesSeen + 1)
            val supported = message.attachments.filter { it.supportedKind() != null }
            if (!account.matches(message)) {
                report = report.copy(skipped = report.skipped + 1)
            } else {
                report = report.copy(matched = report.matched + 1)
                if (supported.isEmpty() && !account.saveEmailAsPdf) {
                    report = report.copy(skipped = report.skipped + 1)
                } else {
                    when (val handled = handle(account, password, message, supported)) {
                        is MessageOutcome.Handled -> {
                            report =
                                report.copy(
                                    imported = report.imported + handled.imported,
                                    duplicates = report.duplicates + handled.duplicates,
                                )
                        }

                        is MessageOutcome.Failed -> {
                            report =
                                report.copy(
                                    imported = report.imported + handled.imported,
                                    duplicates = report.duplicates + handled.duplicates,
                                    errors = report.errors + MailCheckError(handled.reason, handled.detail),
                                )
                            val key = "${account.accountKey}|${account.folder}|${message.uid}"
                            val attempts = (failedAttempts[key] ?: 0) + 1
                            if (attempts >= MAX_MESSAGE_ATTEMPTS) {
                                // A permanently bad message (a vanished UID, an undecodable image)
                                // is passed over after bounded retries instead of stopping the folder.
                                failedAttempts.remove(key)
                                log.warn(
                                    "Skipping email UID ${message.uid} after $attempts failed attempts: " +
                                        handled.reason.name,
                                )
                            } else {
                                failedAttempts[key] = attempts
                                error = handled.reason
                            }
                        }
                    }
                }
            }
            if (error == null) lastUid = message.uid
        }
        return PageOutcome(report, lastUid, error)
    }

    private suspend fun handle(
        account: MailAccount,
        password: String,
        message: MailMessage,
        supported: List<MailAttachment>,
    ): MessageOutcome {
        var imported = 0
        var duplicates = 0
        var failure: MailFailureReason? = null
        var index = 0
        while (index < supported.size && failure == null) {
            val attachment = supported[index]
            index++
            when (val kind = attachment.supportedKind()) {
                MailAttachmentKind.PDF, MailAttachmentKind.IMAGE -> {
                    val name =
                        if (kind == MailAttachmentKind.PDF) {
                            attachment.fileName
                        } else {
                            imageAttachmentPdfName(attachment.fileName)
                        }
                    val result =
                        if (kind == MailAttachmentKind.PDF) {
                            sink.importPdf(attachment.bytes, name)
                        } else {
                            sink.importImage(attachment.bytes, name)
                        }
                    when (result) {
                        MailImportResult.IMPORTED -> {
                            imported++
                            log.info("Imported an email attachment as $name")
                        }

                        MailImportResult.DUPLICATE -> {
                            duplicates++
                        }

                        MailImportResult.FAILED -> {
                            failure = MailFailureReason.IMPORT_FAILED
                        }
                    }
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
                when (sink.importPdf(pdf, emailPdfFileName(message))) {
                    MailImportResult.IMPORTED -> imported++
                    MailImportResult.DUPLICATE -> duplicates++
                    MailImportResult.FAILED -> failure = MailFailureReason.IMPORT_FAILED
                }
            }
        }
        if (failure == null) {
            failure =
                when (account.postAction) {
                    MailPostAction.KEEP -> null
                    MailPostAction.MARK_READ -> transport.markRead(account, password, message.uid)
                    MailPostAction.MOVE -> transport.moveTo(account, password, message.uid, account.moveToFolder)
                    MailPostAction.DELETE -> transport.delete(account, password, message.uid)
                }
        }
        return if (failure == null) {
            MessageOutcome.Handled(imported, duplicates)
        } else {
            // The attachments are in the library; only the message action is left. The cursor stays
            // before this message, so the retry sees duplicates and tries the action again.
            MessageOutcome.Failed(failure, imported = imported, duplicates = duplicates)
        }
    }

    private suspend fun stop(
        account: MailAccount,
        report: MailCheckReport,
        reason: MailFailureReason,
        now: Long,
    ): MailCheckReport {
        val cursor = cursors.get(account.accountKey, account.folder)
        return finish(
            account,
            report.copy(errors = report.errors + MailCheckError(reason)),
            cursor?.uidValidity,
            cursor?.lastUid ?: 0,
            reason,
            now,
        )
    }

    private suspend fun finish(
        account: MailAccount,
        report: MailCheckReport,
        uidValidity: Long?,
        lastUid: Long,
        error: MailFailureReason?,
        now: Long,
    ): MailCheckReport {
        val cursor =
            MailCursor(
                accountKey = account.accountKey,
                folder = account.folder,
                uidValidity = uidValidity,
                lastUid = lastUid,
                lastCheckedAtMillis = now,
                lastError = error,
            )
        cursors.save(cursor)
        runtime.update { it.copy(cursor = cursor, lastReport = report) }
        if (error !=
            null
        ) {
            log.warn("Email import check failed: ${error.name}")
        } else {
            log.info("Email import check finished")
        }
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
        runtime.update { it.copy(cursor = cursor) }
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
            val imported: Int,
            val duplicates: Int,
        ) : MessageOutcome

        data class Failed(
            val reason: MailFailureReason,
            val imported: Int,
            val duplicates: Int,
            val detail: String? = null,
        ) : MessageOutcome
    }

    private companion object {
        /** How often the poller wakes to see whether an account is due; not the account's interval. */
        const val POLL_TICK_MILLIS = 30_000L

        /** A safety cap on pages one check reads; the cursor resumes from where it stopped. */
        const val MAX_PAGES_PER_CHECK = 5

        /** After this many failed tries, a message is passed over rather than stopping the folder. */
        const val MAX_MESSAGE_ATTEMPTS = 5
    }
}
