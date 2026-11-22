package dev.npn.app.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.npn.app.ui.common.keyShortcuts

/**
 * The triage mode's keys, worn by its focused container: J and the right arrow move on (a skip,
 * which marks nothing), K and the left arrow back, both staying inside the mode at the ends.
 *
 * A focused text field takes its own letters and arrows first, so typing a title never jumps to
 * another document; the container sees the keys once the field does not.
 */
internal fun Modifier.triageKeys(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.J, Key.DirectionRight -> onNext
            Key.K, Key.DirectionLeft -> onPrevious
            else -> null
        }
    }

/**
 * Escape leaves the mode, and sees the key before a focused field does: a draft is saved by the
 * editor's own dispose path, so leaving from inside a field is no different from leaving the mode.
 */
internal fun Modifier.triageEscapeKey(onExit: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
            onExit()
            true
        } else {
            false
        }
    }
