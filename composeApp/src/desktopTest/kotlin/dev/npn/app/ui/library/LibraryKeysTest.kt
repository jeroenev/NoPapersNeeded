@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.closing
import dev.npn.app.ui.focusFirst
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
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
            pressInLibrary(Key.Escape, selectionMode = true)
            pressInLibrary(Key.Escape, selectionMode = false)

            ran shouldContainExactly listOf("clear selection", "clear query")
        }

    @Test
    fun deleteTrashesOnlyASelection() =
        onUiThread {
            pressInLibrary(Key.Delete, selectionMode = false)
            pressInLibrary(Key.Delete, selectionMode = true)

            ran shouldContainExactly listOf("trash")
        }

    /** Letters bind only with Ctrl, so a bare letter is never taken from the rest of the screen. */
    @Test
    fun lettersNeedCtrlAndSlashDoesNot() =
        onUiThread {
            pressInLibrary(Key.F)
            pressInLibrary(Key.O)
            pressInLibrary(Key.A)
            pressInLibrary(Key.F, ctrl = true)
            pressInLibrary(Key.O, ctrl = true)
            pressInLibrary(Key.A, ctrl = true)
            pressInLibrary(Key.Slash)

            ran shouldContainExactly listOf("focus search", "import", "select all", "focus search")
        }

    @Test
    fun nothingFiresWhileTheSearchFieldHasFocus() =
        onUiThread {
            pressInLibrary(Key.Escape, searchFocused = true)
            pressInLibrary(Key.A, ctrl = true, searchFocused = true)

            ran.shouldBeEmpty()
        }

    /** Compose a focused node wearing the library's keys in the given state, and press [key] on it. */
    private fun pressInLibrary(
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
        scene.closing {
            render()
            focusFirst()
            press(key, ctrl = ctrl)
        }
    }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }
}
