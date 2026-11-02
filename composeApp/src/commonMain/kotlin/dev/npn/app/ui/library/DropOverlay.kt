package dev.npn.app.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_drop_subtitle
import no_papers_needed.composeapp.generated.resources.library_drop_title
import org.jetbrains.compose.resources.stringResource

/** Scrim alpha over the window while files are dragged over it, so the drop target reads as one. */
private const val DROP_SCRIM_ALPHA = 0.85f

/**
 * What the desktop window shows while files are dragged over it: the whole window is the drop target,
 * and says what a drop does with PDFs and with images.
 */
@Composable
fun DropOverlay(modifier: Modifier = Modifier) {
    Surface(
        modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = DROP_SCRIM_ALPHA),
    ) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.fillMaxSize(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = DROP_SCRIM_ALPHA),
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
            ) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(64.dp))
                    Text(
                        stringResource(Res.string.library_drop_title),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(Res.string.library_drop_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
