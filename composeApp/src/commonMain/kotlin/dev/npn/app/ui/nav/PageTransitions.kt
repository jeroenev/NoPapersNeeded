package dev.npn.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.DefaultNavTransitions

private typealias Enter = AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition
private typealias Exit = AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition
private typealias PredictiveEnter = AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> EnterTransition
private typealias PredictiveExit = AnimatedContentTransitionScope<NavBackStackEntry>.(Int) -> ExitTransition

/**
 * The transitions a navigation host plays: none at all when motion is reduced, otherwise the
 * navigation library's own defaults ([DefaultNavTransitions]), so every other user sees exactly what
 * the host would play unasked and a library update that changes them reaches the app as is.
 *
 * The pop pair is left to the host, which derives it from [enter] and [exit]. The predictive-back
 * pair is a default of its own, so it is replaced too: otherwise a back swipe on Android would still
 * shrink the page.
 */
internal class PageTransitions(
    reduced: Boolean,
) {
    val enter: Enter = if (reduced) NoEnter else DefaultNavTransitions.enterTransition
    val exit: Exit = if (reduced) NoExit else DefaultNavTransitions.exitTransition
    val predictivePopEnter: PredictiveEnter =
        if (reduced) NoPredictiveEnter else DefaultNavTransitions.predictivePopEnterTransition
    val predictivePopExit: PredictiveExit =
        if (reduced) NoPredictiveExit else DefaultNavTransitions.predictivePopExitTransition

    private companion object {
        val NoEnter: Enter = { EnterTransition.None }
        val NoExit: Exit = { ExitTransition.None }
        val NoPredictiveEnter: PredictiveEnter = { EnterTransition.None }
        val NoPredictiveExit: PredictiveExit = { ExitTransition.None }
    }
}
