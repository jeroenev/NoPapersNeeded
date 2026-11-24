@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource

/**
 * The top bar of a screen opened from another one: a back arrow, [title] with an optional
 * [subtitle] under it, then [actions]. The arrow is a [TooltipIconButton] like every bar action, so
 * it names itself on hover as well as to a screen reader.
 */
@Composable
fun BackTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        modifier = modifier,
        navigationIcon = {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                label = stringResource(Res.string.common_back),
                onClick = onBack,
            )
        },
        actions = actions,
    )
}
