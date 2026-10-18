package dev.npn.app.ui.detail

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** How wheel and touchpad scroll at 1x add up to page flips in the viewer. */
class WheelPagingTest {
    @Test
    fun aWheelNotchFlipsAtOnce() {
        wheelPageStep(gathered = 0f, delta = 1f) shouldBe WheelStep(pages = 1, remainder = 0f)
        wheelPageStep(gathered = 0f, delta = -3f) shouldBe WheelStep(pages = -1, remainder = 0f)
    }

    @Test
    fun touchpadScrollAddsUpToAFlip() {
        val first = wheelPageStep(gathered = 0f, delta = 0.4f)
        first shouldBe WheelStep(pages = 0, remainder = 0.4f)
        val second = wheelPageStep(first.remainder, delta = 0.4f)
        second.pages shouldBe 0
        wheelPageStep(second.remainder, delta = 0.4f) shouldBe WheelStep(pages = 1, remainder = 0f)
    }

    /** Turning back starts over from the turn, so what was gathered the other way never counts. */
    @Test
    fun scrollingTheOtherWayStartsOver() {
        wheelPageStep(gathered = 0.9f, delta = -0.5f) shouldBe WheelStep(pages = 0, remainder = -0.5f)
        wheelPageStep(gathered = -0.9f, delta = 1.2f) shouldBe WheelStep(pages = 1, remainder = 0f)
    }

    @Test
    fun theStateGathersAcrossEvents() {
        val state = PageViewerState()
        state.wheelPages(0.6f) shouldBe 0
        state.wheelPages(0.6f) shouldBe 1
        state.wheelPages(0.6f) shouldBe 0
        state.wheelPages(-0.2f) shouldBe 0
        state.wheelPages(-0.9f) shouldBe -1
    }
}
