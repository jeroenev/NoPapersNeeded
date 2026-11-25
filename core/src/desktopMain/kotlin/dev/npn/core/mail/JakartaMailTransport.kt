package dev.npn.core.mail

import jakarta.mail.Address
import jakarta.mail.AuthenticationFailedException
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
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
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
        withContext(io) {
            withFolder(account, password, Folder.READ_ONLY) { reason -> reason }
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
                    return@withContext MailOpenResult.Failed(connectReason(failure))
                }
            try {
                val folder = openFolder(store, account, Folder.READ_WRITE)
                val uidFolder = folder as? UIDFolder
                if (uidFolder == null) {
                    close(folder, expunge = false)
                    close(store)
                    return@withContext MailOpenResult.Failed(MailFailureReason.FETCH_FAILED)
                }
                MailOpenResult.Opened(
                    JakartaMailConnection(store, folder, uidFolder.uidValidity, uidNext(folder, uidFolder), io),
                )
            } catch (failure: Exception) {
                close(store)
                MailOpenResult.Failed(connectReason(failure))
            }
        }

    private inline fun withFolder(
        account: MailAccount,
        password: String,
        mode: Int,
        failure: (MailFailureReason) -> MailFailureReason,
    ): MailFailureReason? {
        var store: Store? = null
        var folder: Folder? = null
        return try {
            store = connect(account, password)
            folder = openFolder(store, account, mode)
            null
        } catch (thrown: Exception) {
            failure(connectReason(thrown))
        } finally {
            close(folder, expunge = false)
            close(store)
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
    private val io: CoroutineDispatcher,
) : MailConnection {
    private val uids = folder as UIDFolder

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
                val messages = taken.map { MimeParser.parseOrFailed(uids.getUID(it), it) }
                // A full page stops at its last message; a short one has seen the whole window.
                val scannedThrough =
                    if (listed.size > MailTransport.PAGE_SIZE) messages.last().uid else maxOf(afterUid, windowEnd)
                MailFetchResult.Success(messages = messages, uidNext = uidNext, scannedThroughUid = scannedThrough)
            } catch (failure: Exception) {
                MailFetchResult.Failed(reason(failure, MailFailureReason.FETCH_FAILED))
            }
        }

    override suspend fun markRead(uid: Long): MailFailureReason? =
        withMessage(uid) { message -> message.setFlag(Flags.Flag.SEEN, true) }

    override suspend fun moveTo(
        uid: Long,
        folder: String,
    ): MailFailureReason? =
        withContext(io) {
            try {
                val message = uids.getMessageByUID(uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                val target = store.getFolder(folder)
                if (target == null || !target.exists()) return@withContext MailFailureReason.FOLDER_NOT_FOUND
                val imapFolder = this@JakartaMailConnection.folder as? IMAPFolder
                if (imapFolder == null || !(store as IMAPStore).hasCapability("UIDPLUS")) {
                    return@withContext MailFailureReason.POST_ACTION_FAILED
                }
                imapFolder.copyMessages(arrayOf(message), target)
                message.setFlag(Flags.Flag.DELETED, true)
                imapFolder.expunge(arrayOf(message))
                null
            } catch (failure: Exception) {
                reason(failure, MailFailureReason.POST_ACTION_FAILED)
            }
        }

    override suspend fun delete(uid: Long): MailFailureReason? =
        withContext(io) {
            try {
                val message = uids.getMessageByUID(uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                val imapFolder = folder as? IMAPFolder
                if (imapFolder == null || !(store as IMAPStore).hasCapability("UIDPLUS")) {
                    return@withContext MailFailureReason.POST_ACTION_FAILED
                }
                message.setFlag(Flags.Flag.DELETED, true)
                imapFolder.expunge(arrayOf(message))
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
                val message = uids.getMessageByUID(uid) ?: return@withContext MailFailureReason.FETCH_FAILED
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
 * Walks a Jakarta Mail message into the small model the importer needs. Pure and testable without
 * a server: a test builds a [jakarta.mail.internet.MimeMessage] in memory. The body is capped, and
 * only attachments the library can import are read into memory; everything else is passed over.
 *
 * An image is a document only when it is an attachment rather than part of the message's look. It
 * is passed over when the HTML body shows it (its Content-ID is referenced as `cid:`), when it is a
 * resource of the HTML (inside a `multipart/related`) without an explicit `Content-Disposition:
 * attachment`, or when it measures under [MIN_IMAGE_SIDE_PX] on its shorter side, the size of a
 * logo, a social icon or a tracking pixel. An image that cannot be measured is kept, and PDFs are
 * kept by name as before.
 */
internal object MimeParser {
    private const val MAX_BODY_CHARS = 1_000_000
    private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    private const val MAX_PART_DEPTH = 10

    /** The shorter side below which an image is decoration, not a photographed or scanned page. */
    const val MIN_IMAGE_SIDE_PX = 200

    private val CID_REFERENCE = Regex("""cid:([^"'\s>)]+)""", RegexOption.IGNORE_CASE)

    /** What one walk of a message gathers, and the Content-IDs its HTML body shows. */
    private class Walk(
        val shownByHtml: Set<String>,
    ) {
        val body = StringBuilder()
        val attachments = mutableListOf<MailAttachment>()
    }

    /**
     * [parse], with a message that cannot be read (an attachment over the cap, a malformed part)
     * returned as a [MailMessage.readFailure] instead of thrown, so one bad message fails alone and
     * the service's bounded retries can pass it over. A closed folder or store is the connection's
     * failure, not the message's, and still fails the whole fetch.
     */
    fun parseOrFailed(
        uid: Long,
        message: Message,
    ): MailMessage =
        try {
            parse(uid, message)
        } catch (closed: FolderClosedException) {
            throw closed
        } catch (closed: StoreClosedException) {
            throw closed
        } catch (_: Exception) {
            MailMessage(
                uid = uid,
                from = "",
                to = "",
                subject = "",
                dateMillis = null,
                bodyText = "",
                attachments = emptyList(),
                readFailure = MailFailureReason.FETCH_FAILED,
            )
        }

    fun parse(
        uid: Long,
        message: Message,
    ): MailMessage {
        val shown = mutableSetOf<String>()
        collectHtmlReferences(message, shown, depth = 0)
        val walk = Walk(shown)
        walk(message, walk, depth = 0, inRelated = false)
        val body = walk.body
        val attachments = walk.attachments
        return MailMessage(
            uid = uid,
            from = addresses(message.from),
            to = addresses(message.getRecipients(Message.RecipientType.TO)),
            subject = decode(message.subject).orEmpty(),
            dateMillis = message.sentDate?.time ?: message.receivedDate?.time,
            bodyText =
                body
                    .toString()
                    .replace("\r\n", "\n")
                    .replace('\r', '\n')
                    .trim(),
            attachments = attachments,
        )
    }

    private fun walk(
        part: Part,
        walk: Walk,
        depth: Int,
        inRelated: Boolean,
    ) {
        if (depth > MAX_PART_DEPTH) return
        if (part.isMimeType("message/rfc822")) {
            (part.content as? Message)?.let { walk(it, walk, depth + 1, inRelated = false) }
            return
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return
            val related = inRelated || part.isMimeType("multipart/related")
            for (index in 0 until multipart.count) {
                multipart.getBodyPart(index)?.let { walk(it, walk, depth + 1, related) }
            }
            return
        }
        if (isAttachment(part)) {
            val name = decode(part.fileName)?.takeIf(String::isNotBlank) ?: return
            val kind = MailAttachment(name, part.contentType, ByteArray(0)).supportedKind() ?: return
            val image = kind == MailAttachmentKind.IMAGE
            if (image && partOfTheBody(part, inRelated, walk.shownByHtml)) return
            val bytes = readCapped(part.inputStream)
            if (image && (shorterSide(bytes) ?: Int.MAX_VALUE) < MIN_IMAGE_SIDE_PX) return
            walk.attachments += MailAttachment(name, part.contentType, bytes)
            return
        }
        val body = walk.body
        if (part.isMimeType("text/plain") && body.length < MAX_BODY_CHARS) {
            val text = part.content as? String ?: return
            body.append(text.take(MAX_BODY_CHARS - body.length))
        }
    }

    /** Every Content-ID an HTML part of [part] shows (`cid:` references), normalized as [contentId] is. */
    private fun collectHtmlReferences(
        part: Part,
        into: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_PART_DEPTH) return
        when {
            part.isMimeType("message/rfc822") -> {
                (part.content as? Message)?.let { collectHtmlReferences(it, into, depth + 1) }
            }

            part.isMimeType("multipart/*") -> {
                val multipart = part.content as? Multipart ?: return
                for (index in 0 until multipart.count) {
                    multipart.getBodyPart(index)?.let { collectHtmlReferences(it, into, depth + 1) }
                }
            }

            part.isMimeType("text/html") && !isAttachment(part) -> {
                val html = part.content as? String ?: return
                CID_REFERENCE.findAll(html.take(MAX_BODY_CHARS)).forEach { match ->
                    into += normalizedId(URLDecoder.decode(match.groupValues[1], Charsets.UTF_8))
                }
            }
        }
    }

    /**
     * Whether an image part belongs to the HTML body rather than to the mail's attachments: the
     * body shows it, or it is one of the body's related resources and not explicitly attached.
     */
    private fun partOfTheBody(
        part: Part,
        inRelated: Boolean,
        shownByHtml: Set<String>,
    ): Boolean {
        val id = (part as? MimePart)?.contentID?.let(::normalizedId)
        if (id != null && id in shownByHtml) return true
        return inRelated && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)
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

    private fun addresses(addresses: Array<Address>?): String =
        addresses
            ?.joinToString(", ") { address ->
                (address as? InternetAddress)?.toUnicodeString() ?: decode(address.toString()).orEmpty()
            }.orEmpty()

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
