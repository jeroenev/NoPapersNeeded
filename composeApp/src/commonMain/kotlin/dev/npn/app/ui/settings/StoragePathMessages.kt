package dev.npn.app.ui.settings

import androidx.compose.runtime.Composable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_absolute
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_backslash
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_blank
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_date_format_only
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_invalid
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_invalid_segment
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_reserved
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_traversal
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unclosed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unexpected_closing
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unknown_variable
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_whitespace
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a [StoragePathError] as user-facing copy. Kept apart from the pane that shows it because
 * it is a pure resource lookup over the whole [StoragePathErrorKind] surface: one exhaustive
 * `when` that would otherwise dominate the pane it belongs to.
 */
@Composable
internal fun StoragePathError.localizedMessage(): String =
    when (kind) {
        StoragePathErrorKind.UNCLOSED_PLACEHOLDER -> {
            stringResource(
                Res.string.common_settings_storage_error_unclosed,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.UNEXPECTED_CLOSING_BRACE -> {
            stringResource(
                Res.string.common_settings_storage_error_unexpected_closing,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.DATE_FORMAT_ONLY -> {
            stringResource(
                Res.string.common_settings_storage_error_date_format_only,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.UNKNOWN_VARIABLE -> {
            stringResource(
                Res.string.common_settings_storage_error_unknown_variable,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.BLANK -> {
            stringResource(Res.string.common_settings_storage_error_blank)
        }

        StoragePathErrorKind.ABSOLUTE -> {
            stringResource(Res.string.common_settings_storage_error_absolute)
        }

        StoragePathErrorKind.BACKSLASH -> {
            stringResource(Res.string.common_settings_storage_error_backslash)
        }

        StoragePathErrorKind.TRAVERSAL -> {
            stringResource(Res.string.common_settings_storage_error_traversal)
        }

        StoragePathErrorKind.RESERVED_DIRECTORY -> {
            stringResource(
                Res.string.common_settings_storage_error_reserved,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.WHITESPACE -> {
            stringResource(Res.string.common_settings_storage_error_whitespace)
        }

        StoragePathErrorKind.INVALID_SEGMENT -> {
            stringResource(
                Res.string.common_settings_storage_error_invalid_segment,
                argument.orEmpty(),
            )
        }

        StoragePathErrorKind.INVALID -> {
            stringResource(Res.string.common_settings_storage_error_invalid)
        }
    }
