package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_clear_search
import no_papers_needed.composeapp.generated.resources.common_search_documents
import org.jetbrains.compose.resources.stringResource

/**
 * A search box: the library's, and Settings' (with its own [placeholder] and no suggestions). Its
 * [suggestions] come ready-made from the caller (the library works them out from the index as the
 * query settles), so unlike [SuggestField] it filters nothing itself, and it reopens its menu
 * whenever new ones arrive while it has focus. The menu and its keyboard handling are
 * [SuggestField]'s.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    suggestions: List<String> = emptyList(),
    onPickSuggestion: ((String) -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    val menu = remember { SuggestionMenu() }
    var focused by remember { mutableStateOf(false) }
    val hasSuggestions = suggestions.isNotEmpty() && onPickSuggestion != null
    DisposableEffect(suggestions, focused) {
        if (focused && hasSuggestions) menu.reopen(true)
        onDispose {}
    }
    val shown = menu.open && focused && hasSuggestions
    val pick: (String) -> Unit = { suggestion ->
        menu.open = false
        onPickSuggestion?.invoke(suggestion)
    }
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                menu.open = it.isNotBlank() && hasSuggestions
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange?.invoke(it.isFocused)
                    }.suggestionKeys(menu, shown, suggestions, pick),
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
            SuggestionDropdown(
                expanded = shown,
                suggestions = suggestions,
                active = menu.activeIn(suggestions.size),
                onDismiss = { menu.open = false },
                onPick = pick,
            )
        }
    }
}
