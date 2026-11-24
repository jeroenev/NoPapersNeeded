package dev.npn.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

/**
 * The page transition each platform's navigation host uses by default, so reduced motion can replace
 * it with no transition without changing what every other user sees. Android cross-fades for 700ms;
 * desktop changes screens at once.
 */
internal expect fun defaultPageEnter(): EnterTransition

internal expect fun defaultPageExit(): ExitTransition

/**
 * The navigation host's own predictive-back transition, which it plays while a back swipe is in
 * progress instead of the pop transition: Android fades the page below in and shrinks the leaving
 * one; desktop has none.
 */
internal expect fun defaultPredictivePopEnter(): EnterTransition

internal expect fun defaultPredictivePopExit(): ExitTransition

/**
 * The transitions a navigation host plays: the platform's defaults, or none at all when motion is
 * reduced. The predictive-back pair is a default of its own, apart from the pop pair, so it is
 * replaced too: otherwise a back swipe on Android would still shrink the page.
 */
internal class PageTransitions(
    reduced: Boolean,
) {
    val enter: EnterTransition = if (reduced) EnterTransition.None else defaultPageEnter()
    val exit: ExitTransition = if (reduced) ExitTransition.None else defaultPageExit()
    val predictivePopEnter: EnterTransition = if (reduced) EnterTransition.None else defaultPredictivePopEnter()
    val predictivePopExit: ExitTransition = if (reduced) ExitTransition.None else defaultPredictivePopExit()
}
