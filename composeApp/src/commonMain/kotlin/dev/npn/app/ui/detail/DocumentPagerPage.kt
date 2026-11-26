package dev.npn.app.ui.detail

/**
 * One page of [DocumentPager]: its index and document id, whether it is the current one, and the
 * pager's previous/next actions, null at the ends of the list.
 *
 * [whenStillSelected] runs an action only while this page is still the settled one, so a completion
 * that lands after a swipe does not act for the page that left. [holdSwipe] keeps the pager from
 * being swiped away from this page while it is true ([HoldSwipeWhileEditing]); the previous/next
 * actions still move.
 */
internal class DocumentPagerPage(
    val index: Int,
    val docId: String,
    val isCurrent: Boolean,
    val onPrevious: (() -> Unit)?,
    val onNext: (() -> Unit)?,
    val whenStillSelected: (() -> Unit) -> Unit,
    val holdSwipe: (Boolean) -> Unit,
)
