package dev.npn.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.DefaultNavTransitions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.lang.reflect.Proxy
import kotlin.test.Test

/**
 * Reduced motion leaves the navigation host nothing to animate, the back swipe included: the
 * predictive-back pair is a default of its own, apart from the pop pair, and Android shrinks the page
 * with it. Otherwise the host gets the navigation library's own defaults, not a copy of them.
 * In desktopTest for the scope's stand-in; nothing here is platform code any more.
 */
class PageTransitionsTest {
    @Test
    fun reducedMotionReplacesEveryTransitionIncludingTheBackSwipe() {
        val reduced = PageTransitions(reduced = true)
        reduced.enter(scope) shouldBe EnterTransition.None
        reduced.exit(scope) shouldBe ExitTransition.None
        reduced.predictivePopEnter(scope, 0) shouldBe EnterTransition.None
        reduced.predictivePopExit(scope, 0) shouldBe ExitTransition.None
    }

    @Test
    fun otherwiseTheNavigationLibrarysDefaultsStand() {
        val animated = PageTransitions(reduced = false)
        animated.enter shouldBeSameInstanceAs DefaultNavTransitions.enterTransition
        animated.exit shouldBeSameInstanceAs DefaultNavTransitions.exitTransition
        animated.predictivePopEnter shouldBeSameInstanceAs DefaultNavTransitions.predictivePopEnterTransition
        animated.predictivePopExit shouldBeSameInstanceAs DefaultNavTransitions.predictivePopExitTransition
    }

    /** None of the replacements looks at its scope, so a stand-in that answers nothing will do. */
    @Suppress("UNCHECKED_CAST")
    private val scope =
        Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(AnimatedContentTransitionScope::class.java),
        ) { _, method, _ -> error("unexpected call to ${method.name}") }
            as AnimatedContentTransitionScope<NavBackStackEntry>
}
