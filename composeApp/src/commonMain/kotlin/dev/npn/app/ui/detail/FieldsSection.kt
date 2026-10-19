@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.AddFieldMenu
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.FieldDefForm
import dev.npn.app.ui.common.FieldDefFormState
import dev.npn.app.ui.common.FieldEditor
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.jsonSaver
import dev.npn.app.ui.common.rememberFieldLocale
import dev.npn.core.app.Doc
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_add_to_fields
import no_papers_needed.composeapp.generated.resources.detail_new_field
import no_papers_needed.composeapp.generated.resources.detail_new_field_menu
import no_papers_needed.composeapp.generated.resources.detail_orphan_fields
import no_papers_needed.composeapp.generated.resources.detail_remove_field
import no_papers_needed.composeapp.generated.resources.detail_title_hint
import org.jetbrains.compose.resources.stringResource

/**
 * The document's fields, driven by the registry: fields marked "show on every document" render
 * always, other registered fields render when this document carries a value (or the user just
 * added them), and values whose key has no definition sit in a muted group with a way to promote
 * the key into the registry. One "Add field" affordance covers both picking an existing
 * definition and defining a new one. [refused] are the keys whose value in [doc] is an entry its
 * editor refused, as typed, so a field coming back on screen shows it as it was typed.
 */
@Composable
internal fun FieldsSection(
    doc: Doc,
    refused: Set<String>,
    defs: List<FieldDef>,
    vocabulary: VocabularyState,
    /** Keys the user picked from "Add field"; they render empty until a value lands. */
    revealed: Set<String>,
    actions: MetadataActions,
) {
    var newField by rememberSaveable(
        stateSaver = jsonSaver(NewFieldSeed.serializer().nullable),
    ) { mutableStateOf(null) }
    val shown = defs.filter { it.showByDefault || it.key in doc.fields || it.key in revealed }
    val orphanKeys = (doc.fields.keys - defs.map { it.key }.toSet()).sorted()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (def in shown) {
            key(def.key) {
                FieldRow(
                    def = def,
                    value = doc.fields[def.key],
                    refused = def.key in refused,
                    suggestions = vocabulary.fieldValues[def.key].orEmpty(),
                    // The title stands in for the file name when empty, so the file name is its placeholder.
                    placeholder = doc.fileName.takeIf { def.key == FieldKeys.TITLE },
                    onCommit = { actions.commitField(def.key, it) },
                ) {
                    if (!def.showByDefault) RemoveFieldButton(def.label) { actions.removeField(def.key) }
                }
                // One hint per field: the title's seeded one also says how the file is renamed, so
                // this note stands in only for a title without a hint.
                if (def.key == FieldKeys.TITLE && def.hint == null) {
                    HelpText(stringResource(Res.string.detail_title_hint))
                }
            }
        }
        if (orphanKeys.isNotEmpty()) {
            Text(
                stringResource(Res.string.detail_orphan_fields),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (orphan in orphanKeys) {
                key("orphan:$orphan") {
                    val value = doc.fields.getValue(orphan)
                    // Shown verbatim as text: without a definition there is no type to judge it by.
                    FieldRow(def = orphanDef(orphan), value = value, onCommit = { actions.commitField(orphan, it) }) {
                        TooltipIconButton(
                            Icons.AutoMirrored.Filled.PlaylistAdd,
                            stringResource(Res.string.detail_add_to_fields),
                            onClick = { newField = NewFieldSeed(key = orphan, label = orphan, value = value) },
                        )
                        RemoveFieldButton(orphan) { actions.removeField(orphan) }
                    }
                }
            }
        }
        // "Add field": every definition not on screen, then "New field…".
        AddFieldMenu(
            label = stringResource(Res.string.detail_add_field),
            fields = defs.filter { it !in shown },
            onPick = { actions.revealField(it.key) },
            newLabel = stringResource(Res.string.detail_new_field_menu),
            onNew = { newField = NewFieldSeed() },
        )
    }

    newField?.let { seed ->
        NewFieldDialog(
            seed = seed,
            existing = defs,
            keySuggestions = vocabulary.orphanKeys,
            onConfirm = { def, value ->
                actions.defineField(def, value)
                newField = null
            },
            onDismiss = { newField = null },
        )
    }
}

