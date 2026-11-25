package dev.npn.core.mail

import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

/** What one message of a page came to. */
internal sealed interface MessageOutcome {
    /** Not one the account asks for. */
    data object Skipped : MessageOutcome

    /** Asked for, but nothing in it the library can take. */
    data object SkippedMatched : MessageOutcome

    data class Unreadable(
        val cause: String?,
    ) : MessageOutcome

    /** The connection failed while this message was downloaded; it is read again after a reconnect. */
    data class Lost(
        val error: MailCheckError,
    ) : MessageOutcome

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

/** A message's outcome, and the attachments its download had to leave out. */
internal class Considered(
    val outcome: MessageOutcome,
    val dropped: List<MailDroppedPart> = emptyList(),
)

/**
 * Takes one message from its envelope to the library: decides from the envelope whether it is worth
 * a download, downloads it, imports its attachments (and the email PDF), and runs the post-action.
 * The cursor and the retries across checks stay [MailService]'s.
 */
internal class MailMessageImporter(
    private val sink: MailDocumentSink,
    private val pdfRenderer: MailPdfRenderer,
    private val log: NpnLog,
) {
    /**
     * What one message comes to: passed over, unreadable, or imported (or failing to be). Decided
     * from its envelope first, so a message the filters do not ask for, or that offers nothing to
     * import, costs no download at all; only the rest have their importable parts downloaded, and
     * their text only for the email PDF.
     */
    suspend fun consider(
        account: MailAccount,
        connection: MailConnection,
        envelope: MailEnvelope,
    ): Considered {
        val offers = envelope.attachments.any { it.supportedKind() != null }
        val early =
            when {
                // Never filtered, imported or given a post-action: what it holds is unknown.
                envelope.readFailure != null -> MessageOutcome.Unreadable(envelope.readFailureCause)

                !account.matches(envelope) -> MessageOutcome.Skipped

                !offers && !account.saveEmailAsPdf -> MessageOutcome.SkippedMatched

                else -> null
            }
        if (early != null) return Considered(early)
        val message =
            when (val downloaded = connection.download(envelope.uid, withBody = account.saveEmailAsPdf)) {
                is MailDownloadResult.Failed -> {
                    return Considered(MessageOutcome.Lost(MailCheckError(downloaded.reason, downloaded.detail)))
                }

                is MailDownloadResult.Success -> {
                    downloaded.message
                }
            }
        val supported = message.attachments.filter { it.supportedKind() != null }
        val outcome =
            when {
                message.readFailure != null -> MessageOutcome.Unreadable(message.readFailureCause)

                // Its images were all decoration once measured: nothing to import, nothing to act on.
                supported.isEmpty() && !account.saveEmailAsPdf -> MessageOutcome.SkippedMatched

                else -> handle(account, connection, message, supported)
            }
        return Considered(outcome, message.droppedParts)
    }

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
                    if (failure == null) log.info("Imported a PDF attachment of email UID ${message.uid}")
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
        // A message that lost an attachment keeps its place in the mailbox: moving or deleting it
        // would take the part the library could not take with it.
        if (failure == null && message.droppedParts.isEmpty()) {
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
}

internal fun MailCheckReport.counting(
    docIds: List<String>,
    duplicates: Int,
): MailCheckReport =
    copy(
        imported = imported + docIds.size,
        importedDocIds = importedDocIds + docIds,
        duplicates = this.duplicates + duplicates,
    )

/**
 * [this] with one message's [outcome] counted. A failure counts what it imported; whether it stops
 * the page or is passed over is decided with the cursor, and counted there.
 */
internal fun MailCheckReport.tallied(outcome: MessageOutcome): MailCheckReport =
    when (outcome) {
        is MessageOutcome.Lost -> this
        MessageOutcome.Skipped -> copy(skipped = skipped + 1)
        MessageOutcome.SkippedMatched -> copy(matched = matched + 1, skipped = skipped + 1)
        is MessageOutcome.Unreadable -> copy(unreadable = unreadable + 1)
        is MessageOutcome.Handled -> copy(matched = matched + 1).counting(outcome.importedDocIds, outcome.duplicates)
        is MessageOutcome.Failed -> copy(matched = matched + 1).counting(outcome.importedDocIds, outcome.duplicates)
    }
