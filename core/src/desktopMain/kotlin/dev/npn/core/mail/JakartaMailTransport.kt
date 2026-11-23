package dev.npn.core.mail

import jakarta.mail.Address
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.FolderNotFoundException
import jakarta.mail.Message
import jakarta.mail.MessagingException
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import org.eclipse.angus.mail.imap.IMAPStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.cert.CertificateException
import java.util.Properties
import javax.net.ssl.SSLException

/**
 * The production IMAP transport, desktop only (Jakarta Mail, Angus Mail implementation). TLS
 * certificates are checked by the JVM and the server's identity is verified; there is no trust-all
 * option. Every expected failure comes back as a [MailFailureReason]; the password is passed only
 * to the store's `connect` and never appears in a log line or a detail.
 */
internal class JakartaMailTransport(
    private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
) : MailTransport {
    override val available: Boolean = true

    override suspend fun testConnection(
        account: MailAccount,
        password: String,
    ): MailFailureReason? =
        withContext(io) {
            withFolder(account, password, Folder.READ_ONLY) { reason -> reason }
        }

    override suspend fun fetchMessages(
        account: MailAccount,
        password: String,
        knownUidValidity: Long?,
        afterUid: Long,
    ): MailFetchResult =
        withContext(io) {
            var store: Store? = null
            var folder: Folder? = null
            try {
                store =
                    try {
                        connect(account, password)
                    } catch (failure: Exception) {
                        return@withContext MailFetchResult.Failed(connectReason(failure))
                    }
                folder =
                    try {
                        openFolder(store, account, Folder.READ_WRITE)
                    } catch (failure: Exception) {
                        return@withContext MailFetchResult.Failed(connectReason(failure))
                    }
                try {
                    val uidFolder =
                        folder as? UIDFolder
                            ?: return@withContext MailFetchResult.Failed(MailFailureReason.FETCH_FAILED)
                    val uidValidity = uidFolder.uidValidity
                    val start = if (knownUidValidity == uidValidity) afterUid + 1 else 1
                    val last = minOf(uidFolder.uidNext - 1, start + MailTransport.PAGE_SIZE - 1)
                    val messages =
                        if (last < start) {
                            emptyList()
                        } else {
                            uidFolder
                                .getMessagesByUID(start, last)
                                .filterNotNull()
                                .map { MimeParser.parse(uidFolder.getUID(it), it) }
                        }
                    MailFetchResult.Success(
                        uidValidity = uidValidity,
                        messages = messages,
                        uidNext = uidFolder.uidNext,
                        scannedThroughUid = maxOf(start - 1, last),
                    )
                } catch (failure: Exception) {
                    MailFetchResult.Failed(reason(failure, MailFailureReason.FETCH_FAILED))
                }
            } finally {
                close(folder, expunge = false)
                close(store)
            }
        }

    override suspend fun markRead(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason? = withMessage(account, password, uid) { message -> message.setFlag(Flags.Flag.SEEN, true) }

    override suspend fun moveTo(
        account: MailAccount,
        password: String,
        uid: Long,
        folder: String,
    ): MailFailureReason? =
        withContext(io) {
            var store: Store? = null
            var source: Folder? = null
            try {
                store = connect(account, password)
                source = openFolder(store, account, Folder.READ_WRITE)
                val message = messageByUid(source, uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                val target = store.getFolder(folder)
                if (target == null || !target.exists()) return@withContext MailFailureReason.FOLDER_NOT_FOUND
                val imapFolder = source as? IMAPFolder ?: return@withContext MailFailureReason.POST_ACTION_FAILED
                if (!(store as IMAPStore).hasCapability(
                        "UIDPLUS",
                    )
                ) {
                    return@withContext MailFailureReason.POST_ACTION_FAILED
                }
                source.copyMessages(arrayOf(message), target)
                message.setFlag(Flags.Flag.DELETED, true)
                imapFolder.expunge(arrayOf(message))
                null
            } catch (failure: Exception) {
                postActionReason(failure)
            } finally {
                close(source, expunge = false)
                close(store)
            }
        }

    override suspend fun delete(
        account: MailAccount,
        password: String,
        uid: Long,
    ): MailFailureReason? =
        withContext(io) {
            var store: Store? = null
            var folder: Folder? = null
            try {
                store = connect(account, password)
                folder = openFolder(store, account, Folder.READ_WRITE)
                val message = messageByUid(folder, uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                val imapFolder = folder as? IMAPFolder ?: return@withContext MailFailureReason.POST_ACTION_FAILED
                if (!(store as IMAPStore).hasCapability(
                        "UIDPLUS",
                    )
                ) {
                    return@withContext MailFailureReason.POST_ACTION_FAILED
                }
                message.setFlag(Flags.Flag.DELETED, true)
                imapFolder.expunge(arrayOf(message))
                null
            } catch (failure: Exception) {
                postActionReason(failure)
            } finally {
                close(folder, expunge = false)
                close(store)
            }
        }

    private suspend fun withMessage(
        account: MailAccount,
        password: String,
        uid: Long,
        action: (Message) -> Unit,
    ): MailFailureReason? =
        withContext(io) {
            var store: Store? = null
            var folder: Folder? = null
            try {
                store = connect(account, password)
                folder = openFolder(store, account, Folder.READ_WRITE)
                val message = messageByUid(folder, uid) ?: return@withContext MailFailureReason.FETCH_FAILED
                action(message)
                null
            } catch (failure: Exception) {
                postActionReason(failure)
            } finally {
                close(folder, expunge = false)
                close(store)
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

    private fun messageByUid(
        folder: Folder,
        uid: Long,
    ): Message? {
        val uidFolder = folder as? UIDFolder ?: return null
        return uidFolder.getMessageByUID(uid)
    }

    private fun connectReason(failure: Exception): MailFailureReason =
        reason(failure, MailFailureReason.CONNECTION_FAILED)

    private fun postActionReason(failure: Exception): MailFailureReason =
        reason(failure, MailFailureReason.POST_ACTION_FAILED)

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
 */
internal object MimeParser {
    private const val MAX_BODY_CHARS = 1_000_000
    private const val MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
    private const val MAX_PART_DEPTH = 10

    fun parse(
        uid: Long,
        message: Message,
    ): MailMessage {
        val body = StringBuilder()
        val attachments = mutableListOf<MailAttachment>()
        walk(message, body, attachments, depth = 0)
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
        body: StringBuilder,
        attachments: MutableList<MailAttachment>,
        depth: Int,
    ) {
        if (depth > MAX_PART_DEPTH) return
        if (part.isMimeType("message/rfc822")) {
            (part.content as? Message)?.let { walk(it, body, attachments, depth + 1) }
            return
        }
        if (part.isMimeType("multipart/*")) {
            val multipart = part.content as? Multipart ?: return
            for (index in 0 until multipart.count) {
                multipart.getBodyPart(index)?.let { walk(it, body, attachments, depth + 1) }
            }
            return
        }
        if (isAttachment(part)) {
            val name = decode(part.fileName)?.takeIf(String::isNotBlank) ?: return
            if (!MailAttachment(name, part.contentType, ByteArray(0)).isSupported()) return
            val bytes = readCapped(part.inputStream)
            attachments += MailAttachment(name, part.contentType, bytes)
            return
        }
        if (part.isMimeType("text/plain") && body.length < MAX_BODY_CHARS) {
            val text = part.content as? String ?: return
            body.append(text.take(MAX_BODY_CHARS - body.length))
        }
    }

    private fun isAttachment(part: Part): Boolean {
        if (part.fileName == null) return false
        return !Part.INLINE.equals(part.disposition, ignoreCase = true)
    }

    private fun MailAttachment.isSupported(): Boolean = supportedKind() != null

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
