package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Reduced motion leaves the navigation host nothing to animate, the back swipe included: the
 * predictive-back pair is a default of its own, apart from the pop pair, and Android shrinks the page
 * with it. In commonTest, so the Android host run checks Android's defaults too.
 */
class PageTransitionsTest {
    @Test
    fun reducedMotionReplacesEveryTransitionIncludingTheBackSwipe() {
        val reduced = PageTransitions(reduced = true)
        reduced.enter shouldBe EnterTransition.None
        reduced.exit shouldBe ExitTransition.None
        reduced.predictivePopEnter shouldBe EnterTransition.None
        reduced.predictivePopExit shouldBe ExitTransition.None
    }

    @Test
    fun otherwiseThePlatformDefaultsStand() {
        val animated = PageTransitions(reduced = false)
        animated.enter shouldBe defaultPageEnter()
        animated.exit shouldBe defaultPageExit()
        animated.predictivePopEnter shouldBe defaultPredictivePopEnter()
        animated.predictivePopExit shouldBe defaultPredictivePopExit()
    }
}
