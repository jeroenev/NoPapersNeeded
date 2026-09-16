package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.core.sync.RcloneOption
import dev.npn.core.sync.RcloneProvider
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_choose
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_connect
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_create
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_current
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_empty
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_new
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_root
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_up
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_use
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_form_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_form_submit
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers_fewer
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers_more
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_retry
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_loading
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_no_providers
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_open
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_waiting
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_with
import org.jetbrains.compose.resources.stringResource

/** The folder offered by default when browsing a remote's root. */
private const val SUGGESTED_FOLDER = "NoPapersNeeded"

/** The gap between the controls of one wizard step. */
private val StepSpacing = 8.dp

/**
 * The in-app cloud setup: pick a provider, sign in, choose the folder. It renders whichever step
 * [state] holds and only reports the user's intent back; the protocol itself lives in
 * [CloudSetupController], so a provider whose form differs never needs a change here.
 */
@Composable
internal fun CloudSetupWizard(
    state: CloudSetupState?,
    setup: CloudSetupActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        when (state) {
            null -> {
                HelpText(stringResource(Res.string.common_settings_cloud_setup_help))
                SettingsActionRow(
                    label = stringResource(Res.string.common_settings_cloud_connect),
                    onClick = setup::start,
                )
            }

            CloudSetupState.Loading -> {
                HelpText(stringResource(Res.string.common_settings_cloud_setup_loading))
                CircularProgressIndicator()
            }

            is CloudSetupState.Providers -> {
                ProviderPicker(state.providers, setup)
            }

            is CloudSetupState.Form -> {
                SetupForm(state, setup)
            }

            is CloudSetupState.SigningIn -> {
                BrowserSignIn(state, onCancel = setup::cancel)
            }

            is CloudSetupState.Folders -> {
                FolderPicker(state, setup)
            }

            is CloudSetupState.Failed -> {
                HelpText(stringResource(Res.string.common_settings_cloud_setup_failed))
                HelpText(state.message)
                SettingsActionRow(
                    label = stringResource(Res.string.common_settings_cloud_retry),
                    onClick = setup::start,
                )
                SettingsActionRow(
                    label = stringResource(Res.string.common_settings_cloud_cancel),
                    onClick = setup::cancel,
                )
            }
        }
    }
}

@Composable
private fun ProviderPicker(
    providers: List<RcloneProvider>,
    setup: CloudSetupActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        if (providers.isEmpty()) {
            HelpText(stringResource(Res.string.common_settings_cloud_setup_no_providers))
        } else {
            HelpText(stringResource(Res.string.common_settings_cloud_providers))
            val (leading, advanced) = providers.partition { !it.advanced }
            for (provider in leading) ProviderButton(provider, setup)
            if (advanced.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                TextButton(onClick = { expanded = !expanded }) {
                    val label =
                        if (expanded) {
                            Res.string.common_settings_cloud_providers_fewer
                        } else {
                            Res.string.common_settings_cloud_providers_more
                        }
                    Text(text = stringResource(label))
                }
                if (expanded) {
                    for (provider in advanced) ProviderButton(provider, setup)
                }
            }
        }
        SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_cancel), onClick = setup::cancel)
    }
}

@Composable
private fun ProviderButton(
    provider: RcloneProvider,
    setup: CloudSetupActions,
) {
    OutlinedButton(onClick = { setup.chooseProvider(provider) }, modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                text =
                    if (provider.browserSignIn) {
                        stringResource(Res.string.common_settings_cloud_sign_in_with, provider.name)
                    } else {
                        provider.name
                    },
                style = MaterialTheme.typography.bodyLarge,
            )
            HelpText(provider.description)
        }
    }
}

@Composable
private fun SetupForm(
    state: CloudSetupState.Form,
    setup: CloudSetupActions,
) {
    val question = state.question
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        HelpText(
            question?.option?.let { it.help.ifBlank { it.name } }
                ?: stringResource(Res.string.common_settings_cloud_form_help, state.provider.name),
        )
        val options = question?.let { listOf(it.option) } ?: state.provider.options
        for (option in options) {
            OptionField(
                option = option,
                value = state.values[option.name].orEmpty(),
                onChange = { setup.setValue(option.name, it) },
            )
        }
        state.error?.let { HelpText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(StepSpacing)) {
            Button(onClick = setup::submit, enabled = !state.busy) {
                Text(text = stringResource(Res.string.common_settings_cloud_form_submit))
            }
            OutlinedButton(onClick = setup::cancel) {
                Text(text = stringResource(Res.string.common_settings_cloud_cancel))
            }
        }
    }
}

