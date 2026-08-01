package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.PopupProperties
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_clear_search
import no_papers_needed.composeapp.generated.resources.common_search_documents
import org.jetbrains.compose.resources.stringResource

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suggestions: List<String> = emptyList(),
    onSuggestionSelected: ((String) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var selectedIndex by remember { mutableStateOf(0) }
    val hasSuggestions = suggestions.isNotEmpty() && onSuggestionSelected != null
    LaunchedEffect(suggestions, focused) {
        if (focused && hasSuggestions) {
            selectedIndex = 0
            expanded = true
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                expanded = it.isNotBlank() && hasSuggestions
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange?.invoke(it.isFocused)
                    }.onPreviewKeyEvent { event ->
                        if (!expanded || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionDown -> {
                                selectedIndex = (selectedIndex + 1).coerceAtMost(suggestions.lastIndex)
                            }

                            Key.DirectionUp -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                            }

                            Key.Enter -> {
                                suggestions.getOrNull(selectedIndex)?.let { onSuggestionSelected?.invoke(it) }
                                expanded = false
                            }

                            Key.Escape -> {
                                expanded = false
                            }

                            else -> {
                                return@onPreviewKeyEvent false
                            }
                        }
                        true
                    },
            singleLine = true,
            placeholder = { Text(placeholder ?: stringResource(Res.string.common_search_documents)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }) {
                        Icon(
                            Icons.Filled.Clear,
                            contentDescription = stringResource(Res.string.common_clear_search),
                        )
                    }
                }
            },
        )
        if (hasSuggestions) {
            DropdownMenu(
                expanded = expanded && focused,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false),
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion) },
                        onClick = {
                            expanded = false
                            onSuggestionSelected(suggestion)
                        },
                    )
                }
            }
        }
    }
}
