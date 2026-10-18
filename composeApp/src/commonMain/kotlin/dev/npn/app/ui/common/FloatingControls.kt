package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A pill of controls floating over content: the viewer's zoom and paging, the grid's zoom, the
 * compact detail screen's document stepper. Its [content] is a row, normally [TooltipIconButton]s
 * and a [PositionStepper].
 */
@Composable
fun FloatingControls(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/**
 * "‹ 3 of 41 ›": a step back, the [position] between, and a step forward, each step labelled for
 * its tooltip and disabled while its action is null (at that end). With [onPositionClick] the
 * position is a button too (the viewer's "go to page").
 */
@Composable
fun PositionStepper(
    position: String?,
    previousLabel: String,
    nextLabel: String,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onPositionClick: (() -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            label = previousLabel,
            onClick = { onPrevious?.invoke() },
            enabled = onPrevious != null,
        )
        if (position != null) {
            val text = @Composable { Text(position, style = MaterialTheme.typography.labelLarge) }
            if (onPositionClick != null) TextButton(onClick = onPositionClick) { text() } else text()
        }
        TooltipIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            label = nextLabel,
            onClick = { onNext?.invoke() },
            enabled = onNext != null,
        )
    }
}
