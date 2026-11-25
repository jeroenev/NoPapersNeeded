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

/** One attachment a message offers, as its structure names it: nothing of it is downloaded yet. */
data class MailAttachmentInfo(
    val fileName: String,
    val contentType: String?,
)

/**
 * A message as a batch fetch describes it, from its envelope and structure alone: enough to filter
 * it and to know whether it offers anything to import, before a byte of its body is downloaded.
 * [attachments] are the parts the library could take, less the images the structure already shows
 * to be the HTML body's own; one may still turn out to be decoration once downloaded.
 */
data class MailEnvelope(
    val uid: Long,
    val from: String,
    val to: String,
    val subject: String,
    /** When the message was sent (its Date header), for the email PDF. */
    val dateMillis: Long?,
    val attachments: List<MailAttachmentInfo>,
    /**
     * When the server received the message (IMAP INTERNALDATE): the date a start point searches by,
     * so it is also the date "imported through" names.
     */
    val receivedMillis: Long? = dateMillis,
    /** Set when even the envelope or structure could not be read; see [MailMessage.readFailure]. */
    val readFailure: MailFailureReason? = null,
    val readFailureCause: String? = null,
)

/** One message the transport read, reduced to what filtering, importing and the email PDF need. */
data class MailMessage(
    val uid: Long,
    val from: String,
    val to: String,
    val subject: String,
    val dateMillis: Long?,
    /**
     * The plain-text body, downloaded only for the email PDF; HTML-only mail, and a download that
     * did not ask for it, leave this empty.
     */
    val bodyText: String,
    val attachments: List<MailAttachment>,
    /**
     * Set when the transport could not read this one message at all (a structure it cannot parse).
     * Its other fields may then be empty. Reading it again gives the same answer, so it is passed
     * over at once: never filtered, imported or given a post-action, and left in the mailbox.
     */
    val readFailure: MailFailureReason? = null,
    /**
     * Why [readFailure] happened, as exception class names only (`ParseException <- IOException`):
     * enough to tell a parse error from a decoding one in a log that may reach a public issue,
     * without a subject, an address or a file name.
     */
    val readFailureCause: String? = null,
    /**
     * Supported attachments the transport had to leave out (over the size cap, or a part it could
     * not decode). The rest of the message is still read; a message that lost a part keeps its
     * place in the mailbox, as no post-action runs on it.
     */
    val droppedParts: List<MailDroppedPart> = emptyList(),
)

/** Why one attachment of an otherwise readable message was left out. */
enum class MailDroppedPart {
    /** Over the transport's per-attachment size cap. */
    TOO_LARGE,

    /** Its bytes could not be decoded (an unknown transfer encoding, a broken part). */
    UNREADABLE,
}

/** Why talking to the mail server failed. Every one is mapped to a whole sentence in `composeApp`. */
enum class MailFailureReason {
    NOT_CONFIGURED,
    NO_PASSWORD,
    SECRET_UNAVAILABLE,
    CONNECTION_FAILED,
    AUTHENTICATION_FAILED,
    TLS_FAILED,
    FOLDER_NOT_FOUND,

    /** Reading the folder failed: the connection dropped, timed out, or the server refused a fetch. */
    FETCH_FAILED,

    /** One message could not be read, while the folder could. */
    MESSAGE_UNREADABLE,
    IMPORT_FAILED,
    POST_ACTION_FAILED,
    PLAIN_REFUSED,
    PDF_RENDER_FAILED,
    UNSUPPORTED,

    /** The folder the account moves imported mail to does not exist on the server. */
    MOVE_FOLDER_NOT_FOUND,

    /** "Move to the trash" was chosen, and the server names no folder as its trash. */
    TRASH_NOT_FOUND,

    /** The server cannot move or delete one message alone (neither MOVE nor UIDPLUS). */
    POST_ACTION_UNSUPPORTED,
}

/**
 * One page of a folder read from a cursor. [uidNext] is the UID the mailbox would give its next
 * message when the folder was opened, so a page can say whether the folder is exhausted.
 */
