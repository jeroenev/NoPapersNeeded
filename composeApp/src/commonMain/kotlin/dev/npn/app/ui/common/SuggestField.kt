package dev.npn.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.PopupProperties
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ux_show_suggestions
import org.jetbrains.compose.resources.stringResource

/**
 * A single-line text field that offers the [suggestions] matching what is typed ([suggestionsFor]).
 *
 * The matches are worked out from the value being shown, never from the keystroke before it, and
 * the menu opens on typing (or from the arrow) and shows exactly while there are matches. It never
 * takes focus or the keyboard from the field; the arrow keys walk it, Enter picks and Escape closes.
 * [onPick] receives a picked suggestion, by default as if it had been typed. The arrow shows while
 * [showSuggestionsButton]; a field with an action of its own passes that as [trailingIcon] instead.
 */
@Composable
fun SuggestField(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit = onValueChange,
    enabled: Boolean = true,
    isError: Boolean = false,
    placeholder: String? = null,
    supportingText: String? = null,
    showSuggestionsButton: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val menu = remember { SuggestionMenu() }
    val matches = remember(value, suggestions) { suggestionsFor(value, suggestions) }
    val shown = menu.open && enabled && matches.isNotEmpty()
    val pick: (String) -> Unit = { suggestion ->
        menu.open = false
        onPick(suggestion)
    }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                menu.reopen(it.isNotBlank())
            },
            label = { Text(label) },
            enabled = enabled,
            isError = isError,
            placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
            supportingText = supportingText?.let { { Text(it) } },
            singleLine = true,
            trailingIcon =
                if (showSuggestionsButton && enabled && suggestions.isNotEmpty()) {
                    { ShowSuggestionsButton(onClick = { menu.reopen(!shown) }) }
                } else {
                    trailingIcon
                },
            modifier = Modifier.fillMaxWidth().suggestionKeys(menu, shown, matches, pick),
        )
        SuggestionDropdown(
            expanded = shown,
            suggestions = matches,
            active = menu.activeIn(matches.size),
            onDismiss = { menu.open = false },
            onPick = pick,
        )
    }
}

/**
 * The suggestions worth offering for [text]: the first few while nothing is typed, otherwise those
 * containing it. An exact match offers nothing, since it is already typed.
 */
internal fun suggestionsFor(
    text: String,
    suggestions: List<String>,
): List<String> {
    val query = text.trim()
    if (query.isEmpty()) return suggestions.take(MAX_INLINE_SUGGESTIONS)
    return suggestions.filter { it.contains(query, ignoreCase = true) && it != query }.take(MAX_INLINE_SUGGESTIONS)
}

/** Whether a suggestion menu is open, and which of its rows the arrow keys are on. */
@Stable
internal class SuggestionMenu {
    var open by mutableStateOf(false)
    private var active by mutableIntStateOf(0)

    /**
     * The row the arrow keys are on among [count] rows, null when there are none. Clamped as it is
     * read, since the rows can change under an open menu.
     */
    fun activeIn(count: Int): Int? = if (count > 0) active.coerceIn(0, count - 1) else null

    /** Move the arrow keys' row [by] rows, staying within [count]. */
    fun move(
        by: Int,
        count: Int,
    ) {
        activeIn(count)?.let { active = (it + by).coerceIn(0, count - 1) }
    }

    /** Open or close the menu, starting again from its first row. */
    fun reopen(open: Boolean) {
        this.open = open
        active = 0
    }
}

/** While [shown], the arrow keys walk [suggestions], Enter picks the active one and Escape closes. */
internal fun Modifier.suggestionKeys(
    menu: SuggestionMenu,
    shown: Boolean,
    suggestions: List<String>,
    onPick: (String) -> Unit,
): Modifier =
    onPreviewKeyEvent { event ->
        if (!shown || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> menu.move(1, suggestions.size)
            Key.DirectionUp -> menu.move(-1, suggestions.size)
            Key.Enter -> menu.activeIn(suggestions.size)?.let { onPick(suggestions[it]) }
            Key.Escape -> menu.open = false
            else -> return@onPreviewKeyEvent false
        }
        true
    }

/**
 * The menu under a suggesting text field. Not focusable: a focusable popup takes the keyboard, so the
 * next keystroke would go to the menu instead of the field. The row the arrow keys are on is marked,
 * on screen and as selected.
 */
@Composable
internal fun SuggestionDropdown(
    expanded: Boolean,
    suggestions: List<String>,
    active: Int?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            val isActive = index == active
            DropdownMenuItem(
                text = { Text(suggestion) },
                modifier =
                    Modifier
                        .then(
                            if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier,
                        ).semantics { selected = isActive },
                onClick = { onPick(suggestion) },
            )
        }
    }
}

@Composable
private fun ShowSuggestionsButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(Res.string.ux_show_suggestions))
    }
}
