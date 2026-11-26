package dev.npn.app.ui.nav

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SectionHeading
import dev.npn.app.ui.common.keyShortcuts
import dev.npn.app.ui.common.previewKeyShortcuts
import dev.npn.app.ui.common.typedKeyShortcuts
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.nav_shortcut_assistant_newline
import no_papers_needed.composeapp.generated.resources.nav_shortcut_assistant_send
import no_papers_needed.composeapp.generated.resources.nav_shortcut_back
import no_papers_needed.composeapp.generated.resources.nav_shortcut_command_palette
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_find
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_first_last
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_next_doc
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_page
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_resize
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_save
import no_papers_needed.composeapp.generated.resources.nav_shortcut_doc_zoom
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_arrows
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_a
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_arrows
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_enter
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_f
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_k
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_o
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_s
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_ctrl_shift_l
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_delete
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_enter
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_escape
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_help
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_home_end
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_pages
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_search
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_shift_enter
import no_papers_needed.composeapp.generated.resources.nav_shortcut_keys_zoom
import no_papers_needed.composeapp.generated.resources.nav_shortcut_library_delete
import no_papers_needed.composeapp.generated.resources.nav_shortcut_library_escape
import no_papers_needed.composeapp.generated.resources.nav_shortcut_library_import
import no_papers_needed.composeapp.generated.resources.nav_shortcut_library_search
import no_papers_needed.composeapp.generated.resources.nav_shortcut_library_select_all
import no_papers_needed.composeapp.generated.resources.nav_shortcut_lock_now
import no_papers_needed.composeapp.generated.resources.nav_shortcut_show_list
import no_papers_needed.composeapp.generated.resources.nav_shortcut_tip_long_press
import no_papers_needed.composeapp.generated.resources.nav_shortcut_tip_page_counter
import no_papers_needed.composeapp.generated.resources.nav_shortcut_tip_right_click
import no_papers_needed.composeapp.generated.resources.nav_shortcut_triage_exit
import no_papers_needed.composeapp.generated.resources.nav_shortcut_triage_review
import no_papers_needed.composeapp.generated.resources.nav_shortcut_triage_skip
import no_papers_needed.composeapp.generated.resources.nav_shortcuts
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_assistant
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_close
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_document
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_everywhere
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_library
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_tips
import no_papers_needed.composeapp.generated.resources.nav_shortcuts_triage
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** One line of the list: the [keys] as the user presses them, and what they [does]. */
private class Shortcut(
    val keys: StringResource,
    val does: StringResource,
)

/** A [title]d group of [shortcuts], one per screen that binds them. */
private class ShortcutGroup(
    val title: StringResource,
    val shortcuts: List<Shortcut>,
)

/**
 * The shortcuts the screens bind, in the order a newcomer meets them. The bindings live with their
 * screens (`libraryKeys`, `DocumentKeys.kt`, `TriageKeys.kt`, the chat's input row); a key added or changed there
 * belongs here too.
 */
private val SHORTCUTS =
    listOf(
        ShortcutGroup(
            Res.string.nav_shortcuts_everywhere,
            listOf(
                Shortcut(Res.string.nav_shortcut_keys_help, Res.string.nav_shortcut_show_list),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_k, Res.string.nav_shortcut_command_palette),
                Shortcut(Res.string.nav_shortcut_keys_escape, Res.string.nav_shortcut_back),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_shift_l, Res.string.nav_shortcut_lock_now),
            ),
        ),
        ShortcutGroup(
            Res.string.nav_shortcuts_library,
            listOf(
                Shortcut(Res.string.nav_shortcut_keys_search, Res.string.nav_shortcut_library_search),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_o, Res.string.nav_shortcut_library_import),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_a, Res.string.nav_shortcut_library_select_all),
                Shortcut(Res.string.nav_shortcut_keys_delete, Res.string.nav_shortcut_library_delete),
                Shortcut(Res.string.nav_shortcut_keys_escape, Res.string.nav_shortcut_library_escape),
            ),
        ),
        ShortcutGroup(
            Res.string.nav_shortcuts_document,
            listOf(
                Shortcut(Res.string.nav_shortcut_keys_pages, Res.string.nav_shortcut_doc_page),
                Shortcut(Res.string.nav_shortcut_keys_home_end, Res.string.nav_shortcut_doc_first_last),
                Shortcut(Res.string.nav_shortcut_keys_zoom, Res.string.nav_shortcut_doc_zoom),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_arrows, Res.string.nav_shortcut_doc_next_doc),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_s, Res.string.nav_shortcut_doc_save),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_f, Res.string.nav_shortcut_doc_find),
                Shortcut(Res.string.nav_shortcut_keys_arrows, Res.string.nav_shortcut_doc_resize),
            ),
        ),
        ShortcutGroup(
            Res.string.nav_shortcuts_triage,
            listOf(
                Shortcut(Res.string.nav_shortcut_keys_arrows, Res.string.nav_shortcut_triage_skip),
                Shortcut(Res.string.nav_shortcut_keys_ctrl_enter, Res.string.nav_shortcut_triage_review),
                Shortcut(Res.string.nav_shortcut_keys_escape, Res.string.nav_shortcut_triage_exit),
            ),
        ),
        ShortcutGroup(
            Res.string.nav_shortcuts_assistant,
            listOf(
                Shortcut(Res.string.nav_shortcut_keys_enter, Res.string.nav_shortcut_assistant_send),
                Shortcut(Res.string.nav_shortcut_keys_shift_enter, Res.string.nav_shortcut_assistant_newline),
            ),
        ),
    )

