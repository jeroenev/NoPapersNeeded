package dev.npn.app.ui.whatsnew

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.util.runCatchingCancellable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_ok
import no_papers_needed.composeapp.generated.resources.common_settings_whats_new
import no_papers_needed.composeapp.generated.resources.common_settings_whats_new_bullet
import no_papers_needed.composeapp.generated.resources.common_settings_whats_new_load_failed
import org.jetbrains.compose.resources.stringResource

/**
 * The bundled "What's new" notes, shown once after a version change and on demand from About.
 * Nothing is fetched: the notes travel in the build, so this works offline and never leaks a
 * request to a release server.
 */
@Composable
internal fun WhatsNewDialog(onDismiss: () -> Unit) {
    val notes by produceState<List<WhatsNewBlock>?>(initialValue = null) {
        value =
            runCatchingCancellable { Res.readBytes(WhatsNewMarkdown.NOTES_PATH).decodeToString() }
                .map(WhatsNewMarkdown::parse)
                .getOrDefault(emptyList())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.common_settings_whats_new)) },
        text = {
            val blocks = notes
            Column(
                Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    blocks == null -> {}

                    blocks.isEmpty() -> {
                        Text(stringResource(Res.string.common_settings_whats_new_load_failed))
                    }

                    else -> {
                        blocks.forEach { block ->
                            when (block) {
                                is WhatsNewBlock.Title -> {
                                    Text(block.text, style = MaterialTheme.typography.titleLarge)
                                }

                                is WhatsNewBlock.Section -> {
                                    Text(
                                        block.text,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }

                                is WhatsNewBlock.Bullet -> {
                                    Bullet(block.text)
                                }

                                is WhatsNewBlock.Paragraph -> {
                                    Text(block.text)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_ok)) }
        },
    )
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.common_settings_whats_new_bullet))
        Text(text, modifier = Modifier.weight(1f))
    }
}
