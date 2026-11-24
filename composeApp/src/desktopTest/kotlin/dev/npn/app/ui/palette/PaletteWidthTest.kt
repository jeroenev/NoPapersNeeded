@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.palette

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PaletteWidthTest {
    @Test
    fun cardFitsCompactWindowsAndIsCappedInWideWindows() =
        onUiThread {
            for ((width, expected) in listOf(400 to 400, 1200 to 640)) {
                var measured = 0
                val scene =
                    themedScene(width, 200) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.paletteCardWidth().onSizeChanged { measured = it.width })
                        }
                    }
                scene.closing { render() }
                measured shouldBe expected
            }
        }
}
