package dev.npn.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
    value: String?,
) {
    /** Exactly what is typed, including a partial or off-type entry. */
    var text by mutableStateOf(shown(value))
        private set

    /** The value [follow] last took, so only a change to it can replace [text]. */
    private var followed by mutableStateOf(value)

    /** What [text] means for [type]. */
    val parsed: FieldParse get() = FieldValues.canonicalize(type, text, locale)

    /** Whether [text] is an entry [type] refuses; a blank field is not one. */
    val invalid: Boolean get() = text.isNotBlank() && parsed is FieldParse.Invalid

    /** Show [typed] and say what it means. */
    fun type(typed: String): FieldParse {
        text = typed
        return parsed
    }

    /**
     * Take the value the field's owner holds. Only a change to it that means something other than
     * [text] replaces [text]: each keystroke comes back here in canonical form (`12.0` as `12`), and
     * a bound's partial entry (`1.`) does not come back at all.
     */
    internal fun follow(value: String?) {
        if (value == followed) return
        followed = value
        if (meaning(value, FieldLocale.CANONICAL) != meaning(text, locale)) text = shown(value)
    }

    private fun shown(value: String?): String = value?.let { FieldValues.display(type, it, locale) }.orEmpty()

    /**
     * What [entry], written the [entryLocale] way, stands for: the value it commits (canonical, or a
     * refused entry as typed), read as a stored value is ([FieldValues.normalized]).
     */
    private fun meaning(
        entry: String?,
        entryLocale: FieldLocale,
    ): String =
        when (val parsed = FieldValues.canonicalize(type, entry, entryLocale)) {
            is FieldParse.Ok -> parsed.canonical.orEmpty()
            is FieldParse.Invalid -> FieldValues.normalized(type, parsed.raw)
        }
}

/**
 * The [NumberInput] for a number of [def]'s type holding the canonical [value] (null for none). It
 * shows the value in [locale] form, so `1.5` reads `1,5` under a comma locale, and keeps what is
 * typed until [value] changes to something the text does not already mean, such as another
 * document's value or a cleared filter.
 */
@Composable
fun rememberNumberInput(
    def: FieldDef,
    value: String?,
    locale: FieldLocale,
): NumberInput {
    val input = remember(def.type, locale) { NumberInput(def.type, locale, value) }
    input.follow(value)
    return input
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
