package dev.npn.app.ui.detail

import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/**
 * The detail's documents as a horizontal pager on compact widths: swipe, or the caller's own
 * previous/next actions, move through the library's current order. [onShowDocument] reports each page the
 * pager settles on, so the caller can let go of the other documents' viewmodels and remember the
 * one on screen across a width change; the starting page counts as shown.
 *
 * [page] gets the page's index and document id, whether it is the current one, and the pager's
 * previous/next actions, null at the ends of the list.
 */
@Composable
internal fun DocumentPager(
    order: DetailBrowseOrder,
    modifier: Modifier = Modifier,
    onShowDocument: (String) -> Unit = {},
    page: @Composable (
        index: Int,
        docId: String,
        isCurrent: Boolean,
        onPrevious: (() -> Unit)?,
        onNext: (() -> Unit)?,
        whenStillSelected: (() -> Unit) -> Unit,
    ) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = order.initialPage) { order.ids.size }
    val scope = rememberCoroutineScope()
    val currentOnShowDocument by rememberUpdatedState(onShowDocument)
    LaunchedEffect(pagerState, order) {
        snapshotFlow { pagerState.settledPage }.collect { pageIndex ->
            order.idAt(pageIndex)?.let(currentOnShowDocument)
        }
    }
    HorizontalPager(
        state = pagerState,
        modifier = modifier,
        key = { order.ids[it] },
    ) { pageIndex ->
        page(
            pageIndex,
            order.ids[pageIndex],
            pageIndex == pagerState.currentPage,
            if (order.hasPrevious(pageIndex)) {
                { scope.launch { pagerState.animateScrollToPage(pageIndex - 1) } }
            } else {
                null
            },
            if (order.hasNext(pageIndex)) {
                { scope.launch { pagerState.animateScrollToPage(pageIndex + 1) } }
            } else {
                null
            },
            { action ->
                if (!pagerState.isScrollInProgress && order.idAt(pagerState.settledPage) == order.ids[pageIndex]) {
                    action()
                }
            },
        )
    }
}
