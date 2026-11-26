package dev.npn.core.app

import dev.npn.core.index.JournalCoordinator
import dev.npn.core.journal.PendingOp
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailAccountJson
import dev.npn.core.mail.MailAccountStore
import dev.npn.core.mail.MailCheckReport
import dev.npn.core.mail.MailDeviceChoices
import dev.npn.core.mail.MailFilter
import dev.npn.core.mail.MailImportResult
import dev.npn.core.mail.MailSaveOutcome
import dev.npn.core.mail.MailState
import dev.npn.core.mail.MailTestOutcome
import dev.npn.core.util.NpnJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * Importing attachments from one IMAP account, desktop only. The account itself is library data in
 * the journal; the password lives in the OS secret store, and the read cursor and this device's own
 * choices in `device.db`.
 *
 * A check never throws for an expected failure: a missing password, an unreachable server or a
 * refused post-action come back in the [MailCheckReport] as a [dev.npn.core.mail.MailFailureReason].
 * Saving an account can fail to reach the secret store, which is its own typed outcome.
 */
interface MailFacade {
    /** The one account, whether a password is saved, and the last check's state. */
    val state: StateFlow<MailState>

    /**
     * Save the account, and the password when [password] is non-blank; a null or blank password
     * leaves the saved one alone ([clearPassword] removes it). The account is saved before a
     * secret-store failure is reported, so it remains visible and can be corrected. [device] is
     * what this device decides for itself: never synced.
     */
    suspend fun saveAccount(
        account: MailAccount,
        password: String?,
        device: MailDeviceChoices,
    ): MailSaveOutcome

    /** Remove the saved password from the OS secret store; the account stays configured otherwise. */
    suspend fun clearPassword(): MailSaveOutcome

    /**
     * Look in the OS secret store whether this device has the account's password, for the settings
     * pane, which asks when it opens. Nothing else reads the store while this device does not check
     * the account, so a locked keyring is not unlocked at every start for a feature that is off.
     */
    suspend fun lookUpPassword()

    /**
     * Connect with the account (and the password saved for its server and username unless
     * [password] is given) and change nothing. [allowPlain] is this device's opt-in to an
     * unencrypted login, as the form holds it.
     */
    suspend fun testConnection(
        account: MailAccount,
        password: String?,
        allowPlain: Boolean,
    ): MailTestOutcome

    /**
     * Check now, regardless of the enabled switch, the poll interval or a refused password, and
     * read until the folder is exhausted. The check outlives a caller that stops waiting;
     * [cancelCheck] stops it.
     */
    suspend fun checkNow(): MailCheckReport

    /**
     * Check now, first reading again the emails earlier checks passed over and left in the mailbox
     * ([dev.npn.core.mail.MailState.passedOver]).
     */
    suspend fun retryPassedOver(): MailCheckReport

    /**
     * Ask the running check, a poll or a Check now, to stop after the message in hand. Everything
     * it handled stays handled and the cursor keeps its place, so the next check goes on from
     * there. Does nothing while no check runs.
     */
    fun cancelCheck()

    /** The documents each check imported, one event per check that imported any. */
    val importedDocuments: Flow<List<String>>
}

/**
 * [MailAccountStore] over the journal's synced settings, one per field ([MailAccountSettings]);
 * writes run through [SessionLifetime.owned].
 */
internal class JournalMailAccount(
    private val coordinator: JournalCoordinator,
    private val lifetime: SessionLifetime,
) : MailAccountStore {
    override val account: Flow<MailAccount> =
        coordinator
            .settings()
            .map { settings -> MailAccountSettings.decode { settings[it] } }
            .distinctUntilChanged()

    override fun current(): MailAccount = MailAccountSettings.decode(coordinator::setting)

    override suspend fun save(account: MailAccount) {
        lifetime.owned {
            coordinator.commitBuilt { state ->
                MailAccountSettings
                    .changes(state::effectiveSetting, account.normalized())
                    .map { (key, value) -> PendingOp.settingSet(key, value) }
            }
        }
    }
}

/**
 * The account as one synced setting per field. A field without its own setting reads from the
 * single JSON value earlier builds wrote ([LibrarySettingKeys.MAIL_ACCOUNT]); a value this build
 * cannot read (a choice a later build added) reads as the field's default, and is written back
 * only when the user changes that field here.
 */
