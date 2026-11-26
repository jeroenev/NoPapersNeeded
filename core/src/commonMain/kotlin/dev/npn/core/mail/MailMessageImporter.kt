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

                // Counted against the message like any failure: retried next check, passed over after
                // a few, rather than marked unreadable on a server's passing refusal.
                is MailDownloadResult.Refused -> {
                    return Considered(MessageOutcome.Failed(downloaded.reason, emptyList(), 0, downloaded.detail))
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

    class Retried(
        val report: MailCheckReport,
        val cursor: MailCursor,
        val error: MailCheckError?,
    )

    /**
     * Read the passed-over messages again. One the folder no longer holds leaves the list; one that
     * imports fully now leaves it too; one that still cannot is counted and stays. A lost
     * connection ends the retry with what it did saved; the rest wait for the next retry.
     */
    suspend fun retryPassedOver(
        account: MailAccount,
        connection: MailConnection,
        start: MailCursor,
        startReport: MailCheckReport,
        cancelRequested: () -> Boolean,
    ): Retried {
        val described =
            when (val fetched = connection.describe(start.passedOver)) {
                is MailFetchResult.Failed -> return Retried(
                    startReport,
                    start,
                    MailCheckError(fetched.reason, fetched.detail),
                )

                is MailFetchResult.Success -> fetched.messages.sortedBy(MailEnvelope::uid)
            }
        val held = described.mapTo(HashSet(), MailEnvelope::uid)
        var cursor = start.copy(passedOver = start.passedOver.filter { it in held })
        var report = startReport
        for (message in described) {
            if (cancelRequested()) break
            val considered = consider(account, connection, message)
            val outcome = considered.outcome
            if (outcome is MessageOutcome.Lost) return Retried(report, cursor, outcome.error)
            report =
                report
                    .copy(
                        messagesSeen = report.messagesSeen + 1,
                        droppedAttachments = report.droppedAttachments + considered.dropped.size,
                    ).tallied(outcome)
            if (outcome is MessageOutcome.Failed) report = report.copy(unreadable = report.unreadable + 1)
            val imported = outcome !is MessageOutcome.Unreadable && outcome !is MessageOutcome.Failed
            if (imported && considered.dropped.isEmpty()) {
                cursor = cursor.copy(passedOver = cursor.passedOver - message.uid)
            }
        }
        log.info("Retried ${described.size} passed-over emails; ${cursor.passedOver.size} still left in the mailbox")
        return Retried(report, cursor, null)
    }

    private suspend fun handle(
        account: MailAccount,
        connection: MailConnection,
        message: MailMessage,
        supported: List<MailAttachment>,
    ): MessageOutcome {
        val imported = mutableListOf<String>()
        var duplicates = 0
        var removed = false
        var failure: MailFailureReason? = null

        fun record(result: MailImportResult) {
            when (result) {
                is MailImportResult.Imported -> {
                    imported += result.docId
                }

                MailImportResult.Duplicate -> {
                    duplicates++
                }

                // Already a document, one the user removed: counted as a duplicate, and the email is
                // left where it is, as its post-action would take what the user chose not to keep.
                MailImportResult.Removed -> {
                    duplicates++
                    removed = true
                }

                MailImportResult.Failed -> {
                    failure = failure ?: MailFailureReason.IMPORT_FAILED
                }
            }
        }

        // Every part is tried, whatever an earlier one came to: one that cannot be imported (an empty
        // file, an image that does not decode) must not keep the email's invoice out of the library.
        for (attachment in supported) {
            when (attachment.supportedKind()) {
                MailAttachmentKind.PDF -> {
                    val result = sink.importPdf(attachment.bytes, attachment.fileName)
                    record(result)
                    if (result is MailImportResult.Imported) {
                        log.info(
                            "Imported a PDF attachment of email UID ${message.uid}",
                        )
                    }
                }

                MailAttachmentKind.IMAGE -> {
                    record(sink.importImage(attachment.bytes, imageAttachmentPdfName(attachment.fileName)))
                }

                null -> {}
            }
        }
        if (account.saveEmailAsPdf) {
            val pdf = emailPdf(message)
            if (pdf == null) failure = failure ?: MailFailureReason.PDF_RENDER_FAILED else record(pdf)
        }
        if (removed) log.info("Email UID ${message.uid} holds a document that was removed; left in the mailbox")
        // A message that lost an attachment keeps its place in the mailbox: moving it would take the
        // part the library could not take with it.
        if (failure == null && message.droppedParts.isEmpty() && !removed) {
            failure = postAction(account, connection, message.uid)
        }
        val reason = failure
        return if (reason == null) {
            MessageOutcome.Handled(imported, duplicates)
        } else {
            // What did import is in the library. The cursor stays before this message, so the retry
            // sees those as duplicates, tries the failed parts again, and only then the action.
            MessageOutcome.Failed(reason, importedDocIds = imported, duplicates = duplicates)
        }
    }

    /**
     * What [message]'s own PDF came to in the library; null when it could not be made. An email read
     * again (another device, a renumbered folder, a start moved back) is known by its Message-ID:
     * its PDF made again, by another build, need not have the same bytes.
     */
    private suspend fun emailPdf(message: MailMessage): MailImportResult? {
        message.messageId?.let { id -> sink.emailPdfOf(id)?.let { return it } }
        val pdf =
            runCatchingCancellable { pdfRenderer.render(message) }.getOrElse { thrown ->
                log.warn("Rendering an email as a PDF failed", thrown)
                null
            } ?: return null
        return sink.importEmailPdf(pdf, emailPdfFileName(message), message.messageId)
    }
}

/** Run the account's post-action on [uid]; null when it worked or there is none. */
private suspend fun postAction(
    account: MailAccount,
    connection: MailConnection,
    uid: Long,
): MailFailureReason? =
    when (account.postAction) {
        MailPostAction.KEEP -> null
        MailPostAction.MARK_READ -> connection.markRead(uid)
        MailPostAction.MOVE -> connection.moveTo(uid, account.moveToFolder)
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
