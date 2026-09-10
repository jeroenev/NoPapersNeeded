package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        TextButton(onClick = { expanded = !expanded }) {
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
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
