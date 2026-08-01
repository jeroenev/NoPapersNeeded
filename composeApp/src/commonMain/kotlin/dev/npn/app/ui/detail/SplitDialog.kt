package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.detail_split
import no_papers_needed.composeapp.generated.resources.detail_split_after_pages
import no_papers_needed.composeapp.generated.resources.detail_split_document
import no_papers_needed.composeapp.generated.resources.detail_split_explanation
import no_papers_needed.composeapp.generated.resources.detail_split_pages
import no_papers_needed.composeapp.generated.resources.detail_split_part
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Parse "split after page N" cut points (1-based, comma/space separated) into contiguous 0-based
 * page ranges covering all [pageCount] pages. Returns null when the input is not a valid set of
 * cut points (non-numeric, out of 1..pageCount-1, or empty).
 */
fun parseSplitPoints(
    input: String,
    pageCount: Int,
): List<IntRange>? {
    val tokens = input.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    val points = tokens.map { it.toIntOrNull() ?: return null }.distinct().sorted()
    if (points.any { it < 1 || it >= pageCount }) return null
    val boundaries = listOf(0) + points + listOf(pageCount)
    return boundaries.zipWithNext { from, to -> from until to }
}

/**
 * Dialog collecting cut points for splitting a [pageCount]-page document. Confirm hands the
 * validated segments to [onConfirm]; the button stays disabled while the input is invalid.
 */
@Composable
fun SplitDialog(
    pageCount: Int,
    onConfirm: (List<IntRange>) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val segments = parseSplitPoints(input, pageCount)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_split_document)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.detail_split_explanation, pageCount - 1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text(stringResource(Res.string.detail_split_after_pages)) },
                    singleLine = true,
                    isError = input.isNotBlank() && segments == null,
                )
                if (segments != null) {
                    Text(
                        segments
                            .mapIndexed { i, range ->
                                val pages =
                                    pluralStringResource(
                                        Res.plurals.detail_split_pages,
                                        range.count(),
                                        range.first + 1,
                                        range.last + 1,
                                    )
                                stringResource(Res.string.detail_split_part, i + 1, pages)
                            }.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = segments != null,
                onClick = { segments?.let(onConfirm) },
            ) { Text(stringResource(Res.string.detail_split)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
