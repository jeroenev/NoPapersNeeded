package dev.npn.app.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Corner radius of the ring drawn around a focused list row or card. */
private val FocusRingShape = RoundedCornerShape(8.dp)

/**
 * A visible focus ring around a custom clickable surface (a document row, a tag card, a queue
 * entry): Compose's ripple says little on a desktop keyboard, so keyboard and switch users need to
 * see where focus is. The ring is drawn inside the node's bounds, so it changes no size, and it
 * appears only while the node itself holds focus. Standard Material buttons and text fields draw
 * their own, so they do not take this.
 *
 * A composable modifier: call it where the surface is composed, before the `clickable` whose focus
 * it follows.
 */
@Composable
fun Modifier.focusRing(shape: Shape = FocusRingShape): Modifier {
    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
}
