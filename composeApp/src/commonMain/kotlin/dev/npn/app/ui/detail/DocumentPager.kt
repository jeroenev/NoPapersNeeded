package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.npn.app.ui.common.reducedMotion
import kotlinx.coroutines.launch

/**
 * The detail's documents as a horizontal pager on compact widths: swipe, or the caller's own
 * previous/next actions, move through the library's current order. [onShowDocument] reports each page the
 * pager settles on, so the caller can let go of the other documents' viewmodels and remember the
 * one on screen across a width change; the starting page counts as shown.
 *
 * The previous/next actions slide to the neighbour, or move there at once when motion is reduced.
 * The current page can hold the swipe ([DocumentPagerPage.holdSwipe]) while the user edits it.
 */
@Composable
internal fun DocumentPager(
    order: DetailBrowseOrder,
    modifier: Modifier = Modifier,
    onShowDocument: (String) -> Unit = {},
    page: @Composable (DocumentPagerPage) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = order.initialPage) { order.ids.size }
    val scope = rememberCoroutineScope()
    val currentOnShowDocument by rememberUpdatedState(onShowDocument)
    val reduced = reducedMotion
    // Which composed pages hold the swipe, by document id; only the current page's hold counts.
    val held = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(pagerState, order) {
        snapshotFlow { pagerState.settledPage }.collect { pageIndex ->
            order.idAt(pageIndex)?.let(currentOnShowDocument)
        }
    }

    fun moveTo(pageIndex: Int): () -> Unit =
        {
            scope.launch { pagerState.moveTo(pageIndex, reduced) }
        }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        userScrollEnabled = order.idAt(pagerState.currentPage)?.let { held[it] } != true,
        key = { order.ids[it] },
    ) { pageIndex ->
        val docId = order.ids[pageIndex]
        DisposableEffect(docId) { onDispose { held.remove(docId) } }
        page(
            DocumentPagerPage(
                index = pageIndex,
                docId = docId,
                isCurrent = pageIndex == pagerState.currentPage,
                onPrevious = if (order.hasPrevious(pageIndex)) moveTo(pageIndex - 1) else null,
                onNext = if (order.hasNext(pageIndex)) moveTo(pageIndex + 1) else null,
                whenStillSelected = { action ->
                    if (!pagerState.isScrollInProgress && order.idAt(pagerState.settledPage) == docId) {
                        action()
                    }
                },
                holdSwipe = { hold -> if (held[docId] != hold) held[docId] = hold },
            ),
        )
    }
}

private suspend fun PagerState.moveTo(
    pageIndex: Int,
    reduced: Boolean,
) {
    if (reduced) scrollToPage(pageIndex) else animateScrollToPage(pageIndex)
}

/**
 * Hold [page]'s swipe while its document is being edited: the metadata sheet is expanded or the
 * keyboard is up. A horizontal drag in the editor (a long single-line field scrolling, say) then
 * stays in the editor instead of moving to the next document mid-edit.
 */
@Composable
internal fun HoldSwipeWhileEditing(
    page: DocumentPagerPage,
    sheet: PeekSheetState,
) {
    val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val hold = sheet.expanded || keyboardUp
    SideEffect { page.holdSwipe(hold) }
}
