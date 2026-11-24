package dev.npn.app.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import dev.npn.app.ui.common.keyShortcuts
import dev.npn.app.ui.common.previewKeyShortcuts

/**
 * The triage mode's keys, worn by its focused container: J and the right arrow move on (a skip,
 * which marks nothing), K and the left arrow back, both staying inside the mode at the ends, and
 * Escape leaves the mode.
 *
 * A focused text field or its suggestion menu takes the keys it uses first (the arrows move its
 * cursor, Escape first closes what is open, as it does elsewhere in the app); the container sees only
 * what the field leaves. J and K are letters, which a desktop field takes only as the typed
 * character and not as the key press, so the metadata pane wears [triageFieldKeys] to keep them.
 */
internal fun Modifier.triageKeys(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onExit: () -> Unit,
): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.J, Key.DirectionRight -> onNext
            Key.K, Key.DirectionLeft -> onPrevious
            Key.Escape -> onExit
            else -> null
        }
    }

/**
 * Worn by the metadata pane, whose fields J and K are typed into: their key presses stop here and
 * never reach [triageKeys]. On desktop a text field inserts the character from its own typed event
 * and leaves the key press unconsumed, so without this a title with a J in it would skip to the next
 * document halfway through the word. The arrows and Escape still pass on.
 */
internal fun Modifier.triageFieldKeys(): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.J, Key.K -> KeepTyping
            else -> null
        }
    }

/** A J or K pressed in the metadata pane: the field has typed it, and the mode does nothing. */
private val KeepTyping: () -> Unit = {}

/** Preview controls use arrows for pages; triage reserves them for document navigation. */
internal fun Modifier.triagePreviewKeys(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier =
    previewKeyShortcuts { event ->
        when (event.key) {
            Key.DirectionRight -> onNext
            Key.DirectionLeft -> onPrevious
            else -> null
        }
    }
