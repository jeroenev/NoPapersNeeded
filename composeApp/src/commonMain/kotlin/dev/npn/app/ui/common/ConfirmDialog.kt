package dev.npn.app.ui.common

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_confirm
import org.jetbrains.compose.resources.stringResource

/** A confirmation that says [message]: confirm or dismiss. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
) {
    ConfirmDialog(
        title = title,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        confirmLabel = confirmLabel,
        dismissLabel = dismissLabel,
        destructive = destructive,
    ) {
        Text(message)
    }
}

/**
 * A dialog with a body of its own ([content], such as a form) and the confirm and dismiss buttons
 * every dialog has; confirming waits for [confirmEnabled].
 */
@Composable
fun ConfirmDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmLabel: String? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = content,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors =
                    if (destructive) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
            ) {
                Text(confirmLabel ?: stringResource(Res.string.common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel ?: stringResource(Res.string.common_cancel))
            }
        },
    )
}