internal object MailAccountSettings {
    private class Field(
        val key: String,
        val read: (stored: String, defaults: MailAccount) -> MailAccount,
        val write: (MailAccount) -> String,
    )

    private val defaults = MailAccount()

    private val fields: List<Field> =
        listOf(
            Field(LibrarySettingKeys.MAIL_HOST, { v, a -> a.copy(host = v) }, { it.host }),
            Field(
                LibrarySettingKeys.MAIL_PORT,
                { v, a -> a.copy(port = v.toIntOrNull() ?: defaults.port) },
                { it.port.toString() },
            ),
            Field(
                LibrarySettingKeys.MAIL_SECURITY,
                { v, a -> a.copy(security = enumOr(v, defaults.security)) },
                { it.security.name },
            ),
            Field(LibrarySettingKeys.MAIL_USERNAME, { v, a -> a.copy(username = v) }, { it.username }),
            Field(LibrarySettingKeys.MAIL_FOLDER, { v, a -> a.copy(folder = v) }, { it.folder }),
            Field(
                LibrarySettingKeys.MAIL_POLL_MINUTES,
                { v, a -> a.copy(pollIntervalMinutes = v.toIntOrNull() ?: defaults.pollIntervalMinutes) },
                { it.pollIntervalMinutes.toString() },
            ),
            Field(
                LibrarySettingKeys.MAIL_FILTERS,
                { v, a -> a.copy(filters = filters(v)) },
                { NpnJson.Persisted.encodeToString(FILTERS, it.filters) },
            ),
            Field(
                LibrarySettingKeys.MAIL_POST_ACTION,
                { v, a -> a.copy(postAction = enumOr(v, defaults.postAction)) },
                { it.postAction.name },
            ),
            Field(LibrarySettingKeys.MAIL_MOVE_TO, { v, a -> a.copy(moveToFolder = v) }, { it.moveToFolder }),
            Field(
                LibrarySettingKeys.MAIL_SAVE_PDF,
                { v, a -> a.copy(saveEmailAsPdf = v.toBooleanStrictOrNull() ?: defaults.saveEmailAsPdf) },
                { it.saveEmailAsPdf.toString() },
            ),
            Field(
                LibrarySettingKeys.MAIL_START_FROM,
                { v, a -> a.copy(startFrom = enumOr(v, defaults.startFrom)) },
                { it.startFrom.name },
            ),
        )

    private val FILTERS = ListSerializer(MailFilter.serializer())

    /** The account [setting] holds: each field from its own setting, else from the earlier JSON. */
    fun decode(setting: (String) -> String?): MailAccount =
        fields
            .fold(MailAccountJson.decode(setting(LibrarySettingKeys.MAIL_ACCOUNT))) { account, field ->
                setting(field.key)?.let { field.read(it, account) } ?: account
            }.normalized()

    /**
     * The settings to write for [next] over what [setting] holds now: the fields whose value it
     * changes, and every field while any is still read from the earlier JSON, so that from the
     * first save on the account no longer depends on it. An empty text is written as such: a
     * cleared setting would read from the earlier JSON again.
     */
    fun changes(
        setting: (String) -> String?,
        next: MailAccount,
    ): Map<String, String> {
        val current = decode(setting)
        val complete = fields.all { setting(it.key) != null }
        return fields
            .filter { !complete || it.write(current) != it.write(next) }
            .associate { it.key to it.write(next) }
    }

    private fun filters(stored: String): List<MailFilter> =
        runCatching { NpnJson.Persisted.decodeFromString(FILTERS, stored) }.getOrElse { emptyList() }

    private inline fun <reified E : Enum<E>> enumOr(
        stored: String,
        otherwise: E,
    ): E = enumValues<E>().firstOrNull { it.name == stored } ?: otherwise
}

/** An import outcome in the terms the email report counts. */
internal fun ImportOutcome.toMailResult(): MailImportResult =
    when (this) {
        is ImportOutcome.Imported -> MailImportResult.Imported(docId)
        is ImportOutcome.Duplicate -> MailImportResult.Duplicate
        is ImportOutcome.Failed -> MailImportResult.Failed
    }
