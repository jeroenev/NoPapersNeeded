package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_hide_technical_details
import no_papers_needed.composeapp.generated.resources.common_show_technical_details
import org.jetbrains.compose.resources.stringResource

/** Progressive disclosure for diagnostics that are useful to support, but not the primary message. */
@Composable
fun TechnicalDetails(
    details: String?,
    modifier: Modifier = Modifier,
) {
    if (details.isNullOrBlank()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        // The label already flips between "Show"/"Hide"; the expand/collapse semantics action lets
        // assistive tech announce and drive the disclosure state rather than reading a plain button.
        TextButton(
            onClick = { expanded = !expanded },
            modifier =
                Modifier.semantics {
                    if (expanded) {
                        collapse {
                            expanded = false
                            true
                        }
                    } else {
                        expand {
                            expanded = true
                            true
                        }
                    }
                },
        ) {
            Text(
                stringResource(
                    if (expanded) {
                        Res.string.common_hide_technical_details
                    } else {
                        Res.string.common_show_technical_details
                    },
                ),
            )
        }
        if (expanded) {
            // Support often needs to copy the diagnostic verbatim, so make the revealed text
            // selectable rather than a read-only label.
            SelectionContainer {
                Text(
                    details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
