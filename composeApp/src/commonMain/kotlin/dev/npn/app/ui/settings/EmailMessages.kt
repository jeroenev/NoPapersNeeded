package dev.npn.app.ui.settings

import androidx.compose.runtime.Composable
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailSecretFailure
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.email_reason_authentication_failed
import no_papers_needed.composeapp.generated.resources.email_reason_connection_failed
import no_papers_needed.composeapp.generated.resources.email_reason_fetch_failed
import no_papers_needed.composeapp.generated.resources.email_reason_folder_not_found
import no_papers_needed.composeapp.generated.resources.email_reason_import_failed
import no_papers_needed.composeapp.generated.resources.email_reason_no_password
import no_papers_needed.composeapp.generated.resources.email_reason_not_configured
import no_papers_needed.composeapp.generated.resources.email_reason_pdf_render_failed
import no_papers_needed.composeapp.generated.resources.email_reason_plain_refused
import no_papers_needed.composeapp.generated.resources.email_reason_post_action_failed
import no_papers_needed.composeapp.generated.resources.email_reason_secret_unavailable
import no_papers_needed.composeapp.generated.resources.email_reason_tls_failed
import no_papers_needed.composeapp.generated.resources.email_reason_unsupported
import no_papers_needed.composeapp.generated.resources.email_secret_store_failed
import no_papers_needed.composeapp.generated.resources.email_secret_unavailable
import no_papers_needed.composeapp.generated.resources.email_secret_unsupported
import org.jetbrains.compose.resources.StringResource
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
        MailFailureReason.IMPORT_FAILED -> Res.string.email_reason_import_failed
        MailFailureReason.POST_ACTION_FAILED -> Res.string.email_reason_post_action_failed
        MailFailureReason.PLAIN_REFUSED -> Res.string.email_reason_plain_refused
        MailFailureReason.PDF_RENDER_FAILED -> Res.string.email_reason_pdf_render_failed
        MailFailureReason.UNSUPPORTED -> Res.string.email_reason_unsupported
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