sealed interface MailFetchResult {
    data class Success(
        val messages: List<MailEnvelope>,
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

/** What downloading one message's parts came to. */
sealed interface MailDownloadResult {
    data class Success(
        val message: MailMessage,
    ) : MailDownloadResult

    /** The connection failed under the download; a fresh one may succeed. */
    data class Failed(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailDownloadResult

    /**
     * The server answered, but refused to hand this message over (an IMAP NO or BAD: throttling, a
     * temporary error, a message it cannot serve). Not the message's fault and not the connection's:
     * it is tried again on later checks, and passed over only after several refusals.
     */
    data class Refused(
        val reason: MailFailureReason,
        val detail: String? = null,
    ) : MailDownloadResult
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

    /** Connect and open the configured folder, changing nothing; the post-action is checked as [open] does. */
    suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason?

    /**
     * Connect and open the account's folder for one whole check: every page and every post-action
     * of the check goes over the returned connection, which the caller closes. The account's
     * post-action is checked here, before anything is imported: a move target the server does not
     * have, no trash folder to delete into, or a server that cannot move one message alone fails
     * the open, rather than every message failing its post-action one check at a time.
     */
    suspend fun open(
        account: MailAccount,
        password: String,
    ): MailOpenResult

    companion object {
        /**
         * The most messages one fetch describes, in one bulk request for their envelopes and
         * structures; a check pages on from the cursor after these. Only descriptions: a message's
         * parts are downloaded one message at a time, and only the parts worth importing.
         */
        const val PAGE_SIZE = 100

        /**
         * The most UIDs one fetch looks across for [PAGE_SIZE] messages. A mailbox whose old mail
         * was deleted has long runs of unused UIDs; a wide window crosses them in few round trips,
         * while listing a window is only a UID list, never a message body.
         */
        const val SCAN_WINDOW = 1_000L
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
     * Describe up to [MailTransport.PAGE_SIZE] messages whose UID is greater than [afterUid], from
     * their envelopes and structures only: no body or attachment is downloaded.
     */
    suspend fun fetchAfter(afterUid: Long): MailFetchResult

    /**
     * Describe the messages of [uids] that the folder still holds, as [fetchAfter] does, so they
     * can be downloaded: how passed-over mail is tried again. A UID the folder no longer holds is
     * left out of the result.
     */
    suspend fun describe(uids: List<Long>): MailFetchResult

    /**
     * Download the parts of [uid], one of the last fetch's messages, that the library could import
     * (capped; a part over the cap or that cannot be decoded is reported as dropped), and its
     * plain-text body only when [withBody] asks for it, for the email PDF. A message that cannot
     * be read comes back as a [MailMessage.readFailure]; a lost connection as [MailDownloadResult.Failed].
     */
    suspend fun download(
        uid: Long,
        withBody: Boolean,
    ): MailDownloadResult

    suspend fun markRead(uid: Long): MailFailureReason?

    suspend fun moveTo(
        uid: Long,
        folder: String,
    ): MailFailureReason?

    /** Move [uid] to the server's trash folder (its special-use `\Trash`), which [MailTransport.open] found. */
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
    /**
     * The message the cursor is stuck before ([lastUid]'s next) and how many checks have failed on
     * it so far. Kept with the cursor, so restarting the app does not start the count over.
     */
    val blockedUid: Long? = null,
    val blockedAttempts: Int = 0,
    /**
     * Messages behind the cursor that were passed over and left in the mailbox without being fully
     * imported: unreadable, refused too often, or missing an attachment that was too large or could
     * not be decoded. Kept until a retry imports them or the folder is renumbered, so the user can
     * see them and ask for them again whatever later checks did. At most [MAX_PASSED_OVER], the
     * newest kept.
     */
    val passedOver: List<Long> = emptyList(),
) {
    /** This cursor with [uid] among the passed-over messages. */
    fun passingOver(uid: Long): MailCursor = copy(passedOver = (passedOver - uid + uid).takeLast(MAX_PASSED_OVER))

    companion object {
        const val MAX_PASSED_OVER = 1_000
    }
}

/**
 * Device-local mail state (`device.db`), never synced because it describes this device: its cursors,
 * and whether this device imports at all. The account is shared by every device of the library; which
 * of them reads the mailbox is each device's own choice.
 */
interface MailCursorStore {
    suspend fun get(
        accountKey: String,
        folder: String,
    ): MailCursor?

    suspend fun save(cursor: MailCursor)

    /** Whether this device checks the account on its own; off until this device's user turns it on. */
    suspend fun enabledHere(): Boolean

    suspend fun setEnabledHere(enabled: Boolean)
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

    /**
     * The bytes of a document the user trashed or deleted. Email import never brings one back: the
     * user's removal wins over mail read again, and the message stays in the mailbox.
     */
    data object Removed : MailImportResult

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
