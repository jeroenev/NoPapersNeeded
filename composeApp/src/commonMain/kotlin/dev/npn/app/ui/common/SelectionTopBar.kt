@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_more_actions
import no_papers_needed.composeapp.generated.resources.common_select_all
import no_papers_needed.composeapp.generated.resources.library_selected_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The top bar a screen shows while something is selected: how many items ([count]), a close button
 * that ends the selection ([onClose]), and what to do with them, [inline] as icons and [overflow]
 * behind the "more" menu ([TopBarActions]).
 *
 * "Select all" ([onSelectAll], disabled while null) leads the overflow menu when there is one, and
 * the icons otherwise.
 */
@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: (() -> Unit)?,
    inline: List<TopBarAction>,
    modifier: Modifier = Modifier,
    overflow: List<TopBarAction> = emptyList(),
) {
    val selectAll =
        TopBarAction(
            icon = Icons.Filled.SelectAll,
            label = stringResource(Res.string.common_select_all),
            onClick = { onSelectAll?.invoke() },
            enabled = onSelectAll != null,
        )
    TopAppBar(
        title = {
            Text(
                pluralStringResource(Res.plurals.library_selected_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            TooltipIconButton(Icons.Filled.Clear, stringResource(Res.string.common_cancel), onClose)
        },
        actions = {
            TopBarActions(
                inline = if (overflow.isEmpty()) listOf(selectAll) + inline else inline,
                overflow = if (overflow.isEmpty()) emptyList() else listOf(selectAll) + overflow,
                overflowLabel = stringResource(Res.string.common_more_actions),
            )
        },
    )
}
