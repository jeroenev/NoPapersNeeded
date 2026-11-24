package dev.npn.core.app

import dev.npn.core.index.JournalCoordinator
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailAccountJson
import dev.npn.core.mail.MailAccountStore
import dev.npn.core.mail.MailCheckReport
import dev.npn.core.mail.MailDocumentSink
import dev.npn.core.mail.MailImportResult
import dev.npn.core.mail.MailSaveOutcome
import dev.npn.core.mail.MailState
import dev.npn.core.mail.MailTestOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * Importing attachments from one IMAP account, desktop only. The account itself is library data in
 * the journal; the password lives in the OS secret store and the read cursor in `device.db`.
 *
 * A check never throws for an expected failure: a missing password, an unreachable server or a
 * refused post-action come back in the [MailCheckReport] as a [dev.npn.core.mail.MailFailureReason].
 * Saving an account can fail to reach the secret store, which is its own typed outcome.
 */
interface MailFacade {
    /** The one account, whether a password is saved, and the last check's state. */
    val state: StateFlow<MailState>

    /**
     * Save the account, and the password when [password] is non-null (an empty string removes it).
     * A null password leaves the saved one alone. The account is saved before a secret-store
     * failure is reported, so it remains visible and can be corrected.
     */
    suspend fun saveAccount(
        account: MailAccount,
        password: String?,
    ): MailSaveOutcome

    /** Remove the saved password from the OS secret store; the account stays configured otherwise. */
    suspend fun clearPassword(): MailSaveOutcome

    /** Connect with the account (and the saved password unless [password] is given) and change nothing. */
    suspend fun testConnection(
        account: MailAccount,
        password: String?,
    ): MailTestOutcome

    /**
     * Check now, regardless of the enabled switch or the poll interval, and read until the folder
     * is exhausted. The check outlives a caller that stops waiting; [cancelCheck] stops it.
     */
    suspend fun checkNow(): MailCheckReport

    /**
     * Ask the running check, a poll or a Check now, to stop after the message in hand. Everything
     * it handled stays handled and the cursor keeps its place, so the next check goes on from
     * there. Does nothing while no check runs.
     */
    fun cancelCheck()

    /** The documents each check imported, one event per check that imported any. */
    val importedDocuments: Flow<List<String>>
}

/** [MailAccountStore] over the journal's synced setting; writes run through [SessionLifetime.owned]. */
internal class JournalMailAccount(
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : MailAccountStore {
    override val account: Flow<MailAccount> =
        coordinator.settingFlow(LibrarySettingKeys.MAIL_ACCOUNT).map(MailAccountJson::decode)

    override fun current(): MailAccount = MailAccountJson.decode(coordinator.setting(LibrarySettingKeys.MAIL_ACCOUNT))

    override suspend fun save(account: MailAccount) {
        lifetime.owned { coordinator.putSetting(LibrarySettingKeys.MAIL_ACCOUNT, MailAccountJson.encode(account)) }
    }
}

/**
 * [MailDocumentSink] over the session's ingest façade: email attachments go through the same
 * import path as a picked file or a scan, so hashing, dedupe, the journal and the storage-path
 * rules all apply, and nothing is written into the library outside the importer.
 */
internal class IngestMailSink(
    private val ingest: IngestFacade,
) : MailDocumentSink {
    override suspend fun importPdf(
        bytes: ByteArray,
        suggestedName: String,
    ): MailImportResult = ingest.importPdfBytes(bytes, suggestedName).toMailResult()

    override suspend fun importImage(
        bytes: ByteArray,
        suggestedName: String,
    ): MailImportResult = ingest.importImagesAsPdf(listOf(bytes), suggestedName).toMailResult()
}

private fun ImportOutcome.toMailResult(): MailImportResult =
    when (this) {
        is ImportOutcome.Imported -> MailImportResult.Imported(docId)
        is ImportOutcome.Duplicate -> MailImportResult.Duplicate
        is ImportOutcome.Failed -> MailImportResult.Failed
    }
