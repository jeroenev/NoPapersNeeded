package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.model.FieldDef

/**
 * An "add a field" button, [label]led, opening a menu of the registered [fields] to [onPick] from,
 * and last a [newLabel] item that runs [onNew] to define one, when given. A [outlined] false button
 * is the quieter variant for a form already full of controls.
 */
@Composable
fun AddFieldMenu(
    label: String,
    fields: List<FieldDef>,
    onPick: (FieldDef) -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    newLabel: String? = null,
    onNew: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        if (outlined) {
            OutlinedButton(onClick = { expanded = true }) { AddFieldButtonContent(label) }
        } else {
            TextButton(onClick = { expanded = true }) { AddFieldButtonContent(label) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            fields.forEach { def ->
                DropdownMenuItem(
                    text = { Text(def.label) },
                    onClick = {
                        expanded = false
                        onPick(def)
                    },
                )
            }
            newLabel?.let {
                DropdownMenuItem(
                    text = { Text(it) },
                    onClick = {
                        expanded = false
                        onNew()
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.AddFieldButtonContent(label: String) {
    Icon(Icons.Filled.Add, contentDescription = null)
    Text(label, modifier = Modifier.padding(start = 8.dp))
    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
}
