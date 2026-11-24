@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.core.ai.AiRunEstimate
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AiOcrEstimateTest : EnglishLocaleTest() {
    @Test
    fun pricedAndUnpricedOcrEstimatesDoNotPromiseAnUnenforcedCap() =
        onUiThread {
            for (price in listOf(0.5, null)) {
                val estimate = AiRunEstimate(1, 21, 21, 1000, 100, price, price != null, 1)
                val scene = ImageComposeScene(800, 600) { Column { AiOcrEstimate(estimate) } }
                scene.closing {
                    render()
                    val text = nodes().flatMap { it.texts() }.joinToString(" ")
                    text.contains("1,000").or(text.contains("1000")) shouldBe true
                    text.contains("cap", ignoreCase = true) shouldBe false
                }
            }
        }
}
