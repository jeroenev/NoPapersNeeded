package dev.npn.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.stringResource

/**
 * Single-text-field entry dialog shared by the "save/rename view" and "rename tag/file" flows. The
 * confirm button trims the text before invoking [onConfirm] and is enabled per [confirmEnabled],
 * which receives the current (untrimmed) text; the default requires non-blank input. Callers that
 * prefill [initial] and want to block confirming an unchanged value pass a stricter predicate.
 */
@Composable
fun TextInputDialog(
    title: String,
    label: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
    confirmLabel: String? = null,
    confirmEnabled: (String) -> Boolean = { it.isNotBlank() },
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = confirmEnabled(text),
                onClick = { onConfirm(text.trim()) },
            ) { Text(confirmLabel ?: stringResource(Res.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
