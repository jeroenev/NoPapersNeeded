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
    /**
     * Set when the transport could not read this one message (a supported attachment over the cap,
     * a malformed part). Its other fields may then be empty: it is never filtered, imported or given
     * a post-action, and it fails like any other message, so it is retried and then passed over
     * instead of failing every fetch of the folder.
     */
    val readFailure: MailFailureReason? = null,
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
 * One page of a folder read from a cursor. [uidNext] is the UID the mailbox would give its next
 * message when the folder was opened, so a page can say whether the folder is exhausted.
 */
sealed interface MailFetchResult {
    data class Success(
        val messages: List<MailMessage>,
        /** The UID the mailbox's next message gets; everything below it existed when it was opened. */
        val uidNext: Long,
        /**
         * The last UID this fetch examined, whether or not a message held it. A page can be empty
         * because a whole UID window was deleted; advancing to this end is what lets the next page
         * look at the following window instead of retrying the same empty one forever.
         */
        val scannedThroughUid: Long,
    ) : MailFetchResult

    data class Failed(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailFetchResult
}

/** What opening the account's folder for one check came to. */
sealed interface MailOpenResult {
    data class Opened(
        val connection: MailConnection,
    ) : MailOpenResult

    data class Failed(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailOpenResult
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
     * Connect and open the account's folder for one whole check: every page and every post-action
     * of the check goes over the returned connection, which the caller closes.
     */
    suspend fun open(
        account: MailAccount,
        password: String,
    ): MailOpenResult

    companion object {
        /** The most messages one fetch returns; a check pages on from the cursor after these. */
        const val PAGE_SIZE = 10

        /**
         * The most UIDs one fetch looks across for [PAGE_SIZE] messages. A mailbox whose old mail
         * was deleted has long runs of unused UIDs; a wide window crosses them in few round trips,
         * while listing a window is only a UID list, never a message body.
         */
        const val SCAN_WINDOW = 500L
    }
}

/**
 * One open folder, held for one check. UIDs are this folder's, under [uidValidity]; the folder
 * cannot change generation while it is open (a server that renumbers closes it, and the next
 * call fails).
 */
interface MailConnection {
    /** The mailbox generation; a stored cursor of another generation is no longer meaningful. */
    val uidValidity: Long

    /** The UID the mailbox's next message gets, as of opening: "new mail only" starts here. */
    val uidNext: Long

    /**
     * The UID of the first message that arrived on or after the day of [sinceMillis] (IMAP
     * `SEARCH SINCE`), [uidNext] when none did, or null when the server could not answer.
     */
    suspend fun firstUidSince(sinceMillis: Long): Long?

    /** How many messages the folder holds after [uid], or null when the server could not say. */
    suspend fun countAfter(uid: Long): Int?

    /**
     * Read up to [MailTransport.PAGE_SIZE] messages whose UID is greater than [afterUid]. Attachments
     * are capped, and only supported ones need be returned.
     */
    suspend fun fetchAfter(afterUid: Long): MailFetchResult

    suspend fun markRead(uid: Long): MailFailureReason?

    suspend fun moveTo(
        uid: Long,
        folder: String,
    ): MailFailureReason?

    suspend fun delete(uid: Long): MailFailureReason?

    /** Close the folder and the connection; never throws, and is safe to call twice. */
    suspend fun close()
}

/** A transport for a host that does not offer email import (Android): every call fails typed. */
object UnsupportedMailTransport : MailTransport {
    override val available: Boolean = false

    override suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason = MailFailureReason.UNSUPPORTED

    override suspend fun open(
        account: MailAccount,
        password: String,
    ): MailOpenResult = MailOpenResult.Failed(MailFailureReason.UNSUPPORTED)
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
    /**
     * The start point [lastUid] was placed for. Null for a cursor placed before start points
     * existed; a cursor whose start point differs from the account's is placed again.
     */
    val startFrom: MailStartPoint? = null,
    /**
     * Set from placing the cursor at a start point until a check reaches the end of the folder:
     * while it is set, a check reads every page instead of a routine poll's few.
     */
    val catchingUp: Boolean = false,
    /** When the last message the cursor passed was sent, for "imported through". */
    val throughMillis: Long? = null,
    /** How many messages were left after the cursor when it was last saved, when known. */
    val remaining: Int? = null,
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
sealed interface MailImportResult {
    /** A new document (or a hidden one brought back): [docId] is what "Show" opens. */
    data class Imported(
        val docId: String,
    ) : MailImportResult

    data object Duplicate : MailImportResult

    data object Failed : MailImportResult
}

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
