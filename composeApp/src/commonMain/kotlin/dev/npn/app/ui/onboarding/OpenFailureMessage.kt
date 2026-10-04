package dev.npn.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import dev.npn.app.OpenFailure
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.ui.common.TechnicalDetails
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_open_failed_folder_missing
import no_papers_needed.composeapp.generated.resources.common_open_failed_folder_not_created
import no_papers_needed.composeapp.generated.resources.common_open_failed_storage_access
import no_papers_needed.composeapp.generated.resources.common_open_failed_unreadable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Why a library did not open: the reason as a sentence in the app's language, and the technical
 * cause, which is English and meant for support, behind [TechnicalDetails].
 */
@Composable
internal fun OpenFailureMessage(
    failure: OpenFailure,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(failure.reason.message), color = color, textAlign = TextAlign.Center)
        TechnicalDetails(failure.details)
    }
}

private val OpenFailureReason.message: StringResource
    get() =
        when (this) {
            OpenFailureReason.FOLDER_MISSING -> Res.string.common_open_failed_folder_missing
            OpenFailureReason.NO_STORAGE_ACCESS -> Res.string.common_open_failed_storage_access
            OpenFailureReason.FOLDER_NOT_CREATED -> Res.string.common_open_failed_folder_not_created
            OpenFailureReason.UNREADABLE -> Res.string.common_open_failed_unreadable
        }
