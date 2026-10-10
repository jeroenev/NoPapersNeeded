package dev.npn.app.ui.detail

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import dev.npn.app.ui.common.keyShortcuts
import dev.npn.app.ui.common.previewKeyShortcuts

// The document view's desktop shortcuts, one modifier per surface. Each fires only while its node
// or something inside it holds focus.

/**
 * The page viewer: arrows / PageUp-PageDown page, Home/End jump to the first/last page, and +/-/0
 * zoom in/out/reset. Paging is delegated to [onPage] (which animates the pager); zoom mutates
 * [transform] via [onTransform], anchored at the container center. Ctrl+arrows pass on to
 * [docPagingKeys].
 *
 * The detail screen gives the viewer focus on entry, so these work before anything is clicked.
 */
internal fun Modifier.viewerKeys(
    pagerState: PagerState,
    pageCount: Int,
    transform: () -> ViewerTransform,
    onTransform: (ViewerTransform) -> Unit,
    containerSize: () -> Size,
    fittedSize: () -> Size,
    onPage: (Int) -> Unit,
): Modifier =
    keyShortcuts { event ->
        val page = pagerState.currentPage

        fun toPage(target: Int) = { onPage(target) }

        fun zoomBy(delta: Float) = { onTransform(zoomAtCenter(transform(), delta, containerSize(), fittedSize())) }
        val resetZoom = { onTransform(ViewerTransform()) }
        when (event.key) {
            Key.DirectionLeft -> toPage(page - 1).takeUnless { event.isCtrlPressed }
            Key.DirectionRight -> toPage(page + 1).takeUnless { event.isCtrlPressed }
            Key.PageUp -> toPage(page - 1)
            Key.PageDown -> toPage(page + 1)
            Key.MoveHome -> toPage(0)
            Key.MoveEnd -> toPage(pageCount - 1)
            Key.Plus, Key.Equals -> zoomBy(ZOOM_STEP)
            Key.Minus -> zoomBy(-ZOOM_STEP)
            Key.Zero -> resetZoom
            else -> null
        }
    }

/** Ctrl+Left/Right walks to the previous/next document; a key with nowhere to go passes on. */
internal fun Modifier.docPagingKeys(
    onPrevDoc: (() -> Unit)?,
    onNextDoc: (() -> Unit)?,
): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.DirectionLeft -> onPrevDoc
            Key.DirectionRight -> onNextDoc
            else -> null
        }?.takeIf { event.isCtrlPressed }
    }

/**
 * Ctrl+S saves the document's draft. It sees the key before the focused field does, so it saves
 * from inside a text field too.
 */
internal fun Modifier.saveKeys(onSave: () -> Unit): Modifier =
    previewKeyShortcuts { event ->
        when (event.key) {
            Key.S -> onSave.takeIf { event.isCtrlPressed }
            else -> null
        }
    }

/** Ctrl+F opens the text tab's find field. */
internal fun Modifier.findKeys(onFind: () -> Unit): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.F -> onFind.takeIf { event.isCtrlPressed }
            else -> null
        }
    }

/**
 * Left/Right move the divider between the page preview and the metadata tabs; Ctrl+Left/Right pass
 * on to [docPagingKeys].
 */
internal fun Modifier.paneResizeKeys(
    onShrink: () -> Unit,
    onGrow: () -> Unit,
): Modifier =
    keyShortcuts { event ->
        when (event.key) {
            Key.DirectionLeft -> onShrink
            Key.DirectionRight -> onGrow
            else -> null
        }?.takeUnless { event.isCtrlPressed }
    }
