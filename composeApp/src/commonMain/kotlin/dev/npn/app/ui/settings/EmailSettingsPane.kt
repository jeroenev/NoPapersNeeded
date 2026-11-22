package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailAccountJson
import dev.npn.core.mail.MailFilter
import dev.npn.core.mail.MailPostAction
import dev.npn.core.mail.MailSecurity
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.email_account
import no_papers_needed.composeapp.generated.resources.email_add_filter
import no_papers_needed.composeapp.generated.resources.email_allow_plain
import no_papers_needed.composeapp.generated.resources.email_allow_plain_help
import no_papers_needed.composeapp.generated.resources.email_check_now
import no_papers_needed.composeapp.generated.resources.email_checking
import no_papers_needed.composeapp.generated.resources.email_clear_password
import no_papers_needed.composeapp.generated.resources.email_enabled
import no_papers_needed.composeapp.generated.resources.email_enabled_help
import no_papers_needed.composeapp.generated.resources.email_filter_attachment
import no_papers_needed.composeapp.generated.resources.email_filter_from
import no_papers_needed.composeapp.generated.resources.email_filter_subject
import no_papers_needed.composeapp.generated.resources.email_filters
import no_papers_needed.composeapp.generated.resources.email_filters_help
import no_papers_needed.composeapp.generated.resources.email_folder
import no_papers_needed.composeapp.generated.resources.email_folder_help
import no_papers_needed.composeapp.generated.resources.email_host
import no_papers_needed.composeapp.generated.resources.email_interval
import no_papers_needed.composeapp.generated.resources.email_interval_help
import no_papers_needed.composeapp.generated.resources.email_intro
import no_papers_needed.composeapp.generated.resources.email_last_check
import no_papers_needed.composeapp.generated.resources.email_last_error
import no_papers_needed.composeapp.generated.resources.email_move_folder
import no_papers_needed.composeapp.generated.resources.email_never_checked
import no_papers_needed.composeapp.generated.resources.email_password
import no_papers_needed.composeapp.generated.resources.email_password_help
import no_papers_needed.composeapp.generated.resources.email_password_saved
import no_papers_needed.composeapp.generated.resources.email_port
import no_papers_needed.composeapp.generated.resources.email_post_action
import no_papers_needed.composeapp.generated.resources.email_post_delete
import no_papers_needed.composeapp.generated.resources.email_post_keep
import no_papers_needed.composeapp.generated.resources.email_post_mark_read
import no_papers_needed.composeapp.generated.resources.email_post_move
import no_papers_needed.composeapp.generated.resources.email_remove_filter
import no_papers_needed.composeapp.generated.resources.email_save
import no_papers_needed.composeapp.generated.resources.email_save_pdf
import no_papers_needed.composeapp.generated.resources.email_save_pdf_help
import no_papers_needed.composeapp.generated.resources.email_security
import no_papers_needed.composeapp.generated.resources.email_security_plain
import no_papers_needed.composeapp.generated.resources.email_security_ssl
import no_papers_needed.composeapp.generated.resources.email_security_starttls
import no_papers_needed.composeapp.generated.resources.email_test
import no_papers_needed.composeapp.generated.resources.email_username
import org.jetbrains.compose.resources.stringResource

private val MailAccountSaver =
    Saver<MailAccount, String>(
        save = { MailAccountJson.encode(it) },
        restore = { MailAccountJson.decode(it) },
    )

