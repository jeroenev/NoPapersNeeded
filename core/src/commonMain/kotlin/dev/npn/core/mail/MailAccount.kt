package dev.npn.core.mail

import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable

/**
 * One desktop user's IMAP account, as the library's journal keeps it. The password is deliberately
 * absent: it lives only in the OS secret store ([MailCredentialStore]), keyed by [accountKey].
 *
 * The account is library data (every device that could import from it should see the same
 * filters and post-action), while the password, the last-processed UID and the last error are
 * this device's own state.
 */
@Serializable
data class MailAccount(
    val host: String = "",
    val port: Int = DEFAULT_IMAPS_PORT,
    val security: MailSecurity = MailSecurity.SSL,
    val username: String = "",
    val folder: String = DEFAULT_FOLDER,
    val enabled: Boolean = false,
    /** Minutes between automatic checks while the app is open; never below [MIN_POLL_MINUTES]. */
    val pollIntervalMinutes: Int = DEFAULT_POLL_MINUTES,
    /** Any one of these matches a message; an empty list matches every message. */
    val filters: List<MailFilter> = emptyList(),
    val postAction: MailPostAction = MailPostAction.KEEP,
    /** The destination of [MailPostAction.MOVE]. */
    val moveToFolder: String = "",
    /** Import a readable PDF of the message headers and plain-text body beside its attachments. */
    val saveEmailAsPdf: Boolean = false,
    /**
     * The explicit opt-in without which [MailSecurity.PLAIN] is refused: the password travels
     * unencrypted, which is never a silent fallback.
     */
    val allowPlain: Boolean = false,
) {
    /** The keyring entry and device cursor this account uses, derived from its connection identity. */
    val accountKey: String
        get() = "${username.trim().lowercase()}@${host.trim().lowercase()}:$port"

    /** Whether every required field is filled and the choices are internally consistent. */
    val isConfigured: Boolean
        get() =
            host.isNotBlank() &&
                username.isNotBlank() &&
                port in 1..MAX_PORT &&
                folder.isNotBlank() &&
                (postAction != MailPostAction.MOVE || moveToFolder.isNotBlank()) &&
                (security != MailSecurity.PLAIN || allowPlain)

    /** This account with the blanks trimmed and the values that have a range brought into it. */
    fun normalized(): MailAccount =
        copy(
            host = host.trim(),
            port = port.coerceIn(1, MAX_PORT),
            username = username.trim(),
            folder = folder.trim().ifBlank { DEFAULT_FOLDER },
            pollIntervalMinutes = pollIntervalMinutes.coerceIn(MIN_POLL_MINUTES, MAX_POLL_MINUTES),
            filters = filters.map(MailFilter::normalized).filterNot(MailFilter::isBlank),
            moveToFolder = moveToFolder.trim(),
        )

    /** Whether [message] is one this account asks for; see [MailFilter.matches]. */
    fun matches(message: MailMessage): Boolean = filters.isEmpty() || filters.any { it.matches(message) }

    companion object {
        const val DEFAULT_FOLDER = "INBOX"
        const val DEFAULT_IMAPS_PORT = 993
        const val DEFAULT_STARTTLS_PORT = 143
        const val DEFAULT_POLL_MINUTES = 15

        /** The shortest automatic interval, so a misconfigured account cannot poll a server continuously. */
        const val MIN_POLL_MINUTES = 5

        /** A day, which is as infrequent as an open app needs to look. */
        const val MAX_POLL_MINUTES = 24 * 60

        const val MAX_PORT = 65_535
    }
}

/** How the IMAP connection is protected. */
@Serializable
enum class MailSecurity {
    SSL,
    STARTTLS,

    /** Unencrypted; accepted only with [MailAccount.allowPlain]. */
    PLAIN,
}

/** What happens to a message once every attachment and its email PDF have been handled. */
@Serializable
enum class MailPostAction {
    KEEP,
    MARK_READ,
    MOVE,
    DELETE,
}

/**
 * One way a message may match. An empty field is no condition; a filter with every field empty is
 * dropped when the account is normalized. A message matches the account when any filter matches.
 */
