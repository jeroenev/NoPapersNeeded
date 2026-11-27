package dev.npn.app.ui.onboarding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import dev.npn.app.OpenFailure
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_open_library_title
import no_papers_needed.composeapp.generated.resources.common_pick_folder_again
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem
import org.jetbrains.compose.resources.stringResource

/**
 * The saved library could not be opened. Retry is always on offer (a drive plugged back in, a
 * grant given back); so is choosing the folder again, through the same [LibraryLocationChooser]
 * onboarding uses, including the all-files grant when that is what went missing. Settings is out
 * of reach here, so "Report a problem" is offered on the screen itself.
 */
@Composable
fun RestoreFailedScreen(
    failure: OpenFailure,
    onRetry: () -> Unit,
    onOpen: (location: String) -> Unit,
    onReportProblem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredPane(modifier) {
        Icon(Icons.Filled.Warning, contentDescription = null)
        Text(
            stringResource(Res.string.common_open_library_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        OpenFailureMessage(failure)
        Button(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
        LibraryLocationChooser(onPick = onOpen) { pickers ->
            OutlinedButton(onClick = pickers.pickLibrary) {
                Text(stringResource(Res.string.common_pick_folder_again))
            }
        }
        TextButton(onClick = onReportProblem) { Text(stringResource(Res.string.common_settings_report_problem)) }
    }
}
