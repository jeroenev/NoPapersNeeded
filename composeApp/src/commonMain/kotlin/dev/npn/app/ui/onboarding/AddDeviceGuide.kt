package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.isAppPrivateLocation
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SectionHeading
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_ok
import no_papers_needed.composeapp.generated.resources.onboarding_add_device
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_cloud
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_cloud_connected
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_cloud_first
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_intro
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_nextcloud
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_nextcloud_steps
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_no_credentials
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_private
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_sees
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_sees_steps
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_syncthing
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_syncthing_steps
import org.jetbrains.compose.resources.stringResource

/** The gap between the guide's sections. */
private val GuideSpacing = 12.dp

/**
 * How to use this library on another device: through its cloud folder ([cloudFolder], as
 * [dev.npn.app.ui.settings.cloudFolderLabel] names it, null while the library has none), or by
 * letting Syncthing or the Nextcloud client carry its folder ([libraryLocation]) there; and what the
 * other device shows while it reads the library in. Nothing in it carries a sign-in: each device
 * signs in on its own.
 */
@Composable
internal fun AddDeviceGuideDialog(
    cloudFolder: String?,
    libraryLocation: String?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.onboarding_add_device)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(GuideSpacing),
            ) {
                AddDeviceGuide(cloudFolder, libraryLocation)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
        },
    )
}

@Composable
private fun ColumnScope.AddDeviceGuide(
    cloudFolder: String?,
    libraryLocation: String?,
) {
    Text(stringResource(Res.string.onboarding_add_device_intro), style = MaterialTheme.typography.bodyMedium)
    GuideSection(
        title = stringResource(Res.string.onboarding_add_device_cloud),
        steps =
            if (cloudFolder != null) {
                stringResource(Res.string.onboarding_add_device_cloud_connected, cloudFolder)
            } else {
                stringResource(Res.string.onboarding_add_device_cloud_first)
            },
    )
    // A cloud library's working copy is in the app's own storage, which no sync app can reach.
    val folder = libraryLocation?.takeUnless(::isAppPrivateLocation)
    if (folder == null) {
        HelpText(stringResource(Res.string.onboarding_add_device_private))
    } else {
        GuideSection(
            title = stringResource(Res.string.onboarding_add_device_syncthing),
            steps = stringResource(Res.string.onboarding_add_device_syncthing_steps, folder),
        )
        GuideSection(
            title = stringResource(Res.string.onboarding_add_device_nextcloud),
            steps = stringResource(Res.string.onboarding_add_device_nextcloud_steps, folder),
        )
    }
    GuideSection(
        title = stringResource(Res.string.onboarding_add_device_sees),
        steps = stringResource(Res.string.onboarding_add_device_sees_steps),
    )
    HelpText(stringResource(Res.string.onboarding_add_device_no_credentials))
}

@Composable
private fun GuideSection(
    title: String,
    steps: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(title, style = MaterialTheme.typography.titleSmall)
        // Selectable, so the folder's path can be copied into the sync app.
        SelectionContainer { Text(steps, style = MaterialTheme.typography.bodyMedium) }
    }
}
