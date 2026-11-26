package dev.npn.core.mail

import dev.npn.core.util.NpnLog
import dev.npn.core.util.info
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.warn

/**
 * This library's mail password in the OS secret store, under [keyScope] and the account's server and
 * username ([MailAccount.accountKey]): a changed port or security keeps it, a changed server or
 * username is another entry, and two libraries on one desktop never share or remove each other's.
 *
 * A password an earlier build saved under [MailAccount.legacyAccountKey] (the server, username and
 * port, shared by every library of the desktop) moves to this library's entry once, on the first
 * read, and only into an empty one.
 */
internal class MailPasswords(
    private val credentials: MailCredentialStore,
    private val cursors: MailCursorStore,
    private val keyScope: String,
    private val log: NpnLog,
) {
    val available: Boolean get() = credentials.available

    /** [account]'s password; a missing one is a success holding null or blank. */
    suspend fun load(account: MailAccount): MailSecretResult<String?> {
        adoptLegacy(account)
        return credentials.load(key(account))
    }

    /** Store [password] for [account]; the failure, or null when it was stored. */
    suspend fun store(
        account: MailAccount,
        password: String,
    ): MailSecretFailure? = (credentials.store(key(account), password) as? MailSecretResult.Failed)?.reason

    suspend fun clear(account: MailAccount): MailSecretResult<Unit> = credentials.clear(key(account))

    /** Remove [account]'s password, which nothing reads any more; a failure is only logged. */
    suspend fun forget(account: MailAccount) = clearQuietly(key(account))

    private fun key(account: MailAccount): String = "$keyScope/${account.accountKey}"

    /** Move the earlier build's password to this library's entry, once; a keyring failure retries later. */
    private suspend fun adoptLegacy(account: MailAccount) {
        if (cursors.legacyPasswordMoved()) return
        val present =
            when (val loaded = credentials.load(key(account))) {
                is MailSecretResult.Failed -> return
                is MailSecretResult.Success -> !loaded.value.isNullOrBlank()
            }
        if (!present) {
            val old =
                when (val loaded = credentials.load(account.legacyAccountKey)) {
                    is MailSecretResult.Failed -> return
                    is MailSecretResult.Success -> loaded.value?.takeIf(String::isNotBlank)
                }
            if (old != null) {
                if (store(account, old) != null) return
                clearQuietly(account.legacyAccountKey)
                log.info("Moved the email password to this library's keyring entry")
            }
        }
        cursors.setLegacyPasswordMoved()
    }

    private suspend fun clearQuietly(key: String) {
        runCatchingCancellable { credentials.clear(key) }.onFailure {
            log.warn("Removing an old email password failed", it)
        }
    }
}
