package dev.npn.core.mail

import jakarta.mail.Address
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderClosedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.StoreClosedException
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimePart
import jakarta.mail.internet.MimeUtility
import jakarta.mail.search.ComparisonTerm
import jakarta.mail.search.ReceivedDateTerm
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.iap.BadCommandException
import org.eclipse.angus.mail.iap.CommandFailedException
import org.eclipse.angus.mail.iap.ConnectionException
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.cert.CertificateException
import java.util.Date
import java.util.Properties
import javax.imageio.ImageIO
import javax.net.ssl.SSLException

/**
 * The production IMAP transport, desktop only (Jakarta Mail, Angus Mail implementation). TLS
 * certificates are checked by the JVM and the server's identity is verified; there is no trust-all
 * option. Every expected failure comes back as a [MailFailureReason]; the password is passed only
 * to the store's `connect` and never appears in a log line or a detail.
 */
internal class JakartaMailTransport(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MailTransport {
    override val available: Boolean = true

    override suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason? =
        when (val opened = open(account, password)) {
            is MailOpenResult.Failed -> {
                opened.reason
            }

            is MailOpenResult.Opened -> {
                opened.connection.close()
                null
            }
        }

    override suspend fun open(
        account: MailAccount,
        password: String,
    ): MailOpenResult =
        withContext(io) {
            val store =
                try {
                    connect(account, password)
                } catch (failure: Exception) {
                    return@withContext MailOpenResult.Failed(connectReason(failure), causeChain(failure))
                }
            try {
                val target =
                    when (val checked = postActionTarget(store, account)) {
                        is PostActionTarget.Refused -> {
                            close(store)
                            return@withContext MailOpenResult.Failed(checked.reason)
                        }

                        is PostActionTarget.Ready -> {
                            checked.folder
                        }
                    }
                val folder = openFolder(store, account, Folder.READ_WRITE)
                val uidFolder = folder as? UIDFolder
                if (uidFolder == null) {
                    close(folder, expunge = false)
                    close(store)
                    return@withContext MailOpenResult.Failed(MailFailureReason.FETCH_FAILED)
                }
                MailOpenResult.Opened(
                    JakartaMailConnection(store, folder, uidFolder.uidValidity, uidNext(folder, uidFolder), target, io),
                )
            } catch (failure: Exception) {
                close(store)
                MailOpenResult.Failed(connectReason(failure), causeChain(failure))
            }
        }

    private sealed interface PostActionTarget {
        /** The folder the post-action puts a message in; null for one that moves nothing. */
        class Ready(
            val folder: String?,
        ) : PostActionTarget

        class Refused(
            val reason: MailFailureReason,
        ) : PostActionTarget
    }

    /**
     * Where the account's post-action puts a message, checked once per connection before anything
     * is imported. A move target or trash folder the server lacks, or a server that can move no
     * single message (neither MOVE nor UIDPLUS, so an expunge would take every message another
     * client marked deleted), is the account's problem, not one message's: it fails the open.
     */
    private fun postActionTarget(
        store: Store,
        account: MailAccount,
    ): PostActionTarget {
        val imap = store as? IMAPStore
        val canMoveOne = imap != null && (imap.hasCapability("MOVE") || imap.hasCapability("UIDPLUS"))
        return when (account.postAction) {
            MailPostAction.KEEP, MailPostAction.MARK_READ -> {
                PostActionTarget.Ready(null)
            }

            MailPostAction.MOVE -> {
                val target = store.getFolder(account.moveToFolder)
                when {
                    !canMoveOne -> {
                        PostActionTarget.Refused(MailFailureReason.POST_ACTION_UNSUPPORTED)
                    }

                    target == null || !target.exists() -> {
                        PostActionTarget.Refused(
                            MailFailureReason.MOVE_FOLDER_NOT_FOUND,
                        )
                    }

                    else -> {
                        PostActionTarget.Ready(target.fullName)
                    }
                }
            }

            MailPostAction.DELETE -> {
                val trash = trashFolder(store)?.takeUnless { it.equals(account.folder, ignoreCase = true) }
                when {
                    !canMoveOne -> PostActionTarget.Refused(MailFailureReason.POST_ACTION_UNSUPPORTED)
                    trash == null -> PostActionTarget.Refused(MailFailureReason.TRASH_NOT_FOUND)
                    else -> PostActionTarget.Ready(trash)
                }
            }
        }
    }

    /**
     * The server's trash folder: the one it marks `\Trash` (RFC 6154 SPECIAL-USE, as Gmail's
     * `[Gmail]/Trash`, Fastmail and Dovecot do), else one with a usual trash name.
     */
    private fun trashFolder(store: Store): String? {
        val folders = store.defaultFolder.list("*").toList()
        folders
            .firstOrNull { folder ->
                (folder as? IMAPFolder)?.attributes?.any { it.equals("\\Trash", ignoreCase = true) } ==
                    true
            }?.let { return it.fullName }
        return TRASH_NAMES.firstNotNullOfOrNull { name ->
            folders.firstOrNull { it.fullName.equals(name, ignoreCase = true) }?.fullName
        }
    }

    private fun connect(
        account: MailAccount,
        password: String,
    ): Store {
        val protocol = if (account.security == MailSecurity.SSL) "imaps" else "imap"
        val session = Session.getInstance(properties(account, protocol))
        return session.getStore(protocol).apply {
            connect(account.host, account.port, account.username, password)
        }
    }

    private fun properties(
        account: MailAccount,
        protocol: String,
    ): Properties =
        Properties().apply {
            put("mail.store.protocol", protocol)
            put("mail.debug", "false")
            val prefix = "mail.$protocol"
            put("$prefix.host", account.host)
            put("$prefix.port", account.port.toString())
            // Finite timeouts: a stalled server must not hold a library close open indefinitely.
            put("$prefix.connectiontimeout", CONNECT_TIMEOUT_MILLIS.toString())
            put("$prefix.timeout", READ_TIMEOUT_MILLIS.toString())
            put("$prefix.writetimeout", WRITE_TIMEOUT_MILLIS.toString())
            // Fetching a body must not mark the message read; only a post-action does that.
            put("$prefix.peek", "true")
            // JavaMail reads the peek setting under the bare IMAP protocol name too.
            put("mail.imap.peek", "true")
            when (account.security) {
                MailSecurity.SSL -> {
                    put("$prefix.ssl.enable", "true")
                    put("$prefix.ssl.checkserveridentity", "true")
                }

                MailSecurity.STARTTLS -> {
                    put("$prefix.starttls.enable", "true")
                    // Required, so a server that refuses to upgrade cannot silently fall back to clear text.
                    put("$prefix.starttls.required", "true")
                    put("$prefix.ssl.checkserveridentity", "true")
                }

                MailSecurity.PLAIN -> {
                    put("$prefix.ssl.enable", "false")
                    put("$prefix.starttls.enable", "false")
                }
            }
        }

    /**
     * The folder's UIDNEXT, or one past its last message's UID for a server that does not report
     * it, so the end of the folder is always known.
     */
    private fun uidNext(
        folder: Folder,
        uids: UIDFolder,
    ): Long {
        val reported = uids.uidNext
        if (reported > 0) return reported
        val count = folder.messageCount
        return if (count <= 0) 1 else uids.getUID(folder.getMessage(count)) + 1
    }

    private fun openFolder(
        store: Store,
        account: MailAccount,
        mode: Int,
    ): Folder {
        val folder =
            try {
                store.getFolder(account.folder)
            } catch (_: FolderNotFoundException) {
                null
            }
        if (folder == null || !folder.exists()) throw MissingFolderException(account.folder)
        folder.open(mode)
        return folder
    }
}

/**
 * One open folder for one check: every page and post-action reuses the same IMAP connection, so a
 * catch-up over thousands of messages logs in once. Blocking calls run on [io]; a call that fails
 * returns its typed reason, and a dropped connection fails every later call until the check ends.
 */
private class JakartaMailConnection(
    private val store: Store,
    private val folder: Folder,
    override val uidValidity: Long,
    override val uidNext: Long,
    /** Where the post-action puts a message: the move target, or the trash; checked on open. */
    private val postActionFolder: String?,
    private val io: CoroutineDispatcher,
) : MailConnection {
    private val uids = folder as UIDFolder

    /**
     * The last fetch's messages by UID, their envelopes and structures already loaded: a download
     * or post-action of one of them needs no further lookup, and reads only the parts it asks for.
     */
    private var batch: Map<Long, Message> = emptyMap()

    private fun message(uid: Long): Message? = batch[uid] ?: uids.getMessageByUID(uid)

    override suspend fun firstUidSince(sinceMillis: Long): Long? =
        withContext(io) {
            try {
                // IMAP SEARCH SINCE: the server compares the arrival date by day, which is as fine as
                // "the last 30 days" needs. UIDs rise with sequence numbers, so the lowest sequence
                // number found is the lowest UID.
                val found = folder.search(ReceivedDateTerm(ComparisonTerm.GE, Date(sinceMillis)))
                val first = found.minByOrNull(Message::getMessageNumber)
                if (first == null) uidNext else uids.getUID(first)
            } catch (_: MessagingException) {
                null
            }
        }

    override suspend fun countAfter(uid: Long): Int? =
        withContext(io) {
            try {
                if (uid + 1 > uidNext - 1) {
                    0
                } else {
                    // A UID list only (UID FETCH n:m (UID)), never a body: cheap even for thousands.
                    uids.getMessagesByUID(uid + 1, uidNext - 1).count { it != null && uids.getUID(it) > uid }
                }
            } catch (_: MessagingException) {
                null
            }
        }

    override suspend fun fetchAfter(afterUid: Long): MailFetchResult =
        withContext(io) {
            try {
                val start = afterUid + 1
                val windowEnd = minOf(uidNext - 1, start + MailTransport.SCAN_WINDOW - 1)
                val listed =
                    if (windowEnd < start) {
                        emptyList()
                    } else {
                        uids.getMessagesByUID(start, windowEnd).filterNotNull()
                    }
                val taken = listed.take(MailTransport.PAGE_SIZE)
                val messages = describeBatch(taken)
                // A full page stops at its last message; a short one has seen the whole window.
                val scannedThrough =
                    if (listed.size > MailTransport.PAGE_SIZE) messages.last().uid else maxOf(afterUid, windowEnd)
                MailFetchResult.Success(messages = messages, uidNext = uidNext, scannedThroughUid = scannedThrough)
            } catch (failure: Exception) {
                MailFetchResult.Failed(reason(failure, MailFailureReason.FETCH_FAILED), causeChain(failure))
            }
        }

    override suspend fun describe(uids: List<Long>): MailFetchResult =
        withContext(io) {
            try {
                val held =
                    uids
                        .distinct()
                        .sorted()
                        .chunked(MailTransport.PAGE_SIZE)
                        .flatMap { chunk ->
                            this@JakartaMailConnection.uids.getMessagesByUID(chunk.toLongArray()).filterNotNull()
                        }
                val messages = describeBatch(held)
                MailFetchResult.Success(messages, uidNext = uidNext, scannedThroughUid = uids.maxOrNull() ?: 0)
            } catch (failure: Exception) {
                MailFetchResult.Failed(reason(failure, MailFailureReason.FETCH_FAILED), causeChain(failure))
            }
        }

    /**
     * One request for the whole batch's UIDs, envelopes and structures, kept for the downloads that
     * follow. Without it each message costs a round trip for its envelope, another for its
     * structure, and one per part, every one of them before the filters could say it was not wanted.
     */
    private fun describeBatch(messages: List<Message>): List<MailEnvelope> {
        folder.fetch(messages.toTypedArray(), BATCH_PROFILE)
        batch = messages.associateBy(uids::getUID)
        return messages.map { MimeParser.envelopeOrFailed(uids.getUID(it), it) }
    }

    override suspend fun download(
        uid: Long,
        withBody: Boolean,
    ): MailDownloadResult =
        withContext(io) {
            try {
                val message =
                    message(uid) ?: return@withContext MailDownloadResult.Failed(MailFailureReason.FETCH_FAILED)
                MailDownloadResult.Success(MimeParser.downloadOrFailed(uid, message, withBody))
            } catch (failure: Exception) {
                if (isServerRefusal(failure) && !isConnectionLoss(failure)) {
                    MailDownloadResult.Refused(MailFailureReason.FETCH_FAILED, causeChain(failure))
                } else {
                    MailDownloadResult.Failed(reason(failure, MailFailureReason.FETCH_FAILED), causeChain(failure))
                }
            }
        }

    override suspend fun markRead(uid: Long): MailFailureReason? =
        withMessage(uid) { message -> message.setFlag(Flags.Flag.SEEN, true) }

    override suspend fun moveTo(
        uid: Long,
        folder: String,
    ): MailFailureReason? = moveOne(uid, folder, MailFailureReason.MOVE_FOLDER_NOT_FOUND)

    override suspend fun delete(uid: Long): MailFailureReason? =
        moveOne(uid, postActionFolder ?: return MailFailureReason.TRASH_NOT_FOUND, MailFailureReason.TRASH_NOT_FOUND)

    /**
     * Move one message to [target]: IMAP MOVE where the server has it, else a copy and a UID
     * EXPUNGE of that one message (UIDPLUS), never a plain expunge, which would also take every
     * message another client marked deleted. [open] already refused a server with neither.
     */
    private suspend fun moveOne(
        uid: Long,
        target: String,
        missing: MailFailureReason,
    ): MailFailureReason? =
        withContext(io) {
            try {
                val message = message(uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                val destination = store.getFolder(target)
                if (destination == null || !destination.exists()) return@withContext missing
                val imapFolder =
                    folder as? IMAPFolder ?: return@withContext MailFailureReason.POST_ACTION_UNSUPPORTED
                val imapStore = store as IMAPStore
                when {
                    imapStore.hasCapability("MOVE") -> {
                        imapFolder.moveMessages(arrayOf(message), destination)
                    }

                    imapStore.hasCapability("UIDPLUS") -> {
                        imapFolder.copyMessages(arrayOf(message), destination)
                        message.setFlag(Flags.Flag.DELETED, true)
                        imapFolder.expunge(arrayOf(message))
                    }

                    else -> {
                        return@withContext MailFailureReason.POST_ACTION_UNSUPPORTED
                    }
                }
                null
            } catch (failure: Exception) {
                reason(failure, MailFailureReason.POST_ACTION_FAILED)
            }
        }

    private suspend fun withMessage(
        uid: Long,
        action: (Message) -> Unit,
    ): MailFailureReason? =
        withContext(io) {
            try {
                val message = message(uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                action(message)
                null
            } catch (failure: Exception) {
                reason(failure, MailFailureReason.POST_ACTION_FAILED)
            }
        }

    override suspend fun close() {
        withContext(io) {
            close(folder, expunge = false)
            close(store)
        }
    }
}

/** What a batch fetch asks for in one request: UIDs, envelopes (with the arrival date) and structures. */
private val BATCH_PROFILE =
    FetchProfile().apply {
        add(UIDFolder.FetchProfileItem.UID)
        add(FetchProfile.Item.ENVELOPE)
        add(FetchProfile.Item.CONTENT_INFO)
    }

private fun connectReason(failure: Exception): MailFailureReason = reason(failure, MailFailureReason.CONNECTION_FAILED)

private fun reason(
    failure: Exception,
    otherwise: MailFailureReason,
): MailFailureReason =
    when {
        failure is AuthenticationFailedException || failure.hasCause<AuthenticationFailedException>() -> {
            MailFailureReason.AUTHENTICATION_FAILED
        }

        failure is FolderNotFoundException || failure.hasCause<FolderNotFoundException>() ||
            failure is MissingFolderException -> {
            MailFailureReason.FOLDER_NOT_FOUND
        }

        failure.hasCause<SSLException>() || failure.hasCause<CertificateException>() -> {
            MailFailureReason.TLS_FAILED
        }

        else -> {
            otherwise
        }
    }

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this as Throwable?) { it.cause }.any { it is T }

/**
 * What went wrong, as the exception class names along the cause chain (`FolderClosedException <-
 * SocketTimeoutException`). A failure's message can quote a subject, an address or a file name,
 * and the log this goes to may be attached to a public issue; the class names cannot.
 */
internal fun causeChain(failure: Throwable): String =
    generateSequence(failure) { it.cause }
        .take(MAX_CAUSES)
        .joinToString(" <- ") { it::class.simpleName ?: "Throwable" }

private const val MAX_CAUSES = 4

/** Trash folder names to fall back on for a server without SPECIAL-USE, tried in this order. */
private val TRASH_NAMES =
    listOf("Trash", "INBOX.Trash", "INBOX/Trash", "Deleted Items", "Deleted Messages", "[Gmail]/Trash")

/**
 * Whether the server answered a command with NO or BAD: a refusal (throttling, a temporary error,
 * a message it cannot serve now) rather than a message that cannot be parsed. Retried on later
 * checks like any failure, never marked unreadable at once.
 */
internal fun isServerRefusal(failure: Throwable): Boolean =
    failure.hasCause<CommandFailedException>() || failure.hasCause<BadCommandException>()

/**
 * Whether [failure] is the connection's rather than one message's: the folder or store closed,
 * or the socket failed or timed out. Reading the message again over a new connection may work, so
 * it fails the fetch (which reconnects) instead of marking the message unreadable.
 */
internal fun isConnectionLoss(failure: Throwable): Boolean =
    failure.hasCause<FolderClosedException>() ||
        failure.hasCause<StoreClosedException>() ||
        failure.hasCause<SocketException>() ||
        failure.hasCause<SocketTimeoutException>() ||
        failure.hasCause<ConnectionException>()

private fun close(
    folder: Folder?,
    expunge: Boolean,
) {
    try {
        if (folder?.isOpen == true) folder.close(expunge)
    } catch (_: MessagingException) {
        // The store is about to close; a failed expunge is reported by the action that asked for it.
    }
}

private fun close(store: Store?) {
    try {
        store?.close()
    } catch (_: MessagingException) {
        // Nothing left to do with a connection the server already ended.
    }
}

/** A folder the configured account does not have, kept distinct from a connection that failed. */
private class MissingFolderException(
    val folderName: String,
) : Exception("No such folder: $folderName")

/** How long a connection attempt, a read and a write may take before the server is treated as gone. */
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 60_000
private const val WRITE_TIMEOUT_MILLIS = 60_000

/**
 * Walks a Jakarta Mail message into the small models the importer needs. Pure and testable without
 * a server: a test builds a [jakarta.mail.internet.MimeMessage] in memory.
 *
 * Two passes, so a mailbox is read at the cost of what it holds for the library. [envelopeOrFailed]
 * reads only headers and structure: Content-Type, file name, disposition and Content-ID are all part
 * of an IMAP BODYSTRUCTURE, which a batch fetch loads for a whole page at once, so it downloads
 * nothing. [downloadOrFailed] then downloads, for one message, only the parts worth importing; the
 * HTML only when an image's Content-ID needs checking against it, and the plain text only when
 * asked for (the email PDF). The body is capped, as is every attachment.
 *
 * An image is a document only when it is an attachment rather than part of the message's look. It
 * is passed over when the HTML body shows it (its Content-ID is referenced as `cid:`), when it is a
 * resource of the HTML (inside a `multipart/related`) without an explicit `Content-Disposition:
 * attachment`, or when it measures under [MIN_IMAGE_SIDE_PX] on its shorter side, the size of a
 * logo, a social icon or a tracking pixel. An image that cannot be measured is kept. A named PDF is
 * always kept, whatever its disposition: Apple Mail sends one as `inline; filename=…`.
 */
internal object MimeParser {
    private const val MAX_BODY_CHARS = 1_000_000
    private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    private const val MAX_PART_DEPTH = 10

    /** The shorter side below which an image is decoration, not a photographed or scanned page. */
    const val MIN_IMAGE_SIDE_PX = 200

    private val CID_REFERENCE = Regex("""cid:([^"'\s>)]+)""", RegexOption.IGNORE_CASE)

    /** One leaf of a message's structure, and whether it sits among an HTML body's related parts. */
    private class Leaf(
        val part: Part,
        val inRelated: Boolean,
    )

    /** A leaf the library could import, as the structure names it. */
    private class Candidate(
        val leaf: Leaf,
        val name: String,
        val kind: MailAttachmentKind,
        /** The normalized Content-ID of an image, checked against the HTML's `cid:` references. */
        val contentId: String?,
    )

    /**
     * The message's envelope and the attachments its structure offers, or a [MailEnvelope.readFailure]
     * when even that cannot be read. A lost connection is the fetch's failure, and is rethrown.
     */
    fun envelopeOrFailed(
        uid: Long,
        message: Message,
    ): MailEnvelope =
        try {
            MailEnvelope(
                uid = uid,
                from = addresses { message.from },
                to = addresses { message.getRecipients(Message.RecipientType.TO) },
                subject = decode(message.subject).orEmpty(),
                dateMillis = message.sentDate?.time ?: message.receivedDate?.time,
                attachments = candidates(leaves(message)).map { MailAttachmentInfo(it.name, it.leaf.part.contentType) },
                receivedMillis = message.receivedDate?.time ?: message.sentDate?.time,
            )
        } catch (failure: Exception) {
            if (isConnectionLoss(failure)) throw failure
            MailEnvelope(
                uid = uid,
                from = "",
                to = "",
                subject = "",
                dateMillis = null,
                attachments = emptyList(),
                readFailure = MailFailureReason.MESSAGE_UNREADABLE,
                readFailureCause = causeChain(failure),
            )
        }

    /**
     * [download], with a message that cannot be read at all returned as a [MailMessage.readFailure]
     * instead of thrown, so one bad message is passed over alone. A lost connection is rethrown.
     */
    fun downloadOrFailed(
        uid: Long,
        message: Message,
        withBody: Boolean,
    ): MailMessage =
        try {
            download(uid, message, withBody)
        } catch (failure: Exception) {
            // A lost connection is the fetch's to retry, a refusal the check's; neither says the
            // message cannot be read.
            if (isConnectionLoss(failure) || isServerRefusal(failure)) throw failure
            MailMessage(
                uid = uid,
                from = "",
                to = "",
                subject = "",
                dateMillis = null,
                bodyText = "",
                attachments = emptyList(),
                readFailure = MailFailureReason.MESSAGE_UNREADABLE,
                readFailureCause = causeChain(failure),
            )
        }

    /** Everything of [message] at once, its text included: what a test of the two passes reads. */
    fun parseOrFailed(
        uid: Long,
        message: Message,
    ): MailMessage = downloadOrFailed(uid, message, withBody = true)

    fun parse(
        uid: Long,
        message: Message,
    ): MailMessage = download(uid, message, withBody = true)

    private fun download(
        uid: Long,
        message: Message,
        withBody: Boolean,
    ): MailMessage {
        val leaves = leaves(message)
        val candidates = candidates(leaves)
        // The HTML is downloaded only when it can change the answer: an image it may be showing.
        val shown =
            if (candidates.any { it.kind == MailAttachmentKind.IMAGE && it.contentId != null }) {
                htmlReferences(leaves)
            } else {
                emptySet()
            }
        val attachments = mutableListOf<MailAttachment>()
        val dropped = mutableListOf<MailDroppedPart>()
        for (candidate in candidates) {
            if (candidate.contentId != null && candidate.contentId in shown) continue
            when (val read = readAttachment(candidate)) {
                is PartRead.Read -> {
                    attachments += read.attachment
                }

                is PartRead.Dropped -> {
                    dropped += read.reason
                }

                PartRead.Decoration -> {}
            }
        }
        return MailMessage(
            uid = uid,
            from = addresses { message.from },
            to = addresses { message.getRecipients(Message.RecipientType.TO) },
            subject = decode(message.subject).orEmpty(),
            dateMillis = message.sentDate?.time ?: message.receivedDate?.time,
            bodyText = if (withBody) plainText(leaves) else "",
            attachments = attachments,
            droppedParts = dropped,
        )
    }

    /**
     * The leaves of [part]'s structure, in order. Walking it reads multipart and nested-message
     * structure only, which an IMAP message has from its BODYSTRUCTURE: no leaf is downloaded.
     */
    private fun leaves(part: Part): List<Leaf> = mutableListOf<Leaf>().also { collectLeaves(part, it, 0, false) }

    private fun collectLeaves(
        part: Part,
        into: MutableList<Leaf>,
        depth: Int,
        inRelated: Boolean,
    ) {
        if (depth > MAX_PART_DEPTH) return
        when {
            part.isMimeType("message/rfc822") -> {
                (part.content as? Message)?.let { collectLeaves(it, into, depth + 1, inRelated = false) }
            }

            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                val related = inRelated || part.isMimeType("multipart/related")
                for (index in 0 until multipart.count) {
                    multipart.getBodyPart(index)?.let { collectLeaves(it, into, depth + 1, related) }
                }
            }

            else -> {
                into += Leaf(part, inRelated)
            }
        }
    }

    /**
     * The leaves the library could import, judged from the structure alone: every named PDF, and
     * every named image except the HTML body's own related resources (no explicit attachment
     * disposition). An image with a Content-ID may still be one the HTML shows, checked on download.
     */
    private fun candidates(leaves: List<Leaf>): List<Candidate> =
        leaves.mapNotNull { leaf ->
            val part = leaf.part
            val name = decode(part.fileName)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val kind = attachmentKind(name, part.contentType) ?: return@mapNotNull null
            val image = kind == MailAttachmentKind.IMAGE
            val attached = Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)
            if (image && leaf.inRelated && !attached) return@mapNotNull null
            val contentId = if (image) (part as? MimePart)?.contentID?.let(::normalizedId) else null
            Candidate(leaf, name, kind, contentId)
        }

    private sealed interface PartRead {
        class Read(
            val attachment: MailAttachment,
        ) : PartRead

        class Dropped(
            val reason: MailDroppedPart,
        ) : PartRead

        /** An image too small to be a document. */
        data object Decoration : PartRead
    }

    /**
     * Download one candidate: one over the size cap or that cannot be decoded is dropped while the
     * rest of the message is still read, and an image too small to be a document is decoration.
     */
    private fun readAttachment(candidate: Candidate): PartRead {
        val part = candidate.leaf.part
        val bytes =
            try {
                readCapped(part.inputStream)
            } catch (_: AttachmentTooLargeException) {
                return PartRead.Dropped(MailDroppedPart.TOO_LARGE)
            } catch (failure: Exception) {
                if (isConnectionLoss(failure) || isServerRefusal(failure)) throw failure
                return PartRead.Dropped(MailDroppedPart.UNREADABLE)
            }
        val image = candidate.kind == MailAttachmentKind.IMAGE
        if (image && (shorterSide(bytes) ?: Int.MAX_VALUE) < MIN_IMAGE_SIDE_PX) return PartRead.Decoration
        return PartRead.Read(MailAttachment(candidate.name, part.contentType, bytes))
    }

    /**
     * The text of the body's plain-text leaves, capped; a named part is an attachment, not text. A
     * message with no plain text (much mail from shops, banks and services is HTML only) gets the
     * text of its HTML instead, so its email PDF says what the email said.
     */
    private fun plainText(leaves: List<Leaf>): String {
        val body = StringBuilder()

        fun bodyParts(type: String) =
            leaves.map(Leaf::part).filter { part ->
                part.isMimeType(type) &&
                    !isAttachment(part) &&
                    decode(part.fileName)?.let { attachmentKind(it, part.contentType) } == null
            }
        val plain = bodyParts("text/plain")
        val textParts = plain.ifEmpty { bodyParts("text/html") }
        for (part in textParts.takeWhile { body.length < MAX_BODY_CHARS }) {
            val content = part.content as? String
            if (content != null) {
                val text = if (plain.isEmpty()) HtmlText.toPlainText(content.take(MAX_BODY_CHARS)) else content
                if (body.isNotEmpty()) body.append("\n\n")
                body.append(text.take(MAX_BODY_CHARS - body.length))
            }
        }
        return body
            .toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
    }

    /** Every Content-ID the HTML leaves show (`cid:` references), normalized as [normalizedId] does. */
    private fun htmlReferences(leaves: List<Leaf>): Set<String> {
        val htmlParts = leaves.map(Leaf::part).filter { it.isMimeType("text/html") && !isAttachment(it) }
        return htmlParts
            .mapNotNull { it.content as? String }
            .flatMap { html -> CID_REFERENCE.findAll(html.take(MAX_BODY_CHARS)).toList() }
            .map { match -> normalizedId(URLDecoder.decode(match.groupValues[1], Charsets.UTF_8)) }
            .toSet()
    }

    private fun normalizedId(raw: String): String =
        raw
            .trim()
            .removePrefix("<")
            .removeSuffix(">")
            .lowercase()

    /** The shorter side of an image in pixels, read from its header only; null when unreadable. */
    private fun shorterSide(bytes: ByteArray): Int? =
        try {
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))?.use { input ->
                val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
                try {
                    reader.input = input
                    minOf(reader.getWidth(0), reader.getHeight(0))
                } finally {
                    reader.dispose()
                }
            }
        } catch (_: IOException) {
            null
        }

    /** A named part not shown inline: an attachment, whether or not the library can take it. */
    private fun isAttachment(part: Part): Boolean {
        if (part.fileName == null) return false
        return !Part.INLINE.equals(part.disposition, ignoreCase = true)
    }

    private fun readCapped(input: InputStream): ByteArray =
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_ATTACHMENT_BYTES) throw AttachmentTooLargeException()
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    /** The [read] header's addresses, or blank when the header does not parse: a filter then sees none. */
    private fun addresses(read: () -> Array<Address>?): String =
        try {
            read()
                ?.joinToString(", ") { address ->
                    (address as? InternetAddress)?.toUnicodeString() ?: decode(address.toString()).orEmpty()
                }.orEmpty()
        } catch (_: MessagingException) {
            ""
        }

    private fun decode(value: String?): String? =
        value?.let {
            try {
                MimeUtility.decodeText(it)
            } catch (_: Exception) {
                it
            }
        }
}

private class AttachmentTooLargeException : Exception()
