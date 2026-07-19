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

/**
 * Parse "split after page N" cut points (1-based, comma/space separated) into contiguous 0-based
 * page ranges covering all [pageCount] pages. Returns null when the input is not a valid set of
 * cut points (non-numeric, out of 1..pageCount-1, or empty).
 */
fun parseSplitPoints(input: String, pageCount: Int): List<IntRange>? {
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
        title = { Text("Split document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Each part becomes a new document with the same tags and properties; the " +
                        "original moves to trash. Enter the pages to split after " +
                        "(1..${pageCount - 1}), e.g. \"3, 5\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Split after page(s)") },
                    singleLine = true,
                    isError = input.isNotBlank() && segments == null,
                )
                if (segments != null) {
                    Text(
                        segments.mapIndexed { i, range ->
                            val pages = if (range.first == range.last) {
                                "page ${range.first + 1}"
                            } else {
                                "pages ${range.first + 1}-${range.last + 1}"
                            }
                            "Part ${i + 1}: $pages"
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
            ) { Text("Split") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
