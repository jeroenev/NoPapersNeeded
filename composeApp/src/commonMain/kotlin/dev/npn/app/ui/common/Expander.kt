package dev.npn.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_collapse
import no_papers_needed.composeapp.generated.resources.ai_expand
import org.jetbrains.compose.resources.stringResource

/**
 * The semantics every disclosure shares: the expand or the collapse action, whichever applies, so a
 * screen reader announces the state and can drive it. For a control that is already a button.
 */
fun Modifier.disclosure(
    expanded: Boolean,
    onToggle: () -> Unit,
): Modifier =
    semantics {
        if (expanded) {
            collapse {
                onToggle()
                true
            }
        } else {
            expand {
                onToggle()
                true
            }
        }
    }

/**
 * Makes a header show or hide what follows it: a button, never a selection (`selectable` would
 * announce a "selected" state the header does not have), with the [disclosure] semantics.
 */
fun Modifier.expander(
    expanded: Boolean,
    enabled: Boolean = true,
    onToggle: () -> Unit,
): Modifier =
    clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
        .then(if (enabled) Modifier.disclosure(expanded, onToggle) else Modifier)

/** The chevron of an expander, named after the action it performs. */
@Composable
fun ExpandIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
        contentDescription = stringResource(if (expanded) Res.string.ai_collapse else Res.string.ai_expand),
        modifier = modifier,
        tint = tint,
    )
}

/** A full-width row that expands or collapses what follows it, with [label] before the chevron. */
@Composable
fun ExpanderRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showIcon: Boolean = true,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().expander(expanded, enabled, onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        if (showIcon) ExpandIcon(expanded)
    }
}
