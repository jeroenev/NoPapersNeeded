@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/** The library's shortcuts on a focused node, as the library screen's content column wears them. */
class LibraryKeysTest {
    private val ran = mutableListOf<String>()

    /** Escape backs out one level at a time: out of selection mode first, then the search. */
    @Test
    fun escapeLeavesSelectionModeBeforeItClearsTheSearch() =
        onUiThread {
            press(Key.Escape, selectionMode = true)
            press(Key.Escape, selectionMode = false)

            ran shouldContainExactly listOf("clear selection", "clear query")
        }

    @Test
    fun deleteTrashesOnlyASelection() =
        onUiThread {
            press(Key.Delete, selectionMode = false)
            press(Key.Delete, selectionMode = true)

            ran shouldContainExactly listOf("trash")
        }

    /** Letters bind only with Ctrl, so a bare letter is never taken from the rest of the screen. */
    @Test
    fun lettersNeedCtrlAndSlashDoesNot() =
        onUiThread {
            press(Key.F)
            press(Key.O)
            press(Key.A)
            press(Key.F, ctrl = true)
            press(Key.O, ctrl = true)
            press(Key.A, ctrl = true)
            press(Key.Slash)

            ran shouldContainExactly listOf("focus search", "import", "select all", "focus search")
        }

    @Test
    fun nothingFiresWhileTheSearchFieldHasFocus() =
        onUiThread {
            press(Key.Escape, searchFocused = true)
            press(Key.A, ctrl = true, searchFocused = true)

            ran.shouldBeEmpty()
        }

    private fun press(
        key: Key,
        ctrl: Boolean = false,
        selectionMode: Boolean = false,
        searchFocused: Boolean = false,
    ) {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                Box(
                    Modifier
                        .size(10.dp)
                        .focusable()
                        .libraryKeys(
                            searchFocused = searchFocused,
                            selectionMode = selectionMode,
                            onFocusSearch = { ran += "focus search" },
                            onImport = { ran += "import" },
                            onSelectAll = { ran += "select all" },
                            onRequestTrash = { ran += "trash" },
                            onClearSelection = { ran += "clear selection" },
                            onClearQuery = { ran += "clear query" },
                        ),
                )
            }
        try {
            scene.render()
            scene
                .nodes()
                .first { it.config.getOrNull(SemanticsActions.RequestFocus) != null }
                .config[SemanticsActions.RequestFocus]
                .action
                ?.invoke()
            scene.render()
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }
}
