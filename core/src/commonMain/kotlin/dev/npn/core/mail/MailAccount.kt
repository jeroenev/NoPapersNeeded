package dev.npn.core.mail

import dev.npn.core.fs.NpnPaths
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable

/**
 * One desktop user's IMAP account, as the library's journal keeps it: one setting per field, so two
 * devices that change different fields at once both keep their change. The password is deliberately
 * absent: it lives only in the OS secret store ([MailCredentialStore]), under [accountKey].
 *
 * The account is library data (every device that could import from it should see the same
 * filters and post-action), while the password, whether this device imports at all, whether it
 * may log in unencrypted, the last-processed UID and the last error are this device's own state.
 */
@Serializable
data class MailAccount(
    val host: String = "",
    val port: Int = DEFAULT_IMAPS_PORT,
    val security: MailSecurity = MailSecurity.SSL,
    val username: String = "",
    val folder: String = DEFAULT_FOLDER,
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
     * How far back a device that has not read this folder yet starts. A value from a later build
     * this one does not know reads as the default, and is not written back unless changed here.
     */
    val startFrom: MailStartPoint = MailStartPoint.LAST_30_DAYS,
) {
    /**
     * The mailbox this account reads: its username at its server. The password and this device's
     * place in the folder are kept under it, so a changed port or connection security keeps both,
     * while a changed server or username is another mailbox, whose password is asked for again.
     */
    val accountKey: String
        get() = "${username.trim().lowercase()}@${host.trim().lowercase()}"

    /** The key earlier builds kept the password and cursor under, with the port; read once to move them. */
    internal val legacyAccountKey: String
        get() = "$accountKey:$port"

    /**
     * Everything a login depends on but the password: what a refused login is remembered for, so
     * that routine checks resume once any of it changes.
     */
    internal val loginKey: String
        get() = "$accountKey:$port:${security.name}"

    /** Whether every required field is filled and the choices are internally consistent. */
    val isConfigured: Boolean
        get() =
            host.isNotBlank() &&
                username.isNotBlank() &&
                port in 1..MAX_PORT &&
                folder.isNotBlank() &&
                (
                    postAction != MailPostAction.MOVE ||
                        (moveToFolder.isNotBlank() && !moveToFolder.equals(folder, ignoreCase = true))
                )

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
    fun matches(message: MailEnvelope): Boolean = filters.isEmpty() || filters.any { it.matches(message) }

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

/**
 * Where reading a folder starts the first time, so a mailbox with years of mail is not read from
 * its oldest message unless that is asked for. Declared from the latest start to the earliest:
 * [reachesFurtherBackThan] is the order.
 */
@Serializable
enum class MailStartPoint {
    /** Only mail that arrives after the first check. */
    NEW_ONLY,
    LAST_30_DAYS,
    LAST_YEAR,

    /** The whole folder, oldest first. */
    EVERYTHING,
    ;

    /** Whether this start reads older mail than [other] does. */
    fun reachesFurtherBackThan(other: MailStartPoint): Boolean = ordinal > other.ordinal

    /**
     * The earliest arrival this start reads, relative to [nowMillis]; null for [EVERYTHING], which
     * has no bound, and for [NEW_ONLY], which starts at the folder's next UID instead of a date.
     */
    fun sinceMillis(nowMillis: Long): Long? =
        when (this) {
            NEW_ONLY, EVERYTHING -> null
            LAST_30_DAYS -> nowMillis - 30 * DAY_MILLIS
            LAST_YEAR -> nowMillis - 365 * DAY_MILLIS
        }

    companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}

/** How the IMAP connection is protected. */
@Serializable
enum class MailSecurity {
    SSL,
    STARTTLS,

    /** Unencrypted; accepted only where this device's user allowed it ([MailDeviceChoices.allowPlain]). */
    PLAIN,
}

/**
 * What happens to a message once every attachment and its email PDF have been handled. An earlier
 * build's "move to the trash" reads as [KEEP]: nothing leaves the mailbox that the user did not
 * choose again.
 */
@Serializable
enum class MailPostAction {
    KEEP,
    MARK_READ,
    MOVE,
}

/**
 * What each device decides for itself about the account, kept in its own `device.db` and never
 * synced: whether it checks the account, and whether it may log in over an unencrypted connection.
 */
data class MailDeviceChoices(
    val enabledHere: Boolean = false,
    /**
     * The explicit opt-in without which [MailSecurity.PLAIN] is refused: the password travels
     * unencrypted, which is never a silent fallback, and one device's choice is not every device's.
     */
    val allowPlain: Boolean = false,
)

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
    fun matches(message: MailEnvelope): Boolean {
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
fun MailAttachment.supportedKind(): MailAttachmentKind? = attachmentKind(fileName, contentType)

/** The importer's kind for an attachment the message offers, before it is downloaded. */
fun MailAttachmentInfo.supportedKind(): MailAttachmentKind? = attachmentKind(fileName, contentType)

/** What the library can make of a part named [fileName] of [contentType]: a PDF, an image, or nothing. */
fun attachmentKind(
    fileName: String,
    contentType: String?,
): MailAttachmentKind? {
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
 * file name. The UID makes it stable across restarts; the import deduplicates repeated saves. It
 * holds the subject, so no log line may name it.
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

/**
 * The account as one JSON value: what the settings form keeps across a recreation, and how builds
 * before the per-field settings stored it (the `mailAccount` setting, still read where a field has
 * no setting of its own). Never a password. A value this build does not know, such as the dropped
 * "move to the trash", reads as the field's default.
 */
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
