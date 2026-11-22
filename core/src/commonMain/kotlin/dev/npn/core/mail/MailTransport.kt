package dev.npn.core.mail

/** One attachment the transport read, in memory: the importer accepts bytes, so no temp file is written. */
data class MailAttachment(
    val fileName: String,
    val contentType: String?,
    val bytes: ByteArray,
) {
    // Data-class equality over a ByteArray is identity by default; an explicit one keeps test
    // comparisons and any future collection use honest.
    override fun equals(other: Any?): Boolean =
        other is MailAttachment &&
            fileName == other.fileName &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * fileName.hashCode() + (contentType?.hashCode() ?: 0)) + bytes.contentHashCode()
}

/** One message the transport read, reduced to what filtering, importing and the email PDF need. */
data class MailMessage(
    val uid: Long,
    val from: String,
    val to: String,
    val subject: String,
    val dateMillis: Long?,
    /** The plain-text body; HTML-only mail leaves this empty and imports its attachments only. */
    val bodyText: String,
    val attachments: List<MailAttachment>,
)

/** Why talking to the mail server failed. Every one is mapped to a whole sentence in `composeApp`. */
enum class MailFailureReason {
    NOT_CONFIGURED,
    NO_PASSWORD,
    SECRET_UNAVAILABLE,
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    TLS_FAILED,
    FOLDER_NOT_FOUND,
    FETCH_FAILED,
    IMPORT_FAILED,
    POST_ACTION_FAILED,
    PLAIN_REFUSED,
    PDF_RENDER_FAILED,
    UNSUPPORTED,
}

/**
 * What the IMAP client returns. [uidValidity] names the mailbox generation: when it changes, the
 * UIDs in [messages] are from a new sequence and a stored cursor must start again at the top.
 */
sealed interface MailFetchResult {
    data class Success(
        val uidValidity: Long,
        val messages: List<MailMessage>,
        /** The highest UID the mailbox held when listed, so an empty page can still move the cursor. */
        val uidNext: Long,
    ) : MailFetchResult

    data class Failed(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailFetchResult
}

/**
 * The IMAP seam. Production is Jakarta Mail over TLS ([dev.npn.core.mail.JakartaMailTransport],
 * desktop only); tests implement it in memory and never touch the network.
 *
 * Implementations must not log or store the password, and must not return message bodies in a
 * failure detail.
 */
interface MailTransport {
    /** Whether this host can do IMAP at all; false on Android, where the feature is not offered. */
    val available: Boolean

    /** Connect and open the configured folder, changing nothing. */
    suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason?

    /**
     * Read messages whose UID is greater than [afterUid], or every message of the folder when
     * [knownUidValidity] is null or no longer matches the mailbox's. Attachments are capped, and
     * only supported ones need be returned.
     */
    suspend fun fetchMessages(
        account: MailAccount,
        password: String,
        knownUidValidity: Long?,
        afterUid: Long,
    ): MailFetchResult

    suspend fun markRead(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason?

    suspend fun moveTo(
        account: MailAccount,
        password: String,
        uid: Long,
        folder: String,
    ): MailFailureReason?

    suspend fun delete(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason?

    companion object {
        /** The most messages one fetch returns; a check pages on from the cursor after these. */
        const val PAGE_SIZE = 200
    }
}

/** A transport for a host that does not offer email import (Android): every call fails typed. */
object UnsupportedMailTransport : MailTransport {
    override val available: Boolean = false

    override suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason = MailFailureReason.UNSUPPORTED

    override suspend fun fetchMessages(
        account: MailAccount,
        password: String,
        knownUidValidity: Long?,
        afterUid: Long,
    ): MailFetchResult = MailFetchResult.Failed(MailFailureReason.UNSUPPORTED)

    override suspend fun markRead(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason = MailFailureReason.UNSUPPORTED

    override suspend fun moveTo(
        account: MailAccount,
        password: String,
        uid: Long,
        folder: String,
    ): MailFailureReason = MailFailureReason.UNSUPPORTED

    override suspend fun delete(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason = MailFailureReason.UNSUPPORTED
}

/** Why the OS secret store could not be used. Mapped to a whole sentence in `composeApp`. */
enum class MailSecretFailure {
    UNSUPPORTED,
    UNAVAILABLE,
    STORE_FAILED,
}

/** The outcome of one secret-store operation; the password itself is never part of a failure. */
sealed interface MailSecretResult<out T> {
    data class Success<T>(
        val value: T,
    ) : MailSecretResult<T>

    data class Failed(
        val reason: MailSecretFailure,
    ) : MailSecretResult<Nothing>
}

/**
 * The one place a mail password may be kept: the OS secret store (libsecret on desktop). There is
 * deliberately no plain-text fallback; a host without a working store returns [MailSecretFailure]
 * and the account cannot be saved with a password.
 */
interface MailCredentialStore {
    /** Whether this host has a secret store at all. */
    val available: Boolean

    suspend fun store(
        accountKey: String,
        password: String,
    ): MailSecretResult<Unit>

    suspend fun load(accountKey: String): MailSecretResult<String?>

    suspend fun clear(accountKey: String): MailSecretResult<Unit>
}

/** The credential store of a host where the feature is not offered (Android). */
object UnsupportedMailCredentialStore : MailCredentialStore {
    override val available: Boolean = false

    override suspend fun store(
        accountKey: String,
        password: String,
    ): MailSecretResult<Unit> = MailSecretResult.Failed(MailSecretFailure.UNSUPPORTED)

    override suspend fun load(accountKey: String): MailSecretResult<String?> =
        MailSecretResult.Failed(MailSecretFailure.UNSUPPORTED)

    override suspend fun clear(accountKey: String): MailSecretResult<Unit> =
        MailSecretResult.Failed(MailSecretFailure.UNSUPPORTED)
}

/**
 * This device's progress through one mailbox folder. [uidValidity] names the mailbox generation, so
 * a server that reset its UIDs is detected rather than skipped; [lastUid] is the last message that
 * was fully handled or deliberately skipped.
 */
data class MailCursor(
    val accountKey: String,
    val folder: String,
    val uidValidity: Long? = null,
    val lastUid: Long = 0,
    val lastCheckedAtMillis: Long? = null,
    /** The last typed failure, for the settings pane after a restart; null after a clean check. */
    val lastError: MailFailureReason? = null,
)

/** Device-local cursor storage (`device.db`): never synced, because it describes this device's reads. */
interface MailCursorStore {
    suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursor?

    suspend fun save(cursor: MailCursor)
}

/** What the import path offers email import: the session's own ingest façade, narrowed. */
interface MailDocumentSink {
    suspend fun importPdf(
        bytes: ByteArray,
        suggestedName: String,
    ): MailImportResult

    suspend fun importImage(
        bytes: ByteArray,
        suggestedName: String,
    ): MailImportResult
}

/** The three things one attachment can be to the library, in the terms the email report counts. */
enum class MailImportResult { IMPORTED, DUPLICATE, FAILED }

/** Renders one email as a readable PDF; null when the host has no PDF stack for it. */
interface MailPdfRenderer {
    val available: Boolean

    suspend fun render(message: MailMessage): ByteArray?
}

/** The renderer of a host that cannot make the email PDF (Android). */
object UnsupportedMailPdfRenderer : MailPdfRenderer {
    override val available: Boolean = false

    override suspend fun render(message: MailMessage): ByteArray? = null
}
