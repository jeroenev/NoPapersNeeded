package dev.npn.app.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import dev.npn.app.ui.common.keyShortcuts
import dev.npn.app.ui.common.previewKeyShortcuts

/**
 * The triage mode's keys, worn by its focused container: the right arrow moves on (a skip, which
 * marks nothing), the left arrow back, both staying inside the mode at the ends, Ctrl+Enter (Cmd+Enter
 * on a Mac) marks the document reviewed and moves on, and Escape leaves the mode.
 *
 * A focused text field or its suggestion menu takes the keys it uses first (the arrows move its
 * cursor, Escape first closes what is open, as it does elsewhere in the app); the container sees only
 * what the field leaves. Ctrl+Enter is the exception: it is seen before the field, so the value just
 * typed is reviewed with the rest without leaving the field first.
 */
internal fun Modifier.triageKeys(
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onReview: () -> Unit,
    onExit: () -> Unit,
): Modifier =
    previewKeyShortcuts { event ->
        onReview.takeIf { event.key == Key.Enter && (event.isCtrlPressed || event.isMetaPressed) }
    }.keyShortcuts { event ->
        when (event.key) {
            Key.DirectionRight -> onNext
            Key.DirectionLeft -> onPrevious
            Key.Escape -> onExit
            else -> null
        }
    }

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
