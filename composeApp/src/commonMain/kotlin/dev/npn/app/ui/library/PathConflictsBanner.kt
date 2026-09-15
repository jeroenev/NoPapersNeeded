package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.index.PathConflict
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_path_conflict
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PathConflictsBanner(conflicts: List<PathConflict>) {
    if (conflicts.isEmpty()) return
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            for (conflict in conflicts) {
                Text(
                    stringResource(Res.string.library_path_conflict, conflict.currentPath, conflict.intendedPath),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