/** Section headings' labels are the accessors (the section title), so search finds them by name. */
@Composable
internal fun EmailSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val mail = state.mail
    // The form is one draft: every field is written when Save is pressed, not once per keystroke
    // (a journal event per character would be permanent). It survives recreation with the screen.
    var draft by rememberSaveable(mail.account, stateSaver = MailAccountSaver) { mutableStateOf(mail.account) }
    var password by remember { mutableStateOf("") }
    var intervalText by rememberSaveable(mail.account.pollIntervalMinutes) {
        mutableStateOf(mail.account.pollIntervalMinutes.toString())
    }
    val interval = intervalText.toIntOrNull()
    val intervalValid = interval != null && interval in MailAccount.MIN_POLL_MINUTES..MailAccount.MAX_POLL_MINUTES

    fun whenSaved(): MailAccount =
        draft
            .copy(pollIntervalMinutes = interval ?: draft.pollIntervalMinutes)
            .normalized()

    // One entry emitter, so the pane composes its sections as one unit and the Compose detekt
    // ruleset can see the pane's single top-level content source.
    SettingsSection(title = null) {
        SettingsSection(title = stringResource(Res.string.email_account)) {
            HelpText(stringResource(Res.string.email_intro))
            SwitchRow(
                label = stringResource(Res.string.email_enabled),
                help = stringResource(Res.string.email_enabled_help),
                checked = draft.enabled,
                onCheckedChange = { draft = draft.copy(enabled = it) },
            )
            TextField(
                value = draft.host,
                label = stringResource(Res.string.email_host),
                onChange = { draft = draft.copy(host = it) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(
                    value = draft.port.toString(),
                    label = stringResource(Res.string.email_port),
                    onChange = { text -> text.toIntOrNull()?.let { draft = draft.copy(port = it) } },
                    number = true,
                    modifier = Modifier.weight(1f),
                )
                Dropdown(
                    selected = draft.security.label(),
                    options = MailSecurity.entries,
                    optionLabel = { it.label() },
                    onSelect = { security ->
                        val oldDefault = draft.security.defaultPort()
                        val port = if (draft.port == oldDefault) security.defaultPort() else draft.port
                        draft = draft.copy(security = security, port = port)
                    },
                    label = stringResource(Res.string.email_security),
                    modifier = Modifier.weight(2f),
                )
            }
            TextField(
                value = draft.username,
                label = stringResource(Res.string.email_username),
                onChange = { draft = draft.copy(username = it) },
            )
            TextField(
                value = password,
                label = stringResource(Res.string.email_password),
                onChange = { password = it },
                password = true,
                placeholder = stringResource(Res.string.email_password_saved).takeIf { mail.hasPassword },
            )
            HelpText(stringResource(Res.string.email_password_help))
            TextField(
                value = draft.folder,
                label = stringResource(Res.string.email_folder),
                onChange = { draft = draft.copy(folder = it) },
            )
            HelpText(stringResource(Res.string.email_folder_help))
            TextField(
                value = intervalText,
                label = stringResource(Res.string.email_interval),
                onChange = { intervalText = it.filter(Char::isDigit) },
                number = true,
            )
            HelpText(stringResource(Res.string.email_interval_help))
            if (draft.security == MailSecurity.PLAIN) {
                SwitchRow(
                    label = stringResource(Res.string.email_allow_plain),
                    help = stringResource(Res.string.email_allow_plain_help),
                    checked = draft.allowPlain,
                    onCheckedChange = { draft = draft.copy(allowPlain = it) },
                )
            }
        }

        SettingsSection(title = stringResource(Res.string.email_filters)) {
            HelpText(stringResource(Res.string.email_filters_help))
            draft.filters.forEachIndexed { index, filter ->
                FilterRow(
                    filter = filter,
                    onChange = { changed ->
                        draft = draft.copy(filters = draft.filters.toMutableList().also { it[index] = changed })
                    },
                    onRemove = { draft = draft.copy(filters = draft.filters.filterIndexed { i, _ -> i != index }) },
                )
            }
            OutlinedButton(onClick = { draft = draft.copy(filters = draft.filters + MailFilter()) }) {
                Text(stringResource(Res.string.email_add_filter))
            }
        }

        SettingsSection(title = stringResource(Res.string.email_post_action)) {
            Dropdown(
                selected = draft.postAction.label(),
                options = MailPostAction.entries,
                optionLabel = { it.label() },
                onSelect = { draft = draft.copy(postAction = it) },
                label = stringResource(Res.string.email_post_action),
            )
            if (draft.postAction == MailPostAction.MOVE) {
                TextField(
                    value = draft.moveToFolder,
                    label = stringResource(Res.string.email_move_folder),
                    onChange = { draft = draft.copy(moveToFolder = it) },
                )
            }
            SwitchRow(
                label = stringResource(Res.string.email_save_pdf),
                help = stringResource(Res.string.email_save_pdf_help),
                checked = draft.saveEmailAsPdf,
                onCheckedChange = { draft = draft.copy(saveEmailAsPdf = it) },
                enabled = mail.pdfAvailable,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = { actions.saveMailAccount(whenSaved(), password.ifBlank { null }) },
                enabled = intervalValid,
            ) { Text(stringResource(Res.string.email_save)) }
            OutlinedButton(
                onClick = { actions.testMailConnection(whenSaved(), password.ifBlank { null }) },
                enabled = intervalValid,
            ) { Text(stringResource(Res.string.email_test)) }
            OutlinedButton(
                onClick = actions::checkMailNow,
                enabled = !mail.checking && mail.transportAvailable,
            ) { Text(stringResource(Res.string.email_check_now)) }
            if (mail.hasPassword) {
                OutlinedButton(onClick = actions::clearMailPassword) {
                    Text(stringResource(Res.string.email_clear_password))
                }
            }
        }

        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mail.checking) {
                Text(stringResource(Res.string.email_checking), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    text =
                        mail.lastCheckedAtMillis?.let {
                            stringResource(Res.string.email_last_check, formatEpochDateTime(it))
                        } ?: stringResource(Res.string.email_never_checked),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            mail.lastError?.let {
                Text(
                    text = stringResource(Res.string.email_last_error, it.text()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            mail.credentialProblem?.let {
                Text(
                    text = stringResource(Res.string.email_last_error, stringResource(it.textRes())),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun FilterRow(
    filter: MailFilter,
    onChange: (MailFilter) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextField(
            value = filter.from.orEmpty(),
            label = stringResource(Res.string.email_filter_from),
            onChange = { onChange(filter.copy(from = it)) },
        )
        TextField(
            value = filter.subject.orEmpty(),
            label = stringResource(Res.string.email_filter_subject),
            onChange = { onChange(filter.copy(subject = it)) },
        )
        TextField(
            value = filter.attachment.orEmpty(),
            label = stringResource(Res.string.email_filter_attachment),
            onChange = { onChange(filter.copy(attachment = it)) },
        )
        TextButton(onClick = onRemove) { Text(stringResource(Res.string.email_remove_filter)) }
    }
}

@Composable
private fun TextField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    password: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text),
    )
}

@Composable
private fun MailSecurity.label(): String =
    stringResource(
        when (this) {
            MailSecurity.SSL -> Res.string.email_security_ssl
            MailSecurity.STARTTLS -> Res.string.email_security_starttls
            MailSecurity.PLAIN -> Res.string.email_security_plain
        },
    )

private fun MailSecurity.defaultPort(): Int =
    when (this) {
        MailSecurity.SSL -> MailAccount.DEFAULT_IMAPS_PORT
        MailSecurity.STARTTLS, MailSecurity.PLAIN -> MailAccount.DEFAULT_STARTTLS_PORT
    }

@Composable
private fun MailPostAction.label(): String =
    stringResource(
        when (this) {
            MailPostAction.KEEP -> Res.string.email_post_keep
            MailPostAction.MARK_READ -> Res.string.email_post_mark_read
            MailPostAction.MOVE -> Res.string.email_post_move
            MailPostAction.DELETE -> Res.string.email_post_delete
        },
    )
