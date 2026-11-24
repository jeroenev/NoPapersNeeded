@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.palette

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The palette's card fills only part of its dialog, and the dimmed rest is the dialog's too, so
 * the dialog never sees a click outside it: the backdrop has to close the palette itself.
 */
class PaletteDismissTest : EnglishLocaleTest() {
    @Test
    fun aTapOnTheDimmedBackdropClosesThePalette() {
        // Beside the card, which is at most 640 dp wide and centred in a 1200 dp window.
        tapAt(Offset(60f, 400f)) shouldBe 1
    }

    @Test
    fun aTapInsideTheCardKeepsItOpen() {
        // The card's empty-results area, which holds nothing clickable of its own.
        tapAt(Offset(600f, 300f)) shouldBe 0
    }

    /** Show the palette with no results in a 1200×800 window, tap at [at], and count its closes. */
    private fun tapAt(at: Offset): Int {
        val actions = CountingActions()
        onUiThread {
            themedScene(WIDTH, HEIGHT) { PaletteDialog(PaletteState(open = true), actions) }.closing {
                render()
                tap(at)
                render()
            }
        }
        return actions.closes
    }

    private fun ImageComposeScene.tap(at: Offset) {
        fun touch(
            type: PointerEventType,
            pressed: Boolean,
        ) = sendPointerEvent(type, listOf(ComposeScenePointer(PointerId(0), at, pressed, PointerType.Touch)))

        touch(PointerEventType.Press, pressed = true)
        render()
        touch(PointerEventType.Release, pressed = false)
    }

    private class CountingActions : CommandPaletteActions {
        var closes = 0

        override fun onQueryChange(text: String) = Unit

        override fun moveSelection(delta: Int) = Unit

        override fun runSelected() = Unit

        override fun run(item: PaletteItem) = Unit

        override fun close() {
            closes++
        }
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 800
    }
}