@Serializable
data class MailFilter(
    val from: String? = null,
    val subject: String? = null,
    /** An attachment file name, a `*`/`?` glob, or a bare extension ("pdf"). */
    val attachment: String? = null,
) {
    fun normalized(): MailFilter =
        MailFilter(
            from = from?.trim()?.takeIf(String::isNotEmpty),
            subject = subject?.trim()?.takeIf(String::isNotEmpty),
            attachment = attachment?.trim()?.takeIf(String::isNotEmpty),
        )

    val isBlank: Boolean get() = from == null && subject == null && attachment == null

    /** Whether this filter's conditions all hold for [message]. */
    fun matches(message: MailMessage): Boolean {
        from?.let { if (!message.from.contains(it, ignoreCase = true)) return false }
        subject?.let { if (!message.subject.contains(it, ignoreCase = true)) return false }
        attachment?.let { pattern ->
            if (message.attachments.none {
                    it.supportedKind() != null &&
                        attachmentNameMatches(
                            pattern,
                            it.fileName,
                        )
                }
            ) {
                return false
            }
        }
        return true
    }
}

/** The attachment types the importer can take: PDFs as they are, images through its image-to-PDF path. */
enum class MailAttachmentKind { PDF, IMAGE }

/** The importer's kind for this attachment, or null when the library cannot take it. */
fun MailAttachment.supportedKind(): MailAttachmentKind? {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    val name = fileName.lowercase()
    return when {
        type == "application/pdf" || name.endsWith(".pdf") -> MailAttachmentKind.PDF
        type in IMAGE_TYPES || name.substringAfterLast('.', "") in IMAGE_EXTENSIONS -> MailAttachmentKind.IMAGE
        else -> null
    }
}

private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/bmp", "image/tiff")
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp", "tif", "tiff")

/** Whether an attachment [name] satisfies a filter [pattern]: a glob, an extension, or a substring. */
internal fun attachmentNameMatches(
    pattern: String,
    name: String,
): Boolean {
    val trimmed = pattern.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it == '*' || it == '?' }) return globMatches(trimmed, name)
    val bare = trimmed.removePrefix(".").lowercase()
    return name.lowercase().endsWith(".$bare") || name.lowercase().contains(bare)
}

/** A small case-insensitive glob: `*` matches any run, `?` one character, everything else is literal. */
private fun globMatches(
    pattern: String,
    text: String,
): Boolean {
    val regex = StringBuilder("^")
    for (char in pattern) {
        when (char) {
            '*' -> regex.append(".*")
            '?' -> regex.append('.')
            else -> regex.append(Regex.escape(char.toString()))
        }
    }
    regex.append('$')
    return Regex(regex.toString(), RegexOption.IGNORE_CASE).matches(text)
}

/**
 * A stable, safe library name for the email PDF of [message]: its UID and subject, sanitized so a
 * subject can never become a path or leave the library, and capped so an overlong subject stays a
 * file name. The UID makes it stable across restarts; the import deduplicates repeated saves.
 */
fun emailPdfFileName(message: MailMessage): String {
    val stem =
        NpnPaths
            .sanitizeSegment("email-${message.uid} ${message.subject}".trim())
            .take(MAX_EMAIL_PDF_STEM)
            .trim()
    return NpnPaths.sanitizeFileName("${stem.ifBlank { "email-${message.uid}" }}.pdf")
}

/** The PDF name used for a supported image attachment, whose bytes the importer turns into a PDF. */
fun imageAttachmentPdfName(fileName: String): String {
    val stem = fileName.substringBeforeLast('.', fileName).trim()
    return NpnPaths.sanitizeFileName(
        NpnPaths.sanitizeSegment(stem.ifBlank { "email image" }.take(MAX_EMAIL_PDF_STEM)) + ".pdf",
    )
}

private const val MAX_EMAIL_PDF_STEM = 120

/** The account as the journal's setting stores it: JSON, and never a password. */
object MailAccountJson {
    private val json = NpnJson.Persisted

    fun decode(stored: String?): MailAccount {
        if (stored.isNullOrBlank()) return MailAccount()
        return runCatching { json.decodeFromString(MailAccount.serializer(), stored) }
            .getOrElse { MailAccount() }
            .normalized()
    }

    fun encode(account: MailAccount): String = json.encodeToString(MailAccount.serializer(), account.normalized())
}