/** What the mouse and touch can do that nothing on screen says. */
private val TIPS =
    listOf(
        Res.string.nav_shortcut_tip_page_counter,
        Res.string.nav_shortcut_tip_long_press,
        Res.string.nav_shortcut_tip_right_click,
    )

/**
 * Opens the shortcut list on `?` (typed, so not while a text field takes it) or Ctrl+/, and the
 * command palette on Ctrl/Cmd+K even while a text field has focus. Bound once around the whole
 * shell, so the keys reach it from anywhere a screen leaves them alone.
 *
 * Keys only travel up from what holds focus, and many screens focus nothing of their own (Activity,
 * Tags, a screen whose focused element was just left), so while nothing inside the shell holds focus
 * its [ShellFocusAnchor] takes it ([KeepShellFocus], told through [onFocusHeld]), and a screen that
 * focuses something of its own takes it back.
 *
 * The shell itself is not focusable: Compose turns a Back or Escape key nothing consumed into a
 * focus exit to the nearest focusable around the focused element, which would spend the first Back
 * from a screen that focuses something (the document detail's preview) moving focus out to the
 * shell. Desktop's Escape, and on some phones (OxygenOS) the back gesture, arrive as such a key.
 */
internal fun Modifier.shellShortcutKeys(
    onFocusHeld: (Boolean) -> Unit,
    onOpenShortcuts: (() -> Unit)?,
    onPalette: () -> Unit,
): Modifier =
    onFocusChanged { onFocusHeld(it.hasFocus) }
        .previewKeyShortcuts { event ->
            if (event.key == Key.K && (event.isCtrlPressed || event.isMetaPressed)) onPalette else null
        }.keyShortcuts { event ->
            when (event.key) {
                Key.Slash -> if (event.isCtrlPressed) onOpenShortcuts else null
                else -> null
            }
        }.typedKeyShortcuts { char -> onOpenShortcuts.takeIf { char == '?' } }

/**
 * Where keyboard focus rests while no screen holds it: an empty focusable inside the shell beside
 * the screens, never around them, so leaving a focused element inside a screen does not land here.
 */
@Composable
internal fun ShellFocusAnchor(focus: FocusRequester) {
    Box(Modifier.focusRequester(focus).focusable())
}

/**
 * Gives the shell focus ([focus], its [ShellFocusAnchor]) whenever nothing in it [held] it, a frame
 * later so its node is attached first (as [dev.npn.app.ui.common.FocusOnEntry] does).
 */
@Composable
internal fun KeepShellFocus(
    focus: FocusRequester,
    held: Boolean,
) {
    LaunchedEffect(held) {
        if (held) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

/** The keyboard shortcut list, with a few tips on what the mouse and touch can do. */
@Composable
internal fun ShortcutsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.nav_shortcuts)) },
        text = {
            Column(
                Modifier.widthIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SHORTCUTS.forEach { group ->
                    SectionHeading(
                        stringResource(group.title),
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    group.shortcuts.forEach { ShortcutRow(it) }
                }
                SectionHeading(
                    stringResource(Res.string.nav_shortcuts_tips),
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
                TIPS.forEach { HelpText(stringResource(it)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.nav_shortcuts_close)) }
        },
    )
}

@Composable
private fun ShortcutRow(shortcut: Shortcut) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            stringResource(shortcut.keys),
            modifier = Modifier.weight(KEYS_WEIGHT),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(
            stringResource(shortcut.does),
            modifier = Modifier.weight(1f - KEYS_WEIGHT),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** The share of a row the keys take; the description gets the rest. */
private const val KEYS_WEIGHT = 0.4f
