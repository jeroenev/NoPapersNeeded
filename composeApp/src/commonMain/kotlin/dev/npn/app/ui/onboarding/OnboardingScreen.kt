package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import dev.npn.app.OpenFailure
import dev.npn.app.platform.defaultLibraryLocation
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.settings.CloudSetupActions
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.CloudSetupWizard
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_could_not_open_library
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_create_new_library
import no_papers_needed.composeapp.generated.resources.common_new_library_folder_name
import no_papers_needed.composeapp.generated.resources.common_onboarding_cloud_title
import no_papers_needed.composeapp.generated.resources.onboarding_already_use
import no_papers_needed.composeapp.generated.resources.onboarding_change_folder
import no_papers_needed.composeapp.generated.resources.onboarding_choose_cloud
import no_papers_needed.composeapp.generated.resources.onboarding_choose_folder
import no_papers_needed.composeapp.generated.resources.onboarding_cloud_help
import no_papers_needed.composeapp.generated.resources.onboarding_cloud_title
import no_papers_needed.composeapp.generated.resources.onboarding_connect_cloud_help
import no_papers_needed.composeapp.generated.resources.onboarding_connect_cloud_title
import no_papers_needed.composeapp.generated.resources.onboarding_connect_help
import no_papers_needed.composeapp.generated.resources.onboarding_connect_here_button
import no_papers_needed.composeapp.generated.resources.onboarding_connect_here_help
import no_papers_needed.composeapp.generated.resources.onboarding_connect_here_title
import no_papers_needed.composeapp.generated.resources.onboarding_connect_progress_note
import no_papers_needed.composeapp.generated.resources.onboarding_connect_synced_button
import no_papers_needed.composeapp.generated.resources.onboarding_connect_synced_help
import no_papers_needed.composeapp.generated.resources.onboarding_connect_synced_title
import no_papers_needed.composeapp.generated.resources.onboarding_connect_title
import no_papers_needed.composeapp.generated.resources.onboarding_device_help
import no_papers_needed.composeapp.generated.resources.onboarding_device_path
import no_papers_needed.composeapp.generated.resources.onboarding_device_title
import no_papers_needed.composeapp.generated.resources.onboarding_get_started
import no_papers_needed.composeapp.generated.resources.onboarding_location_title
import no_papers_needed.composeapp.generated.resources.onboarding_use_this_folder
import no_papers_needed.composeapp.generated.resources.onboarding_value_offline
import no_papers_needed.composeapp.generated.resources.onboarding_value_own
import no_papers_needed.composeapp.generated.resources.onboarding_value_search
import no_papers_needed.composeapp.generated.resources.onboarding_welcome_title
import no_papers_needed.composeapp.generated.resources.ux_library_name
import org.jetbrains.compose.resources.stringResource

