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
import androidx.compose.ui.text.input.KeyboardType
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_number_out_of_range
import org.jetbrains.compose.resources.stringResource

/**
 * Bounded integer entry field. Keeps its own text, so the user can clear it while typing, and
 * commits via [onValueChange] only a parse inside [range]; anything else stays on screen marked as
 * an error and is not committed. Input is filtered to at most [maxDigits] digits.
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
    maxDigits: Int,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toString()) }
    val echoes = remember { EchoTracker(value) }
    echoes.ownerValue(value)?.let { text = it.toString() }
    val outOfRange = text.toIntOrNull()?.let { it !in range } ?: true
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(maxDigits)
            text.toIntOrNull()?.takeIf { it in range }?.let {
                echoes.typed(it)
                onValueChange(it)
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
        modifier = modifier.fillMaxWidth(),
    )
}
