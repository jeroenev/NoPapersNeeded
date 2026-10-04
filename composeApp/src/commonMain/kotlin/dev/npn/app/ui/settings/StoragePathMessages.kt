package dev.npn.app.ui.settings

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.core.pathrules.StoragePathError
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_absolute
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_backslash
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_blank
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_format_not_allowed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_invalid_segment
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_missing_field_key
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_not_a_date
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_reserved
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_traversal
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unclosed
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unexpected_closing
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_unknown_variable
import no_papers_needed.composeapp.generated.resources.common_settings_storage_error_whitespace

/**
 * A [StoragePathError] as user-facing copy. Kept apart from the pane that shows it because it is a
 * pure resource lookup over the whole error surface: one exhaustive `when` that would otherwise
 * dominate the pane it belongs to.
 */
internal fun StoragePathError.text(): UiText =
    when (this) {
        is StoragePathError.UnclosedPlaceholder -> {
            uiText(Res.string.common_settings_storage_error_unclosed, position)
        }

        is StoragePathError.UnexpectedClosingBrace -> {
            uiText(Res.string.common_settings_storage_error_unexpected_closing, position)
        }

        is StoragePathError.UnknownVariable -> {
            uiText(Res.string.common_settings_storage_error_unknown_variable, name)
        }

        StoragePathError.MissingFieldKey -> {
            uiText(Res.string.common_settings_storage_error_missing_field_key)
        }

        is StoragePathError.NotADateField -> {
            uiText(Res.string.common_settings_storage_error_not_a_date, key)
        }

        is StoragePathError.FormatNotAllowed -> {
            uiText(Res.string.common_settings_storage_error_format_not_allowed, name)
        }

        StoragePathError.Blank -> {
            uiText(Res.string.common_settings_storage_error_blank)
        }

        StoragePathError.Absolute -> {
            uiText(Res.string.common_settings_storage_error_absolute)
        }

        StoragePathError.Backslash -> {
            uiText(Res.string.common_settings_storage_error_backslash)
        }

        StoragePathError.Traversal -> {
            uiText(Res.string.common_settings_storage_error_traversal)
        }

        is StoragePathError.ReservedDirectory -> {
            uiText(Res.string.common_settings_storage_error_reserved, name)
        }

        StoragePathError.EdgeWhitespace -> {
            uiText(Res.string.common_settings_storage_error_whitespace)
        }

        is StoragePathError.InvalidSegment -> {
            uiText(Res.string.common_settings_storage_error_invalid_segment, segment)
        }
    }
