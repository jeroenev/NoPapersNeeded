package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Desktop convenience: a press with the secondary (right) mouse button is consumed on the
 * [PointerEventPass.Initial] pass (so tap/long-press detectors never see it) and reported with
 * its position, local to this node, typically to open a context menu there. Inert on touch:
 * fingers never press the secondary button.
 */
@Composable
fun Modifier.onSecondaryClick(action: (Offset) -> Unit): Modifier {
    val current by rememberUpdatedState(action)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val press = event.changes.firstOrNull { it.pressed && !it.previousPressed } ?: continue
                if (event.buttons.isSecondaryPressed) {
                    press.consume()
                    current(press.position)
                }
            }
        }
    }
}
