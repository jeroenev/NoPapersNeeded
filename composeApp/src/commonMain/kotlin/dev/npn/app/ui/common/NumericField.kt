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

/**
 * Bounded integer entry field. Keeps a local editable string so the user can clear it while typing;
 * commits via [onValueChange] only when the parse falls inside [range], and re-seeds from the
 * persisted [value] whenever it changes externally (e.g. clamped by a view model). Input is filtered
 * to at most [maxDigits] digits.
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
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.filter { it.isDigit() }.take(maxDigits)
            text.toIntOrNull()?.takeIf { it in range }?.let(onValueChange)
        },
        label = { Text(label) },
        supportingText = supportingText?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
