package dev.npn.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester

/**
 * Give [requester] focus once, when this enters the composition, so key events reach it without a
 * click first. Waits one frame so the requester's node is attached to the focus owner before asking
 * (asking during the first composition warns "FocusRequester is not initialized").
 */
@Composable
fun FocusOnEntry(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}
