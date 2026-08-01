package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow

/**
 * Collects one-shot [UiMessage]s from a [Flow] and shows each as a snackbar on [snackbar].
 *
 * The pending slot is keyed by a monotonically increasing counter (not the message itself) so a
 * structurally identical message emitted back-to-back still re-triggers the snackbar instead of
 * being swallowed by snapshot-equality skipping the recomposition.
 */
@Composable
fun SnackbarMessages(
    messages: Flow<UiMessage>,
    snackbar: SnackbarHostState,
) {
    var pending by remember { mutableStateOf<Pair<Long, UiMessage>?>(null) }
    LaunchedEffect(messages) {
        messages.collect { pending = (pending?.first?.plus(1) ?: 1L) to it }
    }
    val resolved = pending?.second?.resolve()
    LaunchedEffect(pending?.first) {
        resolved?.let { snackbar.showSnackbar(it) }
    }
}
