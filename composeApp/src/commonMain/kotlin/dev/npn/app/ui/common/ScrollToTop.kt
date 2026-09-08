package dev.npn.app.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Put the list back at the top whenever [resultsKey] changes, i.e. when the items it shows became
 * a different result set rather than an update of the current one.
 *
 * A lazy list anchors its scroll position to the key of the first visible item, so a re-sort
 * otherwise drags the user along with that item to its new place — flipping the sort order lands
 * them at the bottom of the list. `requestScrollToItem` is the counterpart to that anchoring: it
 * pins the position for the next measure pass instead of following the item key, which is the
 * measure pass that applies the new items.
 *
 * The reset therefore has to be requested before that pass, hence [SideEffect] (which runs when
 * the composition is applied, ahead of layout) rather than a launched effect, whose dispatch could
 * land after the list has already re-measured and jumped.
 *
 * Two conditions the caller has to meet:
 * - [resultsKey] must change together with the items (see [dev.npn.app.ui.library.DocumentResults]).
 *   A key that changes ahead of them resets a list that is still showing the old items, and the
 *   anchoring then undoes it on the pass that matters.
 * - The key seen on first composition does not count as a change, so a scroll position restored
 *   with the screen (coming back from a document) survives.
 */
@Composable
fun ScrollToTopOnNewResults(
    resultsKey: Any?,
    state: LazyListState,
) = ScrollToTopOnNewResults(resultsKey) { state.requestScrollToItem(0) }

/** Grid flavour of [ScrollToTopOnNewResults]. */
@Composable
fun ScrollToTopOnNewResults(
    resultsKey: Any?,
    state: LazyGridState,
) = ScrollToTopOnNewResults(resultsKey) { state.requestScrollToItem(0) }

@Composable
private fun ScrollToTopOnNewResults(
    resultsKey: Any?,
    scrollToTop: () -> Unit,
) {
    var applied by remember { mutableStateOf(resultsKey) }
    SideEffect {
        if (applied != resultsKey) {
            applied = resultsKey
            scrollToTop()
        }
    }
}
