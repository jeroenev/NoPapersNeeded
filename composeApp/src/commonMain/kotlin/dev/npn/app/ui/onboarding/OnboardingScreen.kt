package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.OpenFailure
import dev.npn.app.platform.defaultLibraryLocation
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.disclosure
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.CloudSetupWizard
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_choose_library_folder
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_create_new_library
import no_papers_needed.composeapp.generated.resources.common_new_library_folder_name
import no_papers_needed.composeapp.generated.resources.common_onboarding_cloud_help
import no_papers_needed.composeapp.generated.resources.common_onboarding_cloud_title
import no_papers_needed.composeapp.generated.resources.common_onboarding_intro
import no_papers_needed.composeapp.generated.resources.common_onboarding_local_heading
import no_papers_needed.composeapp.generated.resources.common_onboarding_recommended_help
import no_papers_needed.composeapp.generated.resources.common_onboarding_sync
import no_papers_needed.composeapp.generated.resources.common_onboarding_use_cloud
import no_papers_needed.composeapp.generated.resources.common_onboarding_use_recommended
import no_papers_needed.composeapp.generated.resources.ux_library_name
import no_papers_needed.composeapp.generated.resources.ux_sync_help
import org.jetbrains.compose.resources.stringResource

/**
 * First-run screen: where the library lives. Where the platform can set up cloud sync
 * ([cloudSetup] non-null), the first choice is a cloud drive: the user picks a provider, signs in
 * and picks the cloud folder, in the same wizard settings uses ([cloudState] is its step, null
 * while it is closed), and the app keeps the library's working copy in a folder of its own.
 *
 * The other choices keep the library in a folder the user picks. In direct mode (the default
 * everywhere) the folder is a plain path; Android first needs the all-files grant and proposes
 * `Documents/NoPapersNeeded`. The advanced Storage Access Framework switch is Android's escape hatch
 * for folders that have no path (cloud document providers) and is the only way to reach the SAF
 * library implementation.
 *
 * [failure] says why the last attempt from here did not open; the choices below it are the retry.
 */
@Composable
fun OnboardingScreen(
    failure: OpenFailure?,
    useSaf: Boolean,
    onUseSafChange: (Boolean) -> Unit,
    onOpen: (location: String) -> Unit,
    onCreate: (parent: String, name: String) -> Unit,
    onUseRecommendedFolder: (location: String) -> Unit,
    cloudState: CloudSetupState?,
    cloudSetup: CloudSetupActions?,
    modifier: Modifier = Modifier,
) {
    var showSyncHelp by rememberSaveable { mutableStateOf(false) }
    // The create flow picks the PARENT folder first, then asks for the new folder's name.
    var createParent by rememberSaveable { mutableStateOf<String?>(null) }
    val recommended = if (useSaf) null else defaultLibraryLocation()

    CenteredPane(modifier) {
        Text(
            text = stringResource(Res.string.common_app_name),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (cloudSetup != null && cloudState != null) {
            Text(
                text = stringResource(Res.string.common_onboarding_cloud_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            CloudSetupWizard(cloudState, cloudSetup)
            return@CenteredPane
        }
        Text(
            text = stringResource(Res.string.common_onboarding_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (failure != null) {
            Text(
                text = stringResource(Res.string.common_could_not_open_library),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            OpenFailureMessage(failure, color = MaterialTheme.colorScheme.error)
        }
        if (cloudSetup != null) {
            CloudChoice(onStart = cloudSetup::start)
            Text(
                text = stringResource(Res.string.common_onboarding_local_heading),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        }
        val toggleSyncHelp = { showSyncHelp = !showSyncHelp }
        TextButton(onClick = toggleSyncHelp, modifier = Modifier.disclosure(showSyncHelp, toggleSyncHelp)) {
            Text(stringResource(Res.string.ux_sync_help))
        }
        if (showSyncHelp) {
            Text(
                text = stringResource(Res.string.common_onboarding_sync),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        LibraryLocationChooser(
            useSaf = useSaf,
            onUseSafChange = onUseSafChange,
            onPick = onOpen,
            onPickParent = { createParent = it },
        ) { pickLibrary, pickParent ->
            LibraryChoices(
                recommended = recommended,
                useSaf = useSaf,
                onPickLibrary = pickLibrary,
                onPickParent = pickParent,
                onUseRecommendedFolder = onUseRecommendedFolder,
                // With the cloud drive offered first, no folder choice is the filled button.
                primary = cloudSetup == null,
            )
        }
    }

    createParent?.let { parent ->
        TextInputDialog(
            title = stringResource(Res.string.common_create_new_library),
            label = stringResource(Res.string.common_new_library_folder_name),
            initial = stringResource(Res.string.ux_library_name),
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { name ->
                createParent = null
                onCreate(parent, name)
            },
            onDismiss = { createParent = null },
        )
    }
}

/** The cloud drive choice: the filled button that starts the setup wizard, and what it means. */
@Composable
private fun CloudChoice(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = onStart, modifier = Modifier.testTag(TestTags.USE_CLOUD_DRIVE)) {
            Text(stringResource(Res.string.common_onboarding_use_cloud))
        }
        HelpText(
            text = stringResource(Res.string.common_onboarding_cloud_help),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The ways to a library in a folder: the platform's recommended folder when it has one, a new
 * folder, or an existing one. When [primary], the first choice on offer is the filled button.
 */
@Composable
private fun LibraryChoices(
    recommended: String?,
    useSaf: Boolean,
    onPickLibrary: () -> Unit,
    onPickParent: () -> Unit,
    onUseRecommendedFolder: (location: String) -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
) {
    // Both platforms create a folder inside a direct parent; a SAF parent cannot take one.
    val canCreate = !useSaf
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (recommended != null) {
            ChoiceButton(
                filled = primary,
                onClick = { onUseRecommendedFolder(recommended) },
                modifier = Modifier.testTag(TestTags.USE_RECOMMENDED_FOLDER),
            ) {
                Text(stringResource(Res.string.common_onboarding_use_recommended))
            }
            HelpText(
                text = stringResource(Res.string.common_onboarding_recommended_help, recommended),
                textAlign = TextAlign.Center,
            )
        }
        if (canCreate && recommended == null) {
            ChoiceButton(filled = primary, onClick = onPickParent) {
                Text(stringResource(Res.string.common_create_new_library))
            }
        }
        val firstChoice = recommended == null && !canCreate
        ChoiceButton(filled = primary && firstChoice, onClick = onPickLibrary) {
            Text(stringResource(Res.string.common_choose_library_folder))
        }
        if (canCreate && recommended != null) {
            TextButton(onClick = onPickParent) {
                Text(stringResource(Res.string.common_create_new_library))
            }
        }
    }
}

/** A filled button for the screen's first choice, an outlined one for the others. */
@Composable
private fun ChoiceButton(
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (filled) {
        Button(onClick = onClick, modifier = modifier, content = content)
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier, content = content)
    }
}

/**
 * The library-location screens' frame: their content centred in a column of readable width, the
 * whole scrolling when it outgrows the window.
 */
@Composable
internal fun CenteredPane(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}