/**
 * First run, up to an open library: a welcome with the two ways in, then where a new library lives
 * (by intent: on this device, the default, or in a cloud drive) or how to reach one another device
 * already uses (its cloud folder, a folder a sync app keeps up to date, or one already here). The
 * step is saved, so a rotation or a restored process comes back to it; [onBegin] says which way the
 * user went as they make a choice that opens a library, so first run can go on from there.
 *
 * A folder on this device is a plain path. Android first needs the all-files grant, which the
 * "on this device" choice asks for in place, and proposes `Documents/NoPapersNeeded` (desktop the
 * documents folder's `NoPapersNeeded`). The advanced Storage Access Framework switch is Android's
 * escape hatch for folders that have no path (cloud document providers).
 *
 * The cloud drive choice runs the setup wizard settings uses ([cloudState] is its step, null while
 * it is closed; [cloudSetup] null where the platform has none), offering only the providers set up
 * in the user's own terms. [failure] says why the last attempt did not open; the choices are the retry.
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
    onBegin: (FirstRunPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable(stateSaver = enumSaver()) { mutableStateOf(OnboardingStep.WELCOME) }
    // The create flow picks the PARENT folder first, then asks for the new folder's name.
    var createParent by rememberSaveable { mutableStateOf<String?>(null) }
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        isBackEnabled = step != OnboardingStep.WELCOME && cloudState == null,
        onBackCompleted = { step = OnboardingStep.WELCOME },
    )

    CenteredPane(modifier) {
        if (cloudSetup != null && cloudState != null) {
            StepTitle(stringResource(Res.string.common_onboarding_cloud_title))
            CloudSetupWizard(cloudState, cloudSetup, moreProviders = false)
            return@CenteredPane
        }
        if (failure != null) FailureNote(failure)
        when (step) {
            OnboardingStep.WELCOME -> {
                WelcomeStep(
                    onGetStarted = { step = OnboardingStep.LOCATION },
                    onAlreadyUse = { step = OnboardingStep.CONNECT },
                )
            }

            OnboardingStep.LOCATION -> {
                LocationStep(
                    useSaf = useSaf,
                    onUseSafChange = onUseSafChange,
                    onPickLibrary = { location ->
                        onBegin(FirstRunPath.NEW)
                        onOpen(location)
                    },
                    onPickParent = { createParent = it },
                    onUseRecommendedFolder = { location ->
                        onBegin(FirstRunPath.NEW)
                        onUseRecommendedFolder(location)
                    },
                    onUseCloud =
                        cloudSetup?.let { setup ->
                            {
                                onBegin(FirstRunPath.NEW)
                                setup.start()
                            }
                        },
                    onBack = { step = OnboardingStep.WELCOME },
                )
            }

            OnboardingStep.CONNECT -> {
                ConnectStep(
                    useSaf = useSaf,
                    onUseSafChange = onUseSafChange,
                    onPickLibrary = { location ->
                        onBegin(FirstRunPath.CONNECT)
                        onOpen(location)
                    },
                    onUseCloud =
                        cloudSetup?.let { setup ->
                            {
                                onBegin(FirstRunPath.CONNECT)
                                setup.start()
                            }
                        },
                    onBack = { step = OnboardingStep.WELCOME },
                )
            }
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
                onBegin(FirstRunPath.NEW)
                onCreate(parent, name)
            },
            onDismiss = { createParent = null },
        )
    }
}

/** What the app is for, in three lines, and the two ways in. */
@Composable
private fun ColumnScope.WelcomeStep(
    onGetStarted: () -> Unit,
    onAlreadyUse: () -> Unit,
) {
    Text(
        text = stringResource(Res.string.common_app_name),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(Res.string.onboarding_welcome_title),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueLine(Icons.Outlined.Search, stringResource(Res.string.onboarding_value_search))
        ValueLine(Icons.Outlined.PictureAsPdf, stringResource(Res.string.onboarding_value_own))
        ValueLine(Icons.Outlined.CloudOff, stringResource(Res.string.onboarding_value_offline))
    }
    Button(onClick = onGetStarted, modifier = Modifier.testTag(TestTags.GET_STARTED)) {
        Text(stringResource(Res.string.onboarding_get_started))
    }
    TextButton(onClick = onAlreadyUse, modifier = Modifier.testTag(TestTags.ALREADY_USE)) {
        Text(stringResource(Res.string.onboarding_already_use), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ValueLine(
    icon: ImageVector,
    text: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Where a new library lives, by intent: on this device (the default, one tap, with the folder shown
 * and a way to change it, and Android's all-files grant asked for right there), or in a cloud drive
 * ([onUseCloud], null where the platform has no cloud setup).
 */
@Composable
private fun ColumnScope.LocationStep(
    useSaf: Boolean,
    onUseSafChange: (Boolean) -> Unit,
    onPickLibrary: (location: String) -> Unit,
    onPickParent: (location: String) -> Unit,
    onUseRecommendedFolder: (location: String) -> Unit,
    onUseCloud: (() -> Unit)?,
    onBack: () -> Unit,
) {
    // A SAF folder has no path to propose, and cannot have a folder made in it.
    val recommended = if (useSaf) null else defaultLibraryLocation()
    StepTitle(stringResource(Res.string.onboarding_location_title))
    LibraryLocationChooser(
        useSaf = useSaf,
        onUseSafChange = onUseSafChange,
        onPick = onPickLibrary,
        onPickParent = onPickParent,
        grantInChoices = true,
    ) { pickers ->
        ChoiceCard(
            icon = Icons.Outlined.Folder,
            title = stringResource(Res.string.onboarding_device_title),
            help = stringResource(Res.string.onboarding_device_help),
        ) {
            when {
                recommended == null -> {
                    Button(onClick = pickers.pickLibrary) { Text(stringResource(Res.string.onboarding_choose_folder)) }
                }

                !pickers.storage.usable -> {
                    StorageAccessStep(pickers.storage, showTitle = false)
                }

                else -> {
                    HelpText(stringResource(Res.string.onboarding_device_path, recommended))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { onUseRecommendedFolder(recommended) },
                            modifier = Modifier.testTag(TestTags.USE_RECOMMENDED_FOLDER),
                        ) {
                            Text(stringResource(Res.string.onboarding_use_this_folder))
                        }
                        TextButton(onClick = pickers.pickParent) {
                            Text(stringResource(Res.string.onboarding_change_folder))
                        }
                    }
                }
            }
        }
        if (onUseCloud != null) {
            ChoiceCard(
                icon = Icons.Outlined.Cloud,
                title = stringResource(Res.string.onboarding_cloud_title),
                help = stringResource(Res.string.onboarding_cloud_help),
            ) {
                OutlinedButton(onClick = onUseCloud, modifier = Modifier.testTag(TestTags.USE_CLOUD_DRIVE)) {
                    Text(stringResource(Res.string.onboarding_choose_cloud))
                }
            }
        }
    }
    TextButton(onClick = onBack) { Text(stringResource(Res.string.common_back)) }
}

/**
 * Reach a library another device already uses: through its cloud folder ([onUseCloud], null where
 * the platform has no cloud setup), a folder a sync app keeps up to date, or a folder already here.
 * Both folder choices pick the library's folder; they differ in what the user has to do first.
 */
@Composable
private fun ColumnScope.ConnectStep(
    useSaf: Boolean,
    onUseSafChange: (Boolean) -> Unit,
    onPickLibrary: (location: String) -> Unit,
    onUseCloud: (() -> Unit)?,
    onBack: () -> Unit,
) {
    StepTitle(stringResource(Res.string.onboarding_connect_title))
    HelpText(stringResource(Res.string.onboarding_connect_help), textAlign = TextAlign.Center)
    if (onUseCloud != null) {
        ChoiceCard(
            icon = Icons.Outlined.Cloud,
            title = stringResource(Res.string.onboarding_connect_cloud_title),
            help = stringResource(Res.string.onboarding_connect_cloud_help),
        ) {
            OutlinedButton(onClick = onUseCloud) { Text(stringResource(Res.string.onboarding_choose_cloud)) }
        }
    }
    LibraryLocationChooser(useSaf = useSaf, onUseSafChange = onUseSafChange, onPick = onPickLibrary) { pickers ->
        ChoiceCard(
            icon = Icons.Outlined.Sync,
            title = stringResource(Res.string.onboarding_connect_synced_title),
            help = stringResource(Res.string.onboarding_connect_synced_help),
        ) {
            OutlinedButton(onClick = pickers.pickLibrary) {
                Text(stringResource(Res.string.onboarding_connect_synced_button))
            }
        }
        ChoiceCard(
            icon = Icons.Outlined.FolderOpen,
            title = stringResource(Res.string.onboarding_connect_here_title),
            help = stringResource(Res.string.onboarding_connect_here_help),
        ) {
            OutlinedButton(onClick = pickers.pickLibrary) {
                Text(stringResource(Res.string.onboarding_connect_here_button))
            }
        }
    }
    HelpText(stringResource(Res.string.onboarding_connect_progress_note), textAlign = TextAlign.Center)
    TextButton(onClick = onBack) { Text(stringResource(Res.string.common_back)) }
}

/** Why the last choice did not open a library, above the choices that retry it. */
@Composable
private fun ColumnScope.FailureNote(failure: OpenFailure) {
    Text(
        text = stringResource(Res.string.common_could_not_open_library),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    OpenFailureMessage(failure, color = MaterialTheme.colorScheme.error)
}

/** The title of an onboarding or first-run step. */
@Composable
internal fun StepTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
}

/** One of a step's choices: what it is ([title], [help]), and its [actions]. */
@Composable
internal fun ChoiceCard(
    icon: ImageVector,
    title: String,
    help: String,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(ChoiceIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                HelpText(help)
                actions()
            }
        }
    }
}

private val ChoiceIconSize = 28.dp

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