/** One option of a provider form or question: a hidden field, a picker, or a plain text field. */
@Composable
private fun OptionField(
    option: RcloneOption,
    value: String,
    onChange: (String) -> Unit,
) {
    if (option.examples.isNotEmpty() && !option.isPassword) {
        Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
            Dropdown(
                selected = value.ifBlank { stringResource(Res.string.common_settings_cloud_choose) },
                options = option.examples,
                optionLabel = { it.help.ifBlank { it.value } },
                onSelect = { onChange(it.value) },
                label = option.name,
            )
            HelpText(option.help.ifBlank { option.name })
        }
    } else {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(text = option.name) },
            supportingText = { Text(text = option.help.ifBlank { option.name }) },
            visualTransformation = if (option.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BrowserSignIn(
    state: CloudSetupState.SigningIn,
    onCancel: () -> Unit,
) {
    val url = state.authUrl
    val uriHandler = LocalUriHandler.current
    // Open the sign-in page once, when the engine first publishes it.
    LaunchedEffect(url) {
        if (url != null) runCatching { uriHandler.openUri(url) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        state.providerName?.let { provider ->
            Text(
                text = stringResource(Res.string.common_settings_cloud_sign_in_with, provider),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        HelpText(stringResource(Res.string.common_settings_cloud_sign_in_help))
        if (url == null) {
            Text(
                text = stringResource(Res.string.common_settings_cloud_sign_in_waiting),
                style = MaterialTheme.typography.bodyMedium,
            )
            CircularProgressIndicator()
        } else {
            // Selectable so the link can be copied even where no browser can be launched.
            SelectionContainer { Text(text = url, style = MaterialTheme.typography.bodySmall) }
            SettingsActionRow(
                label = stringResource(Res.string.common_settings_cloud_sign_in_open),
                onClick = { runCatching { uriHandler.openUri(url) } },
            )
        }
        SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_sign_in_cancel), onClick = onCancel)
    }
}

@Composable
private fun FolderPicker(
    state: CloudSetupState.Folders,
    setup: CloudSetupActions,
) {
    val root = stringResource(Res.string.common_settings_cloud_folder_root)
    var newFolder by remember(state.remote, state.path) {
        mutableStateOf(if (state.path.isEmpty()) SUGGESTED_FOLDER else "")
    }
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        HelpText(stringResource(Res.string.common_settings_cloud_folder_help))
        Text(
            text = stringResource(Res.string.common_settings_cloud_folder_current, state.path.ifEmpty { root }),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.path.isNotEmpty()) {
            SettingsActionRow(
                label = stringResource(Res.string.common_settings_cloud_folder_up),
                onClick = setup::openFolderUp,
            )
        }
        if (state.busy) {
            CircularProgressIndicator()
        } else {
            for (folder in state.folders) FolderButton(folder, onOpen = { setup.openFolder(folder) })
            if (state.folders.isEmpty()) HelpText(stringResource(Res.string.common_settings_cloud_folder_empty))
        }
        state.error?.let { HelpText(it) }
        OutlinedTextField(
            value = newFolder,
            onValueChange = { newFolder = it },
            label = { Text(text = stringResource(Res.string.common_settings_cloud_folder_new)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(StepSpacing)) {
            OutlinedButton(
                onClick = { setup.createFolder(newFolder) },
                enabled = !state.busy && newFolder.isNotBlank(),
            ) {
                Text(text = stringResource(Res.string.common_settings_cloud_folder_create))
            }
            Button(onClick = setup::useFolder, enabled = !state.busy) {
                Text(text = stringResource(Res.string.common_settings_cloud_folder_use))
            }
        }
        SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_cancel), onClick = setup::cancel)
    }
}

@Composable
private fun FolderButton(
    name: String,
    onOpen: () -> Unit,
) {
    OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(StepSpacing)) {
            Icon(Icons.Outlined.Folder, contentDescription = null)
            Text(text = name)
        }
    }
}
