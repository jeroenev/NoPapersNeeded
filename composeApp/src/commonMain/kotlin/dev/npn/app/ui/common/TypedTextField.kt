package dev.npn.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.VisualTransformation

/**
 * A single-line text field for a value whose owner takes longer to save than keys take to arrive (a
 * settings file written out whole). It keeps what is typed itself, so a key is never applied to a
 * value still on its way back; it hands each edit to [onValueChange], and says when the field loses
 * focus ([onDone]) so the owner can save then rather than wait.
 *
 * [value] replaces what is typed only when it changes to something this field did not type. Its own
 * keystrokes come back through the owner, an earlier one possibly after later ones, and are no edit;
 * a change made elsewhere (a reset, a pick from a list) is.
 */
@Composable
fun TypedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var text by remember { mutableStateOf(value) }
    var seen by remember { mutableStateOf(value) }
    val unconfirmed = remember { ArrayDeque<String>() }
    if (value != seen) {
        seen = value
        val echo = unconfirmed.indexOf(value)
        if (echo >= 0) {
            repeat(echo + 1) { unconfirmed.removeFirst() }
        } else {
            unconfirmed.clear()
            text = value
        }
    }
    val focus = remember { FocusEdge() }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            unconfirmed.addLast(typed)
            onValueChange(typed)
        },
        label = { Text(label) },
        supportingText = supportingText,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        singleLine = true,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (focus.lost(it.isFocused)) onDone() },
    )
}

/** Whether a field held focus, to tell the change that takes it away from every other one. */
private class FocusEdge {
    private var focused = false

    fun lost(nowFocused: Boolean): Boolean = (focused && !nowFocused).also { focused = nowFocused }
}
