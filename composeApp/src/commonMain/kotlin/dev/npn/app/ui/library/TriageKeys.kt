package dev.npn.app.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import dev.npn.app.ui.common.keyShortcuts

/**
 * The triage mode's keys, worn by its focused container: J and the right arrow move on (a skip,
 * which marks nothing), K and the left arrow back, both staying inside the mode at the ends, and
 * Escape leaves the mode.
 *
 * A focused text field or its suggestion menu takes the keys it uses first, so typing a title never
 * jumps to another document and Escape first closes what is open, as it does elsewhere in the app;
 * the container sees only what the field leaves.
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
