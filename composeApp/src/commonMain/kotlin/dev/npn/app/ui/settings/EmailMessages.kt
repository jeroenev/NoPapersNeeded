package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailSecretFailure
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.email_left_in_mailbox
import no_papers_needed.composeapp.generated.resources.email_progress_left
import no_papers_needed.composeapp.generated.resources.email_progress_through
import no_papers_needed.composeapp.generated.resources.email_progress_through_only
import no_papers_needed.composeapp.generated.resources.email_reason_authentication_failed
import no_papers_needed.composeapp.generated.resources.email_reason_connection_failed
import no_papers_needed.composeapp.generated.resources.email_reason_fetch_failed
import no_papers_needed.composeapp.generated.resources.email_reason_folder_not_found
import no_papers_needed.composeapp.generated.resources.email_reason_import_failed
import no_papers_needed.composeapp.generated.resources.email_reason_message_unreadable
import no_papers_needed.composeapp.generated.resources.email_reason_move_folder_not_found
import no_papers_needed.composeapp.generated.resources.email_reason_no_password
import no_papers_needed.composeapp.generated.resources.email_reason_not_configured
import no_papers_needed.composeapp.generated.resources.email_reason_pdf_render_failed
import no_papers_needed.composeapp.generated.resources.email_reason_plain_refused
import no_papers_needed.composeapp.generated.resources.email_reason_post_action_failed
import no_papers_needed.composeapp.generated.resources.email_reason_post_action_unsupported
import no_papers_needed.composeapp.generated.resources.email_reason_secret_unavailable
import no_papers_needed.composeapp.generated.resources.email_reason_tls_failed
import no_papers_needed.composeapp.generated.resources.email_reason_trash_not_found
import no_papers_needed.composeapp.generated.resources.email_reason_unsupported
import no_papers_needed.composeapp.generated.resources.email_retry_passed_over
import no_papers_needed.composeapp.generated.resources.email_secret_store_failed
import no_papers_needed.composeapp.generated.resources.email_secret_unavailable
import no_papers_needed.composeapp.generated.resources.email_secret_unsupported
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The whole-sentence resource for a mail failure; `core` never builds the English itself. */
internal fun MailFailureReason.textRes(): StringResource =
    when (this) {
        MailFailureReason.NOT_CONFIGURED -> Res.string.email_reason_not_configured
        MailFailureReason.NO_PASSWORD -> Res.string.email_reason_no_password
        MailFailureReason.SECRET_UNAVAILABLE -> Res.string.email_reason_secret_unavailable
        MailFailureReason.CONNECTION_FAILED -> Res.string.email_reason_connection_failed
        MailFailureReason.AUTHENTICATION_FAILED -> Res.string.email_reason_authentication_failed
        MailFailureReason.TLS_FAILED -> Res.string.email_reason_tls_failed
        MailFailureReason.FOLDER_NOT_FOUND -> Res.string.email_reason_folder_not_found
        MailFailureReason.FETCH_FAILED -> Res.string.email_reason_fetch_failed
        MailFailureReason.MESSAGE_UNREADABLE -> Res.string.email_reason_message_unreadable
        MailFailureReason.IMPORT_FAILED -> Res.string.email_reason_import_failed
        MailFailureReason.POST_ACTION_FAILED -> Res.string.email_reason_post_action_failed
        MailFailureReason.PLAIN_REFUSED -> Res.string.email_reason_plain_refused
        MailFailureReason.PDF_RENDER_FAILED -> Res.string.email_reason_pdf_render_failed
        MailFailureReason.UNSUPPORTED -> Res.string.email_reason_unsupported
        MailFailureReason.MOVE_FOLDER_NOT_FOUND -> Res.string.email_reason_move_folder_not_found
        MailFailureReason.TRASH_NOT_FOUND -> Res.string.email_reason_trash_not_found
        MailFailureReason.POST_ACTION_UNSUPPORTED -> Res.string.email_reason_post_action_unsupported
    }

/** The whole-sentence resource for a keyring failure. */
internal fun MailSecretFailure.textRes(): StringResource =
    when (this) {
        MailSecretFailure.UNSUPPORTED -> Res.string.email_secret_unsupported
        MailSecretFailure.UNAVAILABLE -> Res.string.email_secret_unavailable
        MailSecretFailure.STORE_FAILED -> Res.string.email_secret_store_failed
    }

/** The resolved sentence for a mail failure, in the composition's language. */
@Composable
internal fun MailFailureReason.text(): String = stringResource(textRes())

/**
 * Where a device stands in the folder, as one sentence: the date it has imported through and about
 * how many messages are left after it, whichever of the two is known; null when neither is.
 */
@Composable
internal fun mailPositionText(
    throughMillis: Long?,
    remaining: Int?,
): String? =
    when {
        throughMillis != null && remaining != null -> {
            pluralStringResource(
                Res.plurals.email_progress_through,
                remaining,
                formatEpochDate(throughMillis),
                remaining,
            )
        }

        remaining != null -> {
            pluralStringResource(Res.plurals.email_progress_left, remaining, remaining)
        }

        throughMillis != null -> {
            stringResource(Res.string.email_progress_through_only, formatEpochDate(throughMillis))
        }

        else -> {
            null
        }
    }

/**
 * The emails checks have left in the mailbox without importing them fully, over every check so far,
 * and a way to try them again. Nothing when none are left.
 */
@Composable
internal fun MailLeftBehind(
    passedOver: Int,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    if (passedOver == 0) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HelpText(pluralStringResource(Res.plurals.email_left_in_mailbox, passedOver, passedOver))
        if (onRetry != null) {
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.email_retry_passed_over)) }
        }
    }
}
