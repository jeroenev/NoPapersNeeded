package dev.npn.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.ExpandIcon
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.expander
import dev.npn.app.ui.common.focusRing
import dev.npn.core.sync.RcloneOption
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupFailure
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_choose
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_connect
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_check_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_create
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_create_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_current
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_empty
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_list_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_new
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_other_files
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_root
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_root_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_up
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_folder_use
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_form_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_form_submit
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_provider_browser
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers_fewer
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_providers_more
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_retry
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_interrupted
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_loading
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_no_providers
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_unexpected
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_open
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_waiting
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sign_in_with
import no_papers_needed.composeapp.generated.resources.onboarding_cloud_more_providers_later
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_address
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_address_help
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_help
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_kind
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_nextcloud
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_other
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_owncloud
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_password
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_password_help
import no_papers_needed.composeapp.generated.resources.onboarding_webdav_user
import org.jetbrains.compose.resources.stringResource

/** The folder offered by default when browsing a remote's root. */
private const val SUGGESTED_FOLDER = "NoPapersNeeded"

/** The gap between the controls of one wizard step. */
private val StepSpacing = 8.dp

/**
 * The in-app cloud setup: pick a provider, sign in, choose the folder. It renders whichever step
 * [state] holds and only reports the user's intent back; the protocol itself lives in
 * [CloudSetupController], so a provider whose form differs never needs a change here, apart from
 * WebDAV, which gets a form in the app's own words.
 *
 * The providers set up in the user's own terms ([plainSetup]) come first. The others name rclone's
 * options, so they wait behind "more providers" where [moreProviders] allows them (settings), and
 * are left out on first run, which says where to find them instead.
 */
@Composable
internal fun CloudSetupWizard(
    state: CloudSetupState?,
    setup: CloudSetupActions,
    moreProviders: Boolean = true,
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
                ProviderPicker(state.providers, setup, moreProviders)
            }

            is CloudSetupState.Form -> {
                if (state.provider.type == WEBDAV_TYPE && state.question == null) {
                    WebDavForm(state, setup)
                } else {
                    SetupForm(state, setup)
                }
            }

            is CloudSetupState.SigningIn -> {
                BrowserSignIn(state, onCancel = setup::cancel)
            }

            is CloudSetupState.Folders -> {
                FolderPicker(state, setup)
            }

            is CloudSetupState.Failed -> {
                HelpText(setupFailureText(state.reason, state.detail))
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
    moreProviders: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        if (providers.isEmpty()) {
            HelpText(stringResource(Res.string.common_settings_cloud_setup_no_providers))
        } else {
            HelpText(stringResource(Res.string.common_settings_cloud_providers))
            val (leading, advanced) = providers.partition { it.plainSetup }
            var expanded by remember { mutableStateOf(false) }
            val offerMore = advanced.isNotEmpty() && moreProviders
            OutlinedCard(Modifier.fillMaxWidth()) {
                leading.forEachIndexed { index, provider ->
                    if (index > 0) HorizontalDivider()
                    ProviderRow(provider, onChoose = { setup.chooseProvider(provider) })
                }
                if (offerMore) {
                    HorizontalDivider()
                    MoreProvidersRow(expanded, onToggle = { expanded = !expanded })
                    if (expanded) {
                        for (provider in advanced) {
                            HorizontalDivider()
                            ProviderRow(provider, onChoose = { setup.chooseProvider(provider) })
                        }
                    }
                }
            }
            if (advanced.isNotEmpty() && !moreProviders) {
                HelpText(stringResource(Res.string.onboarding_cloud_more_providers_later))
            }
        }
        SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_cancel), onClick = setup::cancel)
    }
}

/** The providers that live on a server of the user's own rather than a consumer cloud. */
private val ServerTypes = setOf(WEBDAV_TYPE, "sftp", "ftp", "smb", "seafile")

/**
 * One provider of the picker: its name, and under it how it is set up — a browser sign-in, said in
 * the app's language — or else rclone's description, where that says more than the name does.
 */
@Composable
private fun ProviderRow(
    provider: RcloneProvider,
    onChoose: () -> Unit,
) {
    val detail =
        if (provider.browserSignIn) {
            stringResource(Res.string.common_settings_cloud_provider_browser)
        } else {
            provider.description.takeUnless { provider.name.contains(it.trim(), ignoreCase = true) }
        }
    ListItem(
        headlineContent = { Text(provider.name) },
        supportingContent =
            detail?.let {
                { Text(it) }
            },
        leadingContent = {
            Icon(
                if (provider.type in ServerTypes) Icons.Outlined.Dns else Icons.Outlined.Cloud,
                contentDescription = null,
            )
        },
        trailingContent = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.focusRing().clickable(role = Role.Button, onClick = onChoose),
    )
}

