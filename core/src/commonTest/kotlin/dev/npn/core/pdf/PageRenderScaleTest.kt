package dev.npn.core.pdf

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Render-scale math for [pageRenderScale]. The property that fixes blurry receipts: an elongated
 * page's shorter (readable) side gets meaningfully more pixels than a naive "cap the longer side"
 * scale would give, while normal pages are unaffected.
 */
class PageRenderScaleTest {
    @Test
    fun normalPagesScaleByLongerSide() {
        // US Letter 612x792pt at maxDim 1200 -> 1200/792.
        pageRenderScale(612f, 792f, 1200) shouldBe (1200f / 792f plusOrMinus 0.001f)
        // Orientation-independent.
        pageRenderScale(792f, 612f, 1200) shouldBe (1200f / 792f plusOrMinus 0.001f)
    }

    @Test
    fun tallReceiptGetsHigherResolutionThanNaiveLongerSideCap() {
        // A narrow, tall receipt: ~226 x 1100pt (aspect ~4.9).
        val naive = 1200f / 1100f // old "cap the longer side" behaviour
        val scale = pageRenderScale(226f, 1100f, 1200)
        scale shouldBeGreaterThan naive
        // Shorter (readable) side is lifted to maxDim / 3, i.e. 400px wide instead of ~247px.
        val widthPx = 226f * scale
        widthPx shouldBe (400f plusOrMinus 1f)
    }

    @Test
    fun extremePageIsCappedInTotalPixels() {
        // A pathological 100 x 5000pt page must not render an unbounded bitmap.
        val scale = pageRenderScale(100f, 5000f, 4800)
        val pixels = (100f * scale) * (5000f * scale)
        pixels shouldBeGreaterThan 0f
        // Under the internal cap (4800*2600); allow the exact boundary.
        (pixels <= 4800f * 2600f + 1f) shouldBe true
    }

    @Test
    fun degenerateSizesDoNotCrash() {
        pageRenderScale(0f, 0f, 1200) shouldBeGreaterThan 0f
    }
}
