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
    refused: Boolean,
) {
    /** Exactly what is typed, including a partial or off-type entry. */
    var text by mutableStateOf(shown(value, refused))
        private set

    /** The value [follow] last took, and whether it was refused, so only a change to it can replace [text]. */
    private var followed by mutableStateOf(value to refused)

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
     * Take the value the field's owner holds: a canonical value, or, when [refused], an entry this
     * field refused, as typed. Only a change to it that [text] does not already stand for replaces
     * [text]: each keystroke comes back here in canonical form (`12.0` as `12`) or as the refused
     * entry it is, and a bound's partial entry (`1.`) does not come back at all.
     */
    internal fun follow(
        value: String?,
        refused: Boolean,
    ) {
        if (value to refused == followed) return
        followed = value to refused
        if (!holds(value, refused)) text = shown(value, refused)
    }

    /** A value the locale's way; a refused entry as typed, since it is no number to show another way. */
    private fun shown(
        value: String?,
        refused: Boolean,
    ): String = if (refused) value.orEmpty() else value?.let { FieldValues.display(type, it, locale) }.orEmpty()

    /**
     * Whether [text] is what [value] stands for: a number [text] reads as (comparing canonical forms,
     * the value read as a stored one is, [FieldValues.normalized]), or the same refused entry.
     */
    private fun holds(
        value: String?,
        refused: Boolean,
    ): Boolean {
        val held = value?.trim()?.ifEmpty { null }
        return when (val read = parsed) {
            is FieldParse.Ok -> !refused && read.canonical == held?.let { FieldValues.normalized(type, it) }
            is FieldParse.Invalid -> refused && read.raw == held
        }
    }
}

/**
 * The [NumberInput] for a number of [def]'s type holding the canonical [value] (null for none), or,
 * when [refused], an entry the field refused, as typed. It shows a value in [locale] form, so `1.5`
 * reads `1,5` under a comma locale, and keeps what is typed until [value] changes to something the
 * text does not already stand for, such as another document's value or a cleared filter.
 */
@Composable
fun rememberNumberInput(
    def: FieldDef,
    value: String?,
    locale: FieldLocale,
    refused: Boolean = false,
): NumberInput {
    val input = remember(def.type, locale) { NumberInput(def.type, locale, value, refused) }
    input.follow(value, refused)
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
