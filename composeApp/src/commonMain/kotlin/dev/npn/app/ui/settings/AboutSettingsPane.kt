package dev.npn.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.npn.app.BuildInfo
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_product_version
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AboutSettingsPane() {
    SettingsGroup(title = null) {
        Text(
            text =
                stringResource(
                    Res.string.common_settings_product_version,
                    stringResource(Res.string.common_app_name),
                    BuildInfo.VERSION,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        SettingsHelpText(stringResource(Res.string.common_settings_about_help))
    }
}
