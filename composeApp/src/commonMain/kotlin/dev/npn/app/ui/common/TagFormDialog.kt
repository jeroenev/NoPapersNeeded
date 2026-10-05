@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_color
import no_papers_needed.composeapp.generated.resources.common_tags_color_value
import no_papers_needed.composeapp.generated.resources.common_tags_name_hierarchy
import no_papers_needed.composeapp.generated.resources.common_tags_no_color
import no_papers_needed.composeapp.generated.resources.common_tags_no_color_marker
import org.jetbrains.compose.resources.stringResource

private val Palette =
    listOf(
        "#4C5CC7",
        "#2E7D32",
        "#C62828",
        "#EF6C00",
        "#6A1B9A",
        "#00838F",
        "#AD1457",
        "#5D4037",
        "#455A64",
        "#F9A825",
    )

/**
 * Create or style a tag: an optional name field and a color palette. Shared by the tag editor (a new
 * tag with its color) and the tags screen (restyling one tag or many).
 */
@Composable
fun TagFormDialog(
    title: String,
    initialName: String,
    initialColor: String?,
    confirmLabel: String,
    onConfirm: (name: String, color: String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    nameEditable: Boolean = true,
    /** Hidden for bulk styling, where a single dialog applies one color to many tags. */
    showNameField: Boolean = true,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (showNameField) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.common_tags_name_hierarchy)) },
                        singleLine = true,
                        enabled = nameEditable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(Res.string.common_color), style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.selectableGroup(),
                ) {
                    // "None" swatch.
                    ColorSwatch(
                        color = null,
                        selected = color == null,
                        onClick = { color = null },
                    )
                    Palette.forEach { hex ->
                        ColorSwatch(
                            color = hex,
                            selected = color?.equals(hex, ignoreCase = true) == true,
                            onClick = { color = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !showNameField || name.isNotBlank(),
                onClick = { onConfirm(name.trim(), color) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

/** Diameter of the painted swatch; the touchable area around it is [SwatchTarget]. */
private val SwatchDiameter = 36.dp

/** Interaction target, kept at the accessibility minimum even though the swatch is smaller. */
private val SwatchTarget = 48.dp

@Composable
private fun ColorSwatch(
    color: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = parseHexColor(color) ?: MaterialTheme.colorScheme.surfaceVariant
    val description =
        if (color == null) {
            stringResource(Res.string.common_tags_no_color)
        } else {
            stringResource(Res.string.common_tags_color_value, color)
        }
    // The tappable target is a full 48dp box for pointer/touch and font-scaling accessibility,
    // while the painted swatch stays compact in the centre. One `selectable` with Role.RadioButton
    // exposes the single-choice palette to assistive tech; the visible ring/check is decoration, so
    // the inner content is cleared from the semantics tree to keep one node per option.
    Box(
        Modifier
            .size(SwatchTarget)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            ).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(SwatchDiameter)
                .clip(CircleShape)
                .background(c)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ).clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) {
                Text(
                    stringResource(Res.string.common_tags_no_color_marker),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}
