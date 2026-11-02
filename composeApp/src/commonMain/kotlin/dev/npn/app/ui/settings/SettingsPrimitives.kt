package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText

// The building blocks only settings panes use; the ones other screens share live in ui/common.

/** Vertical gap between an action button and its help copy. */
private val ActionSpacing = 8.dp

/** A button that performs a one-off action (open a sub-screen, clear a cache), plus help copy. */
@Composable
internal fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ActionSpacing)) {
        OutlinedButton(onClick = onClick) { Text(text = label) }
        if (help != null) HelpText(help)
    }
}

/**
 * Whether the settings pane on screen should open its Advanced disclosure: a settings search found
 * a setting behind it ([SettingsCategory.advancedKeywords]). Provided by the pane's host.
 */
internal val LocalRevealAdvanced = compositionLocalOf { false }

/**
 * Whether a pane's Advanced disclosure is open: closed at first, opened when [reveal] says a
 * settings search found something behind it, and from then on the user's to close again. A pane
 * reads it as `var advanced by rememberAdvancedExpanded()`.
 */
@Composable
internal fun rememberAdvancedExpanded(reveal: Boolean = LocalRevealAdvanced.current): MutableState<Boolean> {
    val expanded = rememberSaveable { mutableStateOf(reveal) }
    DisposableEffect(reveal) {
        if (reveal) expanded.value = true
        onDispose {}
    }
    return expanded
}