/** One field's editor, followed by the [actions] offered on it, such as removing it. */
@Composable
private fun FieldRow(
    def: FieldDef,
    value: String?,
    onCommit: (FieldParse) -> Unit,
    refused: Boolean = false,
    suggestions: List<String> = emptyList(),
    placeholder: String? = null,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldEditor(
            def = def,
            value = value,
            refused = refused,
            suggestions = suggestions,
            placeholder = placeholder,
            onCommit = onCommit,
            modifier = Modifier.weight(1f),
        )
        actions()
    }
}

@Composable
private fun RemoveFieldButton(
    label: String,
    onRemove: () -> Unit,
) {
    TooltipIconButton(Icons.Filled.Close, stringResource(Res.string.detail_remove_field, label), onClick = onRemove)
}

/** A stand-in definition for an unregistered key: plain text, labelled by its key. */
private fun orphanDef(key: String) =
    FieldDef(key = key, label = key, type = FieldType.TEXT, showByDefault = false, aiEnabled = false, position = 0)

/**
 * What the "New field" dialog opens with: empty, or an orphan key being promoted with its value.
 * Saved as JSON, so the dialog is open again after the screen is recreated.
 */
@Serializable
internal data class NewFieldSeed(
    val key: String = "",
    val label: String = "",
    val value: String? = null,
)

/**
 * Define a field from the document panel: the shared [FieldDefForm] plus an optional first value
 * for this document, edited with the control of the chosen type so what gets saved is already
 * canonical. Confirming writes the definition and the value together through [onConfirm], and is
 * blocked while the value's editor refuses what it shows (a type switch drops a value the new
 * type's editor would refuse).
 */
@Composable
internal fun NewFieldDialog(
    seed: NewFieldSeed,
    existing: List<FieldDef>,
    keySuggestions: List<String>,
    onConfirm: (FieldDef, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var form by rememberSaveable(stateSaver = jsonSaver(FieldDefFormState.serializer())) {
        mutableStateOf(FieldDefFormState(label = seed.label, key = seed.key, keyEdited = seed.key.isNotEmpty()))
    }
    var value by rememberSaveable { mutableStateOf(seed.value.orEmpty()) }
    // The editor's verdict on what it shows: it reads a number the way the user writes it, so only
    // it can tell that `12.34` is none where a comma is the decimal point. [value] is then as typed.
    var refused by rememberSaveable { mutableStateOf(false) }
    val locale = rememberFieldLocale()
    val draftDef = form.toDef()
    val valueValid = !refused && (value.isBlank() || FieldValues.canonicalize(form.type, value) is FieldParse.Ok)
    val canSave = form.canSave(existing) && valueValid
    // Switching type can strand the typed value. The new type's editor shows it the locale's way and
    // reads it back the same way: keep what it reads, in canonical form, and drop what it would
    // refuse, or what the old editor refused, so the dialog never confirms a value its editor does
    // not show, or one that would fail to write (the definition is committed before the value, so a
    // rejected value would otherwise leave the field half-applied).
    LaunchedEffect(form.type) {
        val read = FieldValues.canonicalize(form.type, FieldValues.display(form.type, value, locale), locale)
        value = if (refused) "" else (read as? FieldParse.Ok)?.canonical.orEmpty()
        refused = false
    }

    ConfirmDialog(
        title = stringResource(Res.string.detail_new_field),
        onConfirm = { draftDef?.let { onConfirm(it, value.ifBlank { null }) } },
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_save),
        confirmEnabled = canSave,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FieldDefForm(
                state = form,
                onChange = { form = it },
                existing = existing,
                keySuggestions = keySuggestions,
            )
            draftDef?.let { def ->
                key(def.type) {
                    FieldEditor(
                        def = def,
                        value = value,
                        refused = refused,
                        locale = locale,
                        onCommit = { entry ->
                            refused = entry is FieldParse.Invalid
                            value =
                                when (entry) {
                                    is FieldParse.Ok -> entry.canonical.orEmpty()
                                    is FieldParse.Invalid -> entry.raw
                                }
                        },
                    )
                }
            }
        }
    }
}
