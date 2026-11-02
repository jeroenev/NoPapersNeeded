package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_mode_bulk_edit
import no_papers_needed.composeapp.generated.resources.ai_mode_chat
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The assistant's two modes, one shell item between them. */
enum class AssistantMode(
    val label: StringResource,
) {
    /** Ask questions about the library. */
    CHAT(Res.string.ai_mode_chat),

    /** Suggest tags and details for many documents, reviewed before anything is written. */
    BULK_EDIT(Res.string.ai_mode_bulk_edit),
}

/**
 * The Chat | Bulk edit control at the top of both assistant screens. [onSelect] gets the other mode
 * only; the one shown is not reopened.
 */
@Composable
internal fun AssistantModeSwitch(
    selected: AssistantMode,
    onSelect: (AssistantMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        SingleChoiceSegmentedButtonRow {
            AssistantMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = mode == selected,
                    onClick = { if (mode != selected) onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, AssistantMode.entries.size),
                ) {
                    Text(stringResource(mode.label))
                }
            }
        }
    }
}
