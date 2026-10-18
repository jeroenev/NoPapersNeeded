package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.settings.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_already_running
import no_papers_needed.composeapp.generated.resources.common_ok
import org.jetbrains.compose.resources.stringResource

/**
 * What a second desktop instance shows before it exits: the first one already has the library open,
 * and two processes must not share its index or drain its queues. In the app's own language.
 */
@Composable
fun AlreadyRunningNotice(
    settings: SettingsRepository,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by settings.settings.collectAsStateWithLifecycle()
    LocaleProvider(snapshot.appLanguage) {
        NpnTheme {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(stringResource(Res.string.common_already_running))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = onClose) { Text(stringResource(Res.string.common_ok)) }
                    }
                }
            }
        }
    }
}
