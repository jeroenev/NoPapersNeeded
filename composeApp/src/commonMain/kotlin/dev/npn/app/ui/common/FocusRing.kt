package dev.npn.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width of the focused ring, in dp. */
private val FocusRingWidth = 2.dp

/**
 * A visible focus ring around a custom clickable surface (a document row, a tag card, a queue
 * entry): Compose's ripple says little on a desktop keyboard, so keyboard and switch users need to
 * see where focus is. The ring is drawn over the node's own content, so it stays visible on a card
 * that paints its own surface, changes no size, and appears only while the node itself holds focus.
 * Standard Material buttons and text fields draw their own, so they do not take this.
 *
 * [radius] should match the surface's corner radius where it has one (Material's 12dp cards are the
 * default). The stroke is inset by half its width so it stays inside the node's bounds.
 *
 * A composable modifier: call it where the surface is composed, before the `clickable` whose focus
 * it follows.
 */
@Composable
fun Modifier.focusRing(radius: Dp = 12.dp): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    val stroke = FocusRingWidth
    return onFocusChanged { focused = it.isFocused }
        .then(
            if (!focused) {
                Modifier
            } else {
                Modifier.drawWithContent {
                    drawContent()
                    val half = stroke.toPx() / 2
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(half, half),
                        size = Size(size.width - stroke.toPx(), size.height - stroke.toPx()),
                        cornerRadius = CornerRadius(radius.toPx()),
                        style = Stroke(width = stroke.toPx()),
                    )
                }
            },
        )
}
