package dev.npn.app.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailAccountJson
import dev.npn.core.mail.MailDeviceChoices

/**
 * The pane's draft of the account, apart from the saved one until Save. [seed] is the saved account
 * the draft started from: when another device changes the account, each field the user has not
 * changed here follows it, while a field they changed keeps their edit. Saving then writes only
 * what differs from the saved account, so another device's change to another field survives. The
 * same for this device's own choices.
 */
@Stable
internal class MailForm(
    seed: MailAccount,
    savedChoices: MailDeviceChoices,
    draft: MailAccount = seed,
    choices: MailDeviceChoices = savedChoices,
    intervalText: String = seed.pollIntervalMinutes.toString(),
    portText: String = seed.port.toString(),
) {
    var seed by mutableStateOf(seed)
        private set
    var savedChoices by mutableStateOf(savedChoices)
        private set
    var draft by mutableStateOf(draft)
    var choices by mutableStateOf(choices)
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

    /** Take in the saved account and choices, field by field, but for the fields the user changed here. */
    fun follow(
        account: MailAccount,
        saved: MailDeviceChoices,
    ) {
        if (account != seed) {
            // Untouched is judged by what Save would write, not the draft's own fields: the port and
            // interval live in their text fields, and saving normalizes.
            val mine = whenSaved()
            val base = seed.normalized()
            // A port or interval being typed (not a number yet) is an edit too.
            take(
                merged(base, mine, account),
                portFollows = port != null && mine.port == base.port,
                intervalFollows = interval != null && mine.pollIntervalMinutes == base.pollIntervalMinutes,
            )
            seed = account
        }
        if (saved != savedChoices) {
            val mine = choices
            val base = savedChoices
            choices =
                MailDeviceChoices(
                    enabledHere = if (mine.enabledHere == base.enabledHere) saved.enabledHere else mine.enabledHere,
                    allowPlain = if (mine.allowPlain == base.allowPlain) saved.allowPlain else mine.allowPlain,
                )
            savedChoices = saved
        }
    }

    /** A save of [account] went through: the draft is that account again, untouched. */
    fun saved(
        account: MailAccount,
        device: MailDeviceChoices,
    ) {
        take(account, portFollows = true, intervalFollows = true)
        seed = account
        choices = device
        savedChoices = device
    }

    private fun take(
        account: MailAccount,
        portFollows: Boolean,
        intervalFollows: Boolean,
    ) {
        draft = account
        if (intervalFollows) intervalText = account.pollIntervalMinutes.toString()
        if (portFollows) portText = account.port.toString()
    }

    companion object {
        /** [theirs] in each field [mine] left as [base] had it; [mine] where the user changed it. */
        private fun merged(
            base: MailAccount,
            mine: MailAccount,
            theirs: MailAccount,
        ): MailAccount {
            fun <T> pick(field: (MailAccount) -> T): T = if (field(mine) == field(base)) field(theirs) else field(mine)
            return MailAccount(
                host = pick { it.host },
                port = pick { it.port },
                security = pick { it.security },
                username = pick { it.username },
                folder = pick { it.folder },
                pollIntervalMinutes = pick { it.pollIntervalMinutes },
                filters = pick { it.filters },
                postAction = pick { it.postAction },
                moveToFolder = pick { it.moveToFolder },
                saveEmailAsPdf = pick { it.saveEmailAsPdf },
                startFrom = pick { it.startFrom },
            )
        }

        val Saver: Saver<MailForm, Any> =
            listSaver(
                save = {
                    listOf(
                        MailAccountJson.encode(it.seed),
                        it.savedChoices.enabledHere,
                        it.savedChoices.allowPlain,
                        MailAccountJson.encode(it.draft),
                        it.choices.enabledHere,
                        it.choices.allowPlain,
                        it.intervalText,
                        it.portText,
                    )
                },
                restore = {
                    MailForm(
                        seed = MailAccountJson.decode(it[0] as String),
                        savedChoices = MailDeviceChoices(it[1] as Boolean, it[2] as Boolean),
                        draft = MailAccountJson.decode(it[3] as String),
                        choices = MailDeviceChoices(it[4] as Boolean, it[5] as Boolean),
                        intervalText = it[6] as String,
                        portText = it[7] as String,
                    )
                },
            )
    }
}
