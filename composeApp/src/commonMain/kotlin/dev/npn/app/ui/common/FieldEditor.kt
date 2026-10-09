package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldLocale
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_choose_date
import no_papers_needed.composeapp.generated.resources.common_field_invalid_date
import no_papers_needed.composeapp.generated.resources.common_field_invalid_decimal
import no_papers_needed.composeapp.generated.resources.common_field_invalid_number
import no_papers_needed.composeapp.generated.resources.common_field_type_date
import no_papers_needed.composeapp.generated.resources.common_field_type_decimal
import no_papers_needed.composeapp.generated.resources.common_field_type_long_text
import no_papers_needed.composeapp.generated.resources.common_field_type_number
import no_papers_needed.composeapp.generated.resources.common_field_type_text
import no_papers_needed.composeapp.generated.resources.detail_iso_date_template
import org.jetbrains.compose.resources.stringResource

/** The [FieldLocale] for the app language in effect, for number entry and display. */
@Composable
fun rememberFieldLocale(): FieldLocale {
    val tag = LocalAppLocale.current
    return remember(tag) { FieldLocale.forLanguageTag(tag) }
}

/**
 * The input control for one registry field, chosen by [FieldDef.type]. This is one of the two
 * places allowed to switch exhaustively on [FieldType] (the other is [FieldValues]); everything
 * else in the UI hands a field here and lets the type decide.
 *
 * [value] is what the document holds (or the draft overlay shows): canonical when it fits the
 * type, or, when [refused], an entry this editor refused, as typed. [onCommit] receives every
 * keystroke or pick as the editor reads it: the canonical value, or an entry the type refuses
 * ([FieldParse.Invalid]), so the draft can block the save while the field shows an inline error
 * instead of silently discarding what was typed. Only the editor can give that verdict, since it
 * reads a number the way [locale] writes it. A stored value that does not fit the type is shown
 * as-is with the same warning and is never rewritten.
 */
@Composable
fun FieldEditor(
    def: FieldDef,
    value: String?,
    onCommit: (FieldParse) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    placeholder: String? = null,
    refused: Boolean = false,
    locale: FieldLocale = rememberFieldLocale(),
) {
    val commitText: (String) -> Unit = { onCommit(FieldValues.canonicalize(def.type, it)) }
    when (def.type) {
        FieldType.TEXT -> {
            TextValueField(def.label, value.orEmpty(), suggestions, def.hint, placeholder, modifier, commitText)
        }

        FieldType.LONG_TEXT -> {
            LongTextField(def.label, value.orEmpty(), def.hint, modifier, commitText)
        }

        FieldType.NUMBER, FieldType.DECIMAL -> {
            NumberField(def, value.orEmpty(), refused, locale, onCommit, modifier)
        }

        FieldType.DATE -> {
            DateField(def, value.orEmpty(), modifier, commitText)
        }
    }
}

/**
 * The text a field is showing: its own keystrokes, seeded and re-seeded from [external].
 *
 * A field cannot render [external] directly. It arrives through the draft overlay, and the draft
 * store treats a value that only differs in surrounding whitespace as no change at all — so typing
 * a space at the end of a value hands back the value without it, and the space is swallowed as the
 * user types. Comparing trimmed forms makes that round trip a no-op here too, while an edit from
 * anywhere else — switching document, discarding the draft, accepting a suggestion, picking a date
 * — still replaces what is on screen.
 */
@Composable
fun rememberFieldText(external: String): MutableState<String> {
    val text = remember { mutableStateOf(external) }
    if (external.trim() != text.value.trim()) text.value = external
    return text
}

/**
 * Single-line text field that reports every keystroke through [onCommit]. The keystrokes live in
 * local state — see [rememberFieldText].
 */
@Composable
fun CommitTextField(
    label: String,
    initial: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    onCommit: (String) -> Unit,
) {
    var text by rememberFieldText(initial)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        isError = isError,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        supportingText = supportingText?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A text value with the values other documents hold on offer. Keystrokes stay local; see [rememberFieldText]. */
@Composable
private fun TextValueField(
    label: String,
    initial: String,
    suggestions: List<String>,
    hint: String?,
    placeholder: String?,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    var text by rememberFieldText(initial)
    SuggestField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        suggestions = suggestions,
        label = label,
        modifier = modifier,
        placeholder = placeholder,
        supportingText = hint,
    )
}

@Composable
private fun LongTextField(
    label: String,
    initial: String,
    hint: String?,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    // Matters most here: a note is prose, so spaces at the end of what is typed so far are
    // constant. See [rememberFieldText].
    var text by rememberFieldText(initial)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        minLines = LONG_TEXT_MIN_LINES,
        maxLines = LONG_TEXT_MAX_LINES,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Numbers are typed the way the locale writes them and stored canonically ([NumberInput]). An
 * entry the type refuses is committed as refused, so the draft can block the save.
 */
@Composable
private fun NumberField(
    def: FieldDef,
    value: String,
    refused: Boolean,
    locale: FieldLocale,
    onCommit: (FieldParse) -> Unit,
    modifier: Modifier,
) {
    NumberInputField(
        input = rememberNumberInput(def, value, locale, refused),
        label = def.label,
        onInput = onCommit,
        modifier = modifier,
        hint = def.hint,
    )
}

/** ISO date entry with a calendar picker; both update the same value. */
@Composable
private fun DateField(
    def: FieldDef,
    value: String,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val invalid = value.isNotBlank() && !FieldValues.isIsoDate(value.trim())
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommitTextField(
            label = def.label,
            initial = value,
            placeholder = stringResource(Res.string.detail_iso_date_template),
            // Same rule the save bar enforces, so the field and the bar never disagree.
            isError = invalid,
            supportingText = if (invalid) stringResource(Res.string.common_field_invalid_date) else def.hint,
            modifier = Modifier.weight(1f),
            onCommit = onCommit,
        )
        IconButton(onClick = { showPicker = true }) {
            Icon(Icons.Filled.CalendarToday, contentDescription = stringResource(Res.string.common_choose_date))
        }
    }
    if (showPicker) {
        IsoDatePickerDialog(
            initial = value.takeIf { FieldValues.isIsoDate(it) },
            title = def.label,
            onSelect = { iso ->
                showPicker = false
                onCommit(iso.orEmpty())
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Human name of a field type, for the field dialogs and the settings field manager. */
@Composable
fun fieldTypeLabel(type: FieldType): String =
    stringResource(
        when (type) {
            FieldType.TEXT -> Res.string.common_field_type_text
            FieldType.LONG_TEXT -> Res.string.common_field_type_long_text
            FieldType.NUMBER -> Res.string.common_field_type_number
            FieldType.DECIMAL -> Res.string.common_field_type_decimal
            FieldType.DATE -> Res.string.common_field_type_date
        },
    )

/** What an entry that does not parse as a number of [type] is told, for the editor and the filter sheet. */
@Composable
internal fun invalidNumberText(type: FieldType): String {
    val text =
        if (type == FieldType.DECIMAL) {
            Res.string.common_field_invalid_decimal
        } else {
            Res.string.common_field_invalid_number
        }
    return stringResource(text)
}

private const val LONG_TEXT_MIN_LINES = 4
private const val LONG_TEXT_MAX_LINES = 10
