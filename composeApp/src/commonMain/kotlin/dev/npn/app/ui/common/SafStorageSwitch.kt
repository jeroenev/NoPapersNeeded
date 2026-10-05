package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf
import no_papers_needed.composeapp.generated.resources.common_settings_use_saf_help
import org.jetbrains.compose.resources.stringResource

/**
 * The SAF switch where a library folder is chosen, stacked and centred to sit under the folder
 * buttons. Settings shows the same setting as a [SwitchRow]; like it, the whole block is the toggle.
 */
@Composable
fun SafStorageSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.common_settings_use_saf),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Switch(checked = checked, onCheckedChange = null)
        HelpText(stringResource(Res.string.common_settings_use_saf_help), textAlign = TextAlign.Center)
    }
}
