package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Above this many options, a [Dropdown] with a search label puts a filter box atop its menu. */
private const val SEARCH_ABOVE_OPTIONS = 8

/**
 * A single-choice picker: a button that shows [selected] and the drop-down arrow, opening a menu of
 * [options]. [selected] is the already-formatted button text, so a setting can fold the value into
 * a sentence ("Provider: OpenAI"); [label] is an optional caption above the button. A [outlined]
 * false button is the quieter variant for a choice nested under another control.
 *
 * A long list given a [searchLabel] gets a filter box at the top of its menu, matching what
 * [searchText] gives for each option.
 */
@Composable
fun <T> Dropdown(
    selected: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
    outlined: Boolean = true,
    searchLabel: String? = null,
    searchText: (T) -> String = { it.toString() },
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchable = searchLabel != null && options.size > SEARCH_ABOVE_OPTIONS
    val open = {
        query = ""
        expanded = true
    }
    Column(modifier) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodySmall)
        if (outlined) {
            OutlinedButton(onClick = open, enabled = enabled) { DropdownButtonContent(selected) }
        } else {
            TextButton(onClick = open, enabled = enabled) { DropdownButtonContent(selected) }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (searchable) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(searchLabel) },
                    singleLine = true,
                    modifier = Modifier.width(280.dp).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            val shown =
                if (searchable) {
                    options.filter { searchText(it).contains(query.trim(), ignoreCase = true) }
                } else {
                    options
                }
            shown.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun RowScope.DropdownButtonContent(selected: String) {
    Text(selected)
    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
}
