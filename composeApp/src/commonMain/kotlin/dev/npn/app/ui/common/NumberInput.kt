package dev.npn.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldLocale
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues

/**
 * What a number field shows and what it means: typed the way [locale] writes numbers, read back in
 * canonical form ([FieldValues.canonicalize]). The document panel's editor and the filter sheet's
 * range bounds both hold one, so a number is entered alike wherever it is asked for.
 */
@Stable
class NumberInput internal constructor(
    val type: FieldType,
    private val locale: FieldLocale,
    text: MutableState<String>,
) {
    /** Exactly what is typed, including a partial or off-type entry. */
    var text by text
        private set

    /** What [text] means for [type]. */
    val parsed: FieldParse get() = FieldValues.canonicalize(type, text, locale)

    /** Whether [text] is an entry [type] refuses; a blank field is not one. */
    val invalid: Boolean get() = text.isNotBlank() && parsed is FieldParse.Invalid

    /** Show [typed] and say what it means. */
    fun type(typed: String): FieldParse {
        text = typed
        return parsed
    }
}

/**
 * The [NumberInput] for a number of [def]'s type holding the canonical [value] (null for none). It
 * shows the value in [locale] form, so `1.5` reads `1,5` under a comma locale, and takes a new
 * [value] from elsewhere as an edit; see [rememberFieldText].
 */
@Composable
fun rememberNumberInput(
    def: FieldDef,
    value: String?,
    locale: FieldLocale,
): NumberInput {
    val shown =
        remember(def.type, value, locale) { value?.let { FieldValues.display(def.type, it, locale) }.orEmpty() }
    val text = rememberFieldText(shown)
    return remember(def.type, locale, text) { NumberInput(def.type, locale, text) }
}

/**
 * The text field of a [NumberInput]: a number keyboard, and the type's error under an entry it
 * refuses, shown exactly as typed. [onInput] gets what each keystroke parses to; [hint] shows while
 * there is no error.
 */
@Composable
fun NumberInputField(
    input: NumberInput,
    label: String,
    onInput: (FieldParse) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val error = if (input.invalid) invalidNumberText(input.type) else null
    OutlinedTextField(
        value = input.text,
        onValueChange = { onInput(input.type(it)) },
        isError = input.invalid,
        label = { Text(label) },
        supportingText = (error ?: hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
