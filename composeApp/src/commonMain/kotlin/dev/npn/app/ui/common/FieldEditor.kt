package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.window.PopupProperties
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
import no_papers_needed.composeapp.generated.resources.ux_show_suggestions
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
 * type. [onCommit] receives every keystroke or pick, already in canonical form when the input
 * parses; input the type refuses is passed through verbatim so the draft can flag it and block
 * the save, and the field shows an inline error instead of silently discarding what was typed.
 * A stored value that does not fit the type is shown as-is with the same warning and is never
 * rewritten.
 */
@Composable
fun FieldEditor(
    def: FieldDef,
    value: String?,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<String> = emptyList(),
    placeholder: String? = null,
    locale: FieldLocale = rememberFieldLocale(),
) {
    when (def.type) {
        FieldType.TEXT -> {
            SuggestField(def.label, value.orEmpty(), suggestions, def.hint, placeholder, modifier, onCommit)
        }

        FieldType.LONG_TEXT -> {
            LongTextField(def.label, value.orEmpty(), def.hint, modifier, onCommit)
        }

        FieldType.NUMBER, FieldType.DECIMAL -> {
            NumberField(def, value.orEmpty(), locale, modifier, onCommit)
        }

        FieldType.DATE -> {
            DateField(def, value.orEmpty(), modifier, onCommit)
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

@Composable
private fun SuggestField(
    label: String,
    initial: String,
    suggestions: List<String>,
    hint: String?,
    placeholder: String?,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    var text by rememberFieldText(initial)
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(text, suggestions) { suggestionsFor(text, suggestions) }
    Box(modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onCommit(it)
                // Match against the value just typed: [filtered] still describes the previous one.
                expanded = it.isNotBlank() && suggestionsFor(it, suggestions).isNotEmpty()
            },
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
            supportingText = hint?.let { { Text(it) } },
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(Res.string.ux_show_suggestions),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
        ) {
            filtered.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s) },
                    onClick = {
                        expanded = false
                        text = s
                        onCommit(s)
                    },
                )
            }
        }
    }
}

/** Existing values worth offering for [text]; an exact match offers nothing (it is already typed). */
private fun suggestionsFor(
    text: String,
    suggestions: List<String>,
): List<String> {
    val query = text.trim()
    if (query.isEmpty()) return suggestions.take(MAX_INLINE_SUGGESTIONS)
    return suggestions.filter { it.contains(query, ignoreCase = true) && it != query }.take(MAX_INLINE_SUGGESTIONS)
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
 * Numbers are typed the way the locale writes them and stored canonically. The field shows the
 * canonical value back in locale form, so `1.5` stored reads `1,5` under a comma locale, and a
 * partial or off-type entry is shown exactly as typed with the error underneath.
 */
@Composable
private fun NumberField(
    def: FieldDef,
    canonical: String,
    locale: FieldLocale,
    modifier: Modifier,
    onCommit: (String) -> Unit,
) {
    val shown = remember(canonical, locale) { FieldValues.display(def.type, canonical, locale) }
    var text by rememberFieldText(shown)
    val invalid = text.isNotBlank() && FieldValues.canonicalize(def.type, text, locale) is FieldParse.Invalid
    val error = if (invalid) invalidNumberText(def.type) else null
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            onCommit(
                when (val parsed = FieldValues.canonicalize(def.type, typed, locale)) {
                    is FieldParse.Ok -> parsed.canonical.orEmpty()
                    is FieldParse.Invalid -> parsed.raw
                },
            )
        },
        isError = invalid,
        label = { Text(def.label) },
        supportingText = (error ?: def.hint)?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
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
internal fun invalidNumberText(type: FieldType): String =
    stringResource(
        if (type == FieldType.DECIMAL) Res.string.common_field_invalid_decimal else Res.string.common_field_invalid_number,
    )

private const val LONG_TEXT_MIN_LINES = 4
private const val LONG_TEXT_MAX_LINES = 10