/** The last row of the picker, showing or hiding the providers that need rclone's own terms. */
@Composable
private fun MoreProvidersRow(
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val label =
        if (expanded) {
            Res.string.common_settings_cloud_providers_fewer
        } else {
            Res.string.common_settings_cloud_providers_more
        }
    ListItem(
        headlineContent = { Text(stringResource(label), color = MaterialTheme.colorScheme.primary) },
        leadingContent = { Icon(Icons.Outlined.MoreHoriz, contentDescription = null) },
        trailingContent = { ExpandIcon(expanded) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.expander(expanded, onToggle = onToggle),
    )
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

/**
 * The WebDAV form in the app's own words: the kind of server, the address its user opens in a
 * browser, and the sign-in. [WebDav.params] makes the address rclone's on submit; any question rclone
 * asks after that is the generic [SetupForm]'s.
 */
@Composable
private fun WebDavForm(
    state: CloudSetupState.Form,
    setup: CloudSetupActions,
) {
    val values = state.values
    Column(verticalArrangement = Arrangement.spacedBy(StepSpacing)) {
        HelpText(stringResource(Res.string.onboarding_webdav_help))
        Dropdown(
            selected = webDavVendorLabel(values[WebDav.VENDOR].orEmpty()),
            options = WebDav.VENDORS,
            optionLabel = { webDavVendorLabel(it) },
            onSelect = { setup.setValue(WebDav.VENDOR, it) },
            label = stringResource(Res.string.onboarding_webdav_kind),
        )
        OutlinedTextField(
            value = values[WebDav.URL].orEmpty(),
            onValueChange = { setup.setValue(WebDav.URL, it) },
            label = { Text(text = stringResource(Res.string.onboarding_webdav_address)) },
            supportingText = { Text(text = stringResource(Res.string.onboarding_webdav_address_help)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = values[WebDav.USER].orEmpty(),
            onValueChange = { setup.setValue(WebDav.USER, it) },
            label = { Text(text = stringResource(Res.string.onboarding_webdav_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = values[WebDav.PASS].orEmpty(),
            onValueChange = { setup.setValue(WebDav.PASS, it) },
            label = { Text(text = stringResource(Res.string.onboarding_webdav_password)) },
            supportingText = { Text(text = stringResource(Res.string.onboarding_webdav_password_help)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let { HelpText(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(StepSpacing)) {
            Button(onClick = setup::submit, enabled = !state.busy && !values[WebDav.URL].isNullOrBlank()) {
                Text(text = stringResource(Res.string.common_settings_cloud_form_submit))
            }
            OutlinedButton(onClick = setup::cancel) {
                Text(text = stringResource(Res.string.common_settings_cloud_cancel))
            }
        }
    }
}

@Composable
private fun webDavVendorLabel(vendor: String): String =
    when (vendor) {
        WebDav.NEXTCLOUD -> stringResource(Res.string.onboarding_webdav_nextcloud)
        WebDav.OWNCLOUD -> stringResource(Res.string.onboarding_webdav_owncloud)
        else -> stringResource(Res.string.onboarding_webdav_other)
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
    // Open the sign-in page once, when the engine first publishes it: saved, so the screen coming
    // back (after the app lock, or in a recreated window) does not open it again, and again.
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        if (url != null && url != opened) {
            opened = url
            runCatching { uriHandler.openUri(url) }
        }
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
        when {
            state.problem != null -> HelpText(folderProblemText(state.problem, state.detail))
            state.path.isEmpty() -> HelpText(stringResource(Res.string.common_settings_cloud_folder_root_help))
        }
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
            Button(onClick = setup::useFolder, enabled = state.usable) {
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

/** Why setup stopped, as a whole sentence; the engine's own text where it gave one. */
@Composable
private fun setupFailureText(
    reason: SetupFailure,
    detail: String?,
): String =
    when (reason) {
        SetupFailure.ENGINE -> stringResource(Res.string.common_settings_cloud_setup_failed, detail.orEmpty())
        SetupFailure.NOT_IN_PROGRESS -> stringResource(Res.string.common_settings_cloud_setup_interrupted)
        SetupFailure.UNEXPECTED_ANSWER -> stringResource(Res.string.common_settings_cloud_setup_unexpected)
    }

@Composable
private fun folderProblemText(
    problem: FolderProblem,
    detail: String?,
): String =
    when (problem) {
        FolderProblem.LIST_FAILED -> {
            stringResource(
                Res.string.common_settings_cloud_folder_list_failed,
                detail.orEmpty(),
            )
        }

        FolderProblem.CREATE_FAILED -> {
            stringResource(
                Res.string.common_settings_cloud_folder_create_failed,
                detail.orEmpty(),
            )
        }

        FolderProblem.CHECK_FAILED -> {
            stringResource(
                Res.string.common_settings_cloud_folder_check_failed,
                detail.orEmpty(),
            )
        }

        FolderProblem.OTHER_FILES -> {
            stringResource(Res.string.common_settings_cloud_folder_other_files)
        }
    }
