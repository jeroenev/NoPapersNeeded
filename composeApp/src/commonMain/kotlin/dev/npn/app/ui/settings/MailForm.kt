package dev.npn.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailAccountJson

/**
 * The pane's draft of the account, apart from the saved one until Save. [seed] is the saved account
 * the draft started from: when another device changes the account, an untouched draft follows it,
 * while one the user is editing keeps their edits. The same for this device's own switch.
 */
@Stable
internal class MailForm(
    seed: MailAccount,
    savedEnabledHere: Boolean,
    draft: MailAccount = seed,
    enabledHere: Boolean = savedEnabledHere,
    intervalText: String = seed.pollIntervalMinutes.toString(),
    portText: String = seed.port.toString(),
) {
    var seed by mutableStateOf(seed)
        private set
    var savedEnabledHere by mutableStateOf(savedEnabledHere)
        private set
    var draft by mutableStateOf(draft)
    var enabledHere by mutableStateOf(enabledHere)
    var intervalText by mutableStateOf(intervalText)
    var portText by mutableStateOf(portText)

    val interval: Int?
        get() = intervalText.toIntOrNull()?.takeIf { it in MailAccount.MIN_POLL_MINUTES..MailAccount.MAX_POLL_MINUTES }

    val port: Int? get() = portText.toIntOrNull()?.takeIf { it in 1..MailAccount.MAX_PORT }

    val valid: Boolean get() = interval != null && port != null

    fun whenSaved(): MailAccount =
        draft
            .copy(
                pollIntervalMinutes = interval ?: draft.pollIntervalMinutes,
                port = port ?: draft.port,
            ).normalized()

    /** Take in the saved account and switch, unless the user has changed them here since. */
    fun follow(
        account: MailAccount,
        enabled: Boolean,
    ) {
        if (account != seed) {
            // Untouched is judged by what Save would write, not the draft's own fields: the port and
            // interval live in their text fields, and saving normalizes, so a draft just saved would
            // otherwise count as edited for good and stop following other devices.
            if (whenSaved() == seed.normalized()) take(account)
            seed = account
        }
        if (enabled != savedEnabledHere) {
            if (enabledHere == savedEnabledHere) enabledHere = enabled
            savedEnabledHere = enabled
        }
    }

    /** A save of [account] went through: the draft is that account again, untouched. */
    fun saved(
        account: MailAccount,
        enabled: Boolean,
    ) {
        take(account)
        seed = account
        enabledHere = enabled
        savedEnabledHere = enabled
    }

    private fun take(account: MailAccount) {
        draft = account
        intervalText = account.pollIntervalMinutes.toString()
        portText = account.port.toString()
    }

    companion object {
        val Saver: Saver<MailForm, Any> =
            listSaver(
                save = {
                    listOf(
                        MailAccountJson.encode(it.seed),
                        it.savedEnabledHere,
                        MailAccountJson.encode(it.draft),
                        it.enabledHere,
                        it.intervalText,
                        it.portText,
                    )
                },
                restore = {
                    MailForm(
                        seed = MailAccountJson.decode(it[0] as String),
                        savedEnabledHere = it[1] as Boolean,
                        draft = MailAccountJson.decode(it[2] as String),
                        enabledHere = it[3] as Boolean,
                        intervalText = it[4] as String,
                        portText = it[5] as String,
                    )
                },
            )
    }
}
