package dev.npn.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_number_out_of_range
import org.jetbrains.compose.resources.stringResource

/**
 * Bounded integer entry field. Keeps its own text, so the user can clear it while typing, and
 * commits via [onValueChange] only a parse inside [range]; anything else stays on screen marked as
 * an error and is not committed, and [onInvalid] hears of it, so an owner that waits before saving
 * can drop the number it was last given, which the field no longer shows. Input is filtered to
 * digits, no more than [range]'s top has. [onDone] says the field lost focus.
 *
 * Re-seeds from [value] only when it changes to a number this field did not commit itself. A
 * persisted value round-trips asynchronously, so the echo of an earlier keystroke can arrive after
 * later ones; taking it as an edit would overwrite them. A change from anywhere else (a reset, a
 * clamp) still replaces what is on screen.
 */
@Composable
fun NumericField(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    label: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    onInvalid: () -> Unit = {},
    onDone: () -> Unit = {},
) {
    var text by remember { mutableStateOf(value.toString()) }
    val echoes = remember { EchoTracker(value) }
    val focus = remember { FocusEdge() }
    echoes.ownerValue(value)?.let { text = it.toString() }
    val outOfRange = text.toIntOrNull()?.let { it !in range } ?: true
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(range.last.toString().length)
            val typed = text.toIntOrNull()?.takeIf { it in range }
            if (typed == null) {
                onInvalid()
            } else {
                echoes.typed(typed)
                onValueChange(typed)
            }
        },
        label = { Text(label) },
        isError = outOfRange,
        supportingText =
            if (outOfRange) {
                { Text(stringResource(Res.string.common_number_out_of_range, range.first, range.last)) }
            } else {
                supportingText?.let { help -> { Text(help) } }
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth().onFocusChanged { if (focus.lost(it.isFocused)) onDone() },
    )
}
