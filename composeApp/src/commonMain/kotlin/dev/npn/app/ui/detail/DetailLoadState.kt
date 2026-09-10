package dev.npn.app.ui.detail

import androidx.compose.runtime.Immutable
import dev.npn.core.app.DocListItem

/**
 * Whether the document row itself has resolved. [observeDoc] emits null both before the first
 * query lands *and* when the document is gone, and the two must not look the same: showing the
 * "unavailable" surface before the first emission is the false-negative flash this models away.
 *
 * The viewmodel only downgrades to [Missing] once it has seen at least one emission (or was never
 * seeded with a row), so [Loading] genuinely means "not resolved yet".
 */
@Immutable
sealed interface DocLoadState {
    /** No row yet and none has been observed; keep the surface neutral rather than "unavailable". */
    data object Loading : DocLoadState

    /** The row is here; render it. */
    data class Present(
        val item: DocListItem,
    ) : DocLoadState

    /** Observed and confirmed absent: trashed, or deleted on another replica while open. */
    data object Missing : DocLoadState
}

/**
 * Page count as an explicit lifecycle rather than a bare `Int` where 0 doubled as both "loading"
 * and "empty". The viewer keyed its spinner off `count <= 0`, so a genuine failure (a corrupt or
 * unreadable PDF) span forever; these cases are now distinct and each gets its own surface, with
 * [Failed] carrying a retry.
 */
@Immutable
sealed interface PageCountState {
    data object Loading : PageCountState

    data class Ready(
        val count: Int,
    ) : PageCountState

    /** The document opened but reported no pages; not an error, but not a viewer either. */
    data object Empty : PageCountState

    /** Resolving the count failed (unreadable/corrupt file, or the doc is gone). Offers a retry. */
    data object Failed : PageCountState
}

/** The number of pages to page through, or 0 when there is nothing (or nothing yet) to show. */
val PageCountState.count: Int
    get() = (this as? PageCountState.Ready)?.count ?: 0

/** Resolve a nullable page-count result into a [PageCountState]: null is a failure, 0 is empty. */
internal fun pageCountStateOf(count: Int?): PageCountState =
    when {
        count == null -> PageCountState.Failed
        count <= 0 -> PageCountState.Empty
        else -> PageCountState.Ready(count)
    }
