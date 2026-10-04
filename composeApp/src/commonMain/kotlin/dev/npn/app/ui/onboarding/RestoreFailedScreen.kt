package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.npn.app.OpenFailure
import dev.npn.app.di.OpenFailureReason
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_open_library_title
import no_papers_needed.composeapp.generated.resources.common_pick_folder_again
import no_papers_needed.composeapp.generated.resources.common_retry
import org.jetbrains.compose.resources.stringResource

/**
 * The saved library could not be opened. Retry is always on offer (a drive plugged back in, a
 * grant given back); so is choosing the folder again, through the same [LibraryLocationChooser]
 * onboarding uses, including the all-files grant when that is what went missing.
 */
@Composable
fun RestoreFailedScreen(
    failure: OpenFailure,
    useSaf: Boolean,
    onUseSafChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onOpen: (location: String) -> Unit,
    modifier: Modifier = Modifier,
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
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Text(
                stringResource(Res.string.common_open_library_title),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            OpenFailureMessage(failure)
            Button(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            LibraryLocationChooser(
                useSaf = useSaf,
                onUseSafChange = onUseSafChange,
                onPick = onOpen,
                requireStorageAccess = failure.reason == OpenFailureReason.NO_STORAGE_ACCESS,
            ) { pickLibrary, _ ->
                OutlinedButton(onClick = pickLibrary) {
                    Text(stringResource(Res.string.common_pick_folder_again))
                }
            }
        }
    }
}
